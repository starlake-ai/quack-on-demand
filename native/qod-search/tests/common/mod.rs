//! Shared by the integration tests: the part 2 corpus rule, segment builds and a naive BM25
//! reference.
#![allow(dead_code)]
use qod_search_core::bundle::{self, Doc, Segment, Source};
use tantivy::fieldnorm::FieldNormReader;
use tantivy::tokenizer::{LowerCaser, RemoveLongFilter, SimpleTokenizer, TextAnalyzer};

pub const INDEX_ID: &str = "0b6c2a4e-6f1d-4c8e-9a3b-7d5e1f2c3a4b";

fn splitmix64(mut x: u64) -> u64 {
    x = x.wrapping_add(0x9e37_79b9_7f4a_7c15);
    x = (x ^ (x >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
    x = (x ^ (x >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
    x ^ (x >> 31)
}

fn hash2(i: u64, j: u64) -> u64 {
    splitmix64(splitmix64(i) ^ j.wrapping_mul(0xd6e8_feb8_6659_fd93))
}

/// Task 3's generator rule (bench/search/corpus/gen_pages.sql) with a hash of our own in place of
/// DuckDB's `hash()`: doc i has 20 + h(i, len) % 180 words, word j is w<floor(u^4 * 50000)> with
/// u = (h(i, j) % 2^32) / 2^32. Same distribution, not the same texts.
pub fn page_text(i: u64) -> String {
    let len = 20 + hash2(i, u64::MAX) % 180;
    let words: Vec<String> = (0..len)
        .map(|j| {
            let u = (hash2(i, j) % 4_294_967_296) as f64 / 4_294_967_296.0;
            format!("w{}", (u.powi(4) * 50_000.0).floor() as u64)
        })
        .collect();
    words.join(" ")
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

pub fn source_for(path: &str) -> Source {
    Source::new(INDEX_ID, path, 1_000_000 + path.len() as u64, 4_000 + path.len() as u64)
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
        docs.iter().enumerate().map(|(p, d)| Doc { position: p as u64, rowid: d.rowid, snapshot: -1, text: d.text.clone() }),
    )
    .unwrap();
    bundle::open(&bytes, &src).unwrap()
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
                let (k1, b) = (k1 as f32, b as f32);
                let avg = total as f32 / n as f32;
                let x = ((n - df) as f32 + 0.5) / (*df as f32 + 0.5);
                let idf = (1.0f32 + x).ln();
                let weight = idf * (1.0 + k1);
                let norm = k1 * (1.0 - b + b * dl as f32 / avg);
                let tf = tf as f32;
                score32 += weight * (tf / (tf + norm));
            } else {
                let avg = total as f64 / n as f64;
                let idf = (1.0 + (n as f64 - *df as f64 + 0.5) / (*df as f64 + 0.5)).ln();
                let tf = tf as f64;
                score64 += idf * (tf * (k1 + 1.0)) / (tf + k1 * (1.0 - b + b * dl as f64 / avg));
            }
        }
        if matched {
            out.push((d.rowid, if f32_order { score32 as f64 } else { score64 }));
        }
    }
    out.sort_by(|x, y| y.1.total_cmp(&x.1).then(x.0.cmp(&y.0)));
    out
}

pub fn rel(got: f32, want: f64) -> f64 {
    ((got as f64 - want) / want).abs()
}
