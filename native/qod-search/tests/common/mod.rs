//! Shared by the integration tests: the part 2 corpus rule, segment builds, the C ABI glue and naive
//! BM25 references (one field and several).
#![allow(dead_code)]
use qod_search_core::bundle::{self, Doc, MacKey, Segment, Source};
use std::ffi::{CStr, CString};
use tantivy::fieldnorm::FieldNormReader;
use tantivy::tokenizer::{LowerCaser, RemoveLongFilter, SimpleTokenizer, TextAnalyzer};

pub const INDEX_ID: &str = "0b6c2a4e-6f1d-4c8e-9a3b-7d5e1f2c3a4b";
/// The table id every one-field test bundle records.
pub const TABLE_ID: i64 = 1;
/// The one field of the ported v1 tests: column `body`, field id 1.
pub const BODY: &[(&str, i64)] = &[("body", 1)];

fn splitmix64(mut x: u64) -> u64 {
    x = x.wrapping_add(0x9e37_79b9_7f4a_7c15);
    x = (x ^ (x >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
    x = (x ^ (x >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
    x ^ (x >> 31)
}

pub fn hash2(i: u64, j: u64) -> u64 {
    splitmix64(splitmix64(i) ^ j.wrapping_mul(0xd6e8_feb8_6659_fd93))
}

/// Task 3's generator rule (bench/search/corpus/gen_pages.sql) with a hash of our own in place of
/// DuckDB's `hash()`: doc i has 20 + h(i, len) % 180 words, word j is w<floor(u^4 * 50000)> with
/// u = (h(i, j) % 2^32) / 2^32. Same distribution, not the same texts.
pub fn page_text(i: u64) -> String {
    let len = 20 + hash2(i, u64::MAX) % 180;
    words(i, len)
}

fn words(i: u64, len: u64) -> String {
    let words: Vec<String> = (0..len)
        .map(|j| {
            let u = (hash2(i, j) % 4_294_967_296) as f64 / 4_294_967_296.0;
            format!("w{}", (u.powi(4) * 50_000.0).floor() as u64)
        })
        .collect();
    words.join(" ")
}

/// A short title of the same vocabulary: 3 to 14 words.
pub fn title_text(i: u64) -> String {
    let len = 3 + hash2(i, u64::MAX - 1) % 12;
    words(i.wrapping_add(50_000_000), len)
}

/// A random text of the same vocabulary, from `seed`.
pub fn random_text(seed: u64) -> String {
    page_text(seed.wrapping_add(10_000_000))
}

/// The TEXT field's analyzer (Tantivy's "default").
pub fn analyzer() -> TextAnalyzer {
    TextAnalyzer::builder(SimpleTokenizer::default())
        .filter(RemoveLongFilter::limit(40))
        .filter(LowerCaser)
        .build()
}

pub fn tokens(text: &str) -> Vec<String> {
    let mut a = analyzer();
    let mut s = a.token_stream(text);
    let mut out = Vec::new();
    while s.advance() {
        out.push(s.token().text.clone());
    }
    out
}

/// A source of the one-field layout (`body`, field id 1, table 1).
pub fn source_for(path: &str) -> Source {
    source_with(path, TABLE_ID, BODY)
}

pub fn source_with(path: &str, table_id: i64, fields: &[(&str, i64)]) -> Source {
    Source::new(INDEX_ID, path, 1_000_000 + path.len() as u64, 4_000 + path.len() as u64, table_id, fields, test_mac_key(INDEX_ID))
}

/// The bundle MAC key the tests give index `index_id`: a fixed function of the id, distinct per
/// index (the manager derives the real one from the node-key secret, NodeKeys.searchBundleMacKey).
pub fn test_mac_key(index_id: &str) -> MacKey {
    MacKey::new(&qod_search_core::sha256::digest(format!("qod-search-test-mac-key|{index_id}").as_bytes())).unwrap()
}

/// The bundle of one-field test docs, as bytes.
pub fn build_bytes(src: &Source, docs: &[TDoc]) -> Vec<u8> {
    bundle::build(src, docs.iter().enumerate().map(|(p, d)| Doc::one(p as u64, d.rowid, -1, d.text.clone()))).unwrap()
}

/// A bundle's body: every byte before its MAC.
pub fn unseal(bytes: &[u8]) -> &[u8] {
    &bytes[..bytes.len() - bundle::MAC_LEN]
}

/// `body` with the MAC of `src` appended: what a writer holding the index's key would publish, so a
/// test that edits a bundle's body reaches the parser behind the MAC.
pub fn seal(body: &[u8], src: &Source) -> Vec<u8> {
    let label = bundle::mac_label(src).unwrap();
    let mac = qod_search_core::sha256::hmac(src.mac_key.bytes(), &[label.as_bytes(), b"\n", body]);
    let mut out = body.to_vec();
    out.extend_from_slice(&mac);
    out
}

/// Rewrites the MAC of a bundle whose body a test edited in place.
pub fn reseal(bytes: &mut Vec<u8>, src: &Source) {
    let body = unseal(bytes).to_vec();
    *bytes = seal(&body, src);
}

/// Sets the footer's format version of a bundle body (no MAC) and rewrites the footer's CRC.
pub fn set_footer_version(body: &mut [u8], version: u32) {
    let t = body.len() - 28;
    let fo = u64::from_le_bytes(body[t..t + 8].try_into().unwrap()) as usize;
    body[fo + 8..fo + 12].copy_from_slice(&version.to_le_bytes());
    let crc = crc32fast::hash(&body[fo..t]);
    body[t + 8..t + 12].copy_from_slice(&crc.to_le_bytes());
}

/// One test doc: its text and rowid.
#[derive(Clone, Debug)]
pub struct TDoc {
    pub rowid: i64,
    pub text: String,
}

pub fn build(path: &str, docs: &[TDoc]) -> Segment {
    let src = source_for(path);
    let bytes = bundle::build(
        &src,
        docs.iter().enumerate().map(|(p, d)| Doc::one(p as u64, d.rowid, -1, d.text.clone())),
    )
    .unwrap();
    bundle::open(&bytes, &src).unwrap()
}

/// One doc of several fields: its rowid and one text per field (None = SQL NULL).
#[derive(Clone, Debug)]
pub struct MDoc {
    pub rowid: i64,
    pub texts: Vec<Option<String>>,
}

pub fn build_multi(path: &str, table_id: i64, fields: &[(&str, i64)], docs: &[MDoc]) -> Segment {
    let src = source_with(path, table_id, fields);
    bundle::open(&build_multi_bytes(&src, docs), &src).unwrap()
}

pub fn build_multi_bytes(src: &Source, docs: &[MDoc]) -> Vec<u8> {
    bundle::build(
        src,
        docs.iter().enumerate().map(|(p, d)| Doc { position: p as u64, rowid: d.rowid, snapshot: -1, texts: d.texts.clone() }),
    )
    .unwrap()
}

/// The 20,000-doc corpus split over 3 segments of 9,000, 7,000 and 4,000 docs, rowids
/// row_id_start + position with starts 0, 9,000 and 16,000.
pub fn corpus() -> Vec<Vec<TDoc>> {
    let mut out = Vec::new();
    for (start, n) in [(0u64, 9_000u64), (9_000, 7_000), (16_000, 4_000)] {
        out.push((0..n).map(|p| TDoc { rowid: (start + p) as i64, text: page_text(start + p) }).collect());
    }
    out
}

/// The two-field corpus (`title`, `body`) of `n` docs over the given segment sizes: rowid = doc
/// number; every 97th doc has a NULL title.
pub fn multi_corpus(sizes: &[usize]) -> Vec<Vec<MDoc>> {
    let mut out = Vec::new();
    let mut i = 0u64;
    for n in sizes {
        let mut seg = Vec::new();
        for _ in 0..*n {
            let title = if i % 97 == 0 { None } else { Some(title_text(i)) };
            seg.push(MDoc { rowid: i as i64, texts: vec![title, Some(page_text(i))] });
            i += 1;
        }
        out.push(seg);
    }
    out
}

pub fn bitset(n: usize, f: impl Fn(usize) -> bool) -> Vec<u64> {
    let mut w = vec![0u64; n.div_ceil(64)];
    for d in 0..n {
        if f(d) {
            w[d / 64] |= 1 << (d % 64);
        }
    }
    w
}

/// Tantivy's quantized fieldnorm of an exact token count: the largest code value at or below it
/// (FieldNormReader::id_to_fieldnorm over the 256 codes).
pub fn quantized(count: u32) -> u32 {
    (0..=255u8).map(FieldNormReader::id_to_fieldnorm).filter(|v| *v <= count).max().unwrap_or(0)
}

/// A doc of the reference: rowid, its tokens.
pub struct RefDoc {
    pub rowid: i64,
    pub tokens: Vec<String>,
}

pub fn ref_docs(docs: &[TDoc]) -> Vec<RefDoc> {
    docs.iter().map(|d| RefDoc { rowid: d.rowid, tokens: tokens(&d.text) }).collect()
}

/// The naive BM25 reference over `population` (N, exact avgdl, df), scoring the docs of
/// `population` that `scored` keeps: idf = ln(1 + (N - df + 0.5) / (df + 0.5)), score per term =
/// idf * (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * dl / avgdl)) with dl the quantized fieldnorm,
/// summed over the query's terms (the query is a disjunction of its tokens). `f32` follows
/// Tantivy's own order of f32 operations (query/bm25.rs); otherwise every step is in f64.
/// Returns every hit sorted by score descending then rowid ascending.
pub fn reference(
    population: &[&RefDoc],
    scored: &dyn Fn(&RefDoc) -> bool,
    query: &str,
    k1: f64,
    b: f64,
    f32_order: bool,
) -> Vec<(i64, f64)> {
    let terms = tokens(query);
    let n = population.len() as u64;
    let total: u64 = population.iter().map(|d| d.tokens.len() as u64).sum();
    let df: Vec<u64> =
        terms.iter().map(|t| population.iter().filter(|d| d.tokens.iter().any(|x| x == t)).count() as u64).collect();
    let mut out = Vec::new();
    for d in population.iter().filter(|d| scored(d)) {
        let dl = quantized(d.tokens.len() as u32);
        let mut score32 = 0f32;
        let mut score64 = 0f64;
        let mut matched = false;
        for (t, df) in terms.iter().zip(&df) {
            let tf = d.tokens.iter().filter(|x| *x == t).count() as u32;
            if tf == 0 {
                continue;
            }
            matched = true;
            if f32_order {
                score32 += term_score32(n, total, *df, tf, dl, k1 as f32, b as f32);
            } else {
                score64 += term_score64(n, total, *df, tf, dl, k1, b);
            }
        }
        if matched {
            out.push((d.rowid, if f32_order { score32 as f64 } else { score64 }));
        }
    }
    out.sort_by(|x, y| y.1.total_cmp(&x.1).then(x.0.cmp(&y.0)));
    out
}

/// One term's BM25 in Tantivy's f32 order of operations (query/bm25.rs).
pub fn term_score32(n: u64, total: u64, df: u64, tf: u32, dl: u32, k1: f32, b: f32) -> f32 {
    let avg = total as f32 / n as f32;
    let x = ((n - df) as f32 + 0.5) / (df as f32 + 0.5);
    let idf = (1.0f32 + x).ln();
    let weight = idf * (1.0 + k1);
    let norm = k1 * (1.0 - b + b * dl as f32 / avg);
    let tf = tf as f32;
    weight * (tf / (tf + norm))
}

/// One term's BM25 in f64, every step.
pub fn term_score64(n: u64, total: u64, df: u64, tf: u32, dl: u32, k1: f64, b: f64) -> f64 {
    let avg = total as f64 / n as f64;
    let idf = idf64(n, df);
    let tf = tf as f64;
    idf * (tf * (k1 + 1.0)) / (tf + k1 * (1.0 - b + b * dl as f64 / avg))
}

pub fn idf64(n: u64, df: u64) -> f64 {
    (1.0 + (n as f64 - df as f64 + 0.5) / (df as f64 + 0.5)).ln()
}

pub fn rel(got: f32, want: f64) -> f64 {
    ((got as f64 - want) / want).abs()
}

// ---- C ABI glue ----

#[allow(unused_imports)]
pub use qod_search_core::{QsField, QsHit, QsSearchArgs, QsSource, QsText};

/// Takes and frees an error string of the library.
pub unsafe fn take_err(e: *mut std::os::raw::c_char) -> String {
    if e.is_null() {
        return String::new();
    }
    let s = CStr::from_ptr(e).to_string_lossy().to_string();
    qod_search_core::qs_string_free(e);
    s
}

/// Owned C strings behind a list of `QsField`s.
pub struct CFields {
    _names: Vec<CString>,
    pub fields: Vec<QsField>,
}

pub fn c_fields(fields: &[(&str, i64)]) -> CFields {
    let names: Vec<CString> = fields.iter().map(|(c, _)| CString::new(*c).unwrap()).collect();
    let fields = names.iter().zip(fields).map(|(n, (_, id))| QsField { column: n.as_ptr(), field_id: *id }).collect();
    CFields { _names: names, fields }
}

/// Owned C strings behind a `QsSource`.
pub struct CSource {
    _strings: Vec<CString>,
    _fields: CFields,
    _key: Box<[u8; 32]>,
    pub source: QsSource,
}

pub fn c_source(src: &Source) -> CSource {
    let strings: Vec<CString> =
        [&src.index_id, &src.path_key, &src.path].iter().map(|s| CString::new(s.as_str()).unwrap()).collect();
    let pairs: Vec<(&str, i64)> = src.fields.iter().map(|(c, id)| (c.as_str(), *id)).collect();
    let fields = c_fields(&pairs);
    let key = Box::new(*src.mac_key.bytes());
    let source = QsSource {
        index_id: strings[0].as_ptr(),
        path_key: strings[1].as_ptr(),
        path: strings[2].as_ptr(),
        file_size: src.file_size,
        footer_size: src.footer_size,
        table_id: src.table_id,
        fields: fields.fields.as_ptr(),
        n_fields: fields.fields.len(),
        record_count: src.record_count,
        row_id_start: src.row_id_start,
        has_internal_row_id: src.has_internal_row_id as i32,
        has_internal_snapshot: src.has_internal_snapshot as i32,
        mac_key: key.as_ptr(),
        mac_key_len: key.len(),
    };
    CSource { _strings: strings, _fields: fields, _key: key, source }
}

/// Opens `bytes` through the C ABI.
pub fn c_open(bytes: &[u8], src: &Source) -> *mut qod_search_core::QsSegment {
    let cs = c_source(src);
    let mut err = std::ptr::null_mut();
    let seg = unsafe { qod_search_core::qs_segment_open(bytes.as_ptr(), bytes.len(), &cs.source, &mut err) };
    assert!(!seg.is_null(), "{}", unsafe { take_err(err) });
    seg
}

/// Owned arguments behind a `QsSearchArgs`.
pub struct CArgs {
    _query: CString,
    _fields: CFields,
    _segments: Vec<*const qod_search_core::QsSegment>,
    _live: Vec<*const u64>,
    _live_words: Vec<usize>,
    _allowed: Vec<*const u64>,
    _allowed_words: Vec<usize>,
    pub args: QsSearchArgs,
}

/// The arguments of a search; `allowed` empty means NULL (every doc allowed).
#[allow(clippy::too_many_arguments)]
pub fn c_args(
    segments: &[*const qod_search_core::QsSegment],
    live: &[Vec<u64>],
    allowed: &[Vec<u64>],
    population: i32,
    query: &str,
    fields: &[(&str, i64)],
    k: u32,
    cancel: *const u8,
    memory_budget: u64,
) -> CArgs {
    let query = CString::new(query).unwrap();
    let fields = c_fields(fields);
    let segments = segments.to_vec();
    let live_p: Vec<*const u64> = live.iter().map(|w| w.as_ptr()).collect();
    let live_words: Vec<usize> = live.iter().map(|w| w.len()).collect();
    let allowed_p: Vec<*const u64> = allowed.iter().map(|w| w.as_ptr()).collect();
    let allowed_words: Vec<usize> = allowed.iter().map(|w| w.len()).collect();
    let args = QsSearchArgs {
        segments: segments.as_ptr(),
        n_segments: segments.len(),
        live: live_p.as_ptr(),
        live_words: live_words.as_ptr(),
        allowed: if allowed.is_empty() { std::ptr::null() } else { allowed_p.as_ptr() },
        allowed_words: if allowed.is_empty() { std::ptr::null() } else { allowed_words.as_ptr() },
        population,
        query: query.as_ptr(),
        fields: fields.fields.as_ptr(),
        n_fields: fields.fields.len(),
        k1: 1.2,
        b: 0.75,
        k,
        path: qod_search_core::QS_PATH_EXHAUSTIVE,
        cancel,
        memory_budget,
    };
    CArgs {
        _query: query,
        _fields: fields,
        _segments: segments,
        _live: live_p,
        _live_words: live_words,
        _allowed: allowed_p,
        _allowed_words: allowed_words,
        args,
    }
}

pub fn empty_hit() -> QsHit {
    QsHit { rowid: 0, segment: 0, doc: 0, score: 0.0 }
}

/// qs_search through the C ABI: the hits, or the error message.
pub fn c_search(a: &QsSearchArgs) -> Result<Vec<QsHit>, String> {
    let mut out = vec![empty_hit(); a.k as usize];
    let mut n = 0usize;
    let mut err = std::ptr::null_mut();
    let rc = unsafe { qod_search_core::qs_search(a, out.as_mut_ptr(), out.len(), &mut n, &mut err) };
    if rc != 0 {
        assert_eq!(n, 0);
        return Err(unsafe { take_err(err) });
    }
    assert!(err.is_null());
    out.truncate(n);
    Ok(out)
}
