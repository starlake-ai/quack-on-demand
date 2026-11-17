//! Task 7 fix round 1 (review C1, C2, I1, I3): the query limits, the exact collector against
//! block-max bounds chosen under another average length, crafted bundles with valid checksums, and
//! cancellation and the memory budget of a search.
mod common;
use common::*;
use qod_search_core::bundle::{self, Doc, Source};
use qod_search_core::controls::Controls;
use qod_search_core::search::{self, check_query, top_k, Hit, Population};
#[cfg(feature = "measurement")]
use qod_search_core::search::top_k_block_wand_measurement_only;
use qod_search_core::Segment;
use std::sync::atomic::AtomicU8;

fn code(r: qod_search_core::Result<()>) -> &'static str {
    r.err().map(|e| e.code()).unwrap_or("ok")
}

fn nested(d: usize) -> String {
    format!("{}w1{}", "(".repeat(d), ")".repeat(d))
}

#[test]
fn query_length_limit() {
    let at = "a".repeat(4096);
    check_query(&at, BODY).unwrap();
    assert_eq!(code(check_query(&"a".repeat(4097), BODY)), "search_query_too_large");
}

#[test]
fn query_depth_limit_at_and_past() {
    check_query(&nested(32), BODY).unwrap();
    assert_eq!(code(check_query(&nested(33), BODY)), "search_query_too_large");
    // 1000 deep is about 2 KB: it overflowed a 512 KiB stack before the limit (review C1); now
    // refused before any parse.
    let e = check_query(&nested(1000), BODY).unwrap_err();
    assert_eq!(e.code(), "search_query_too_large");
    assert!(e.message().contains("deeper than 32"), "{e}");
    assert_eq!(code(check_query(&nested(100_000), BODY)), "search_query_too_large");
    // Unbalanced and quoted parentheses count too (over-refusal is accepted).
    assert_eq!(code(check_query(&format!("\"{}\" w1", "(".repeat(33)), BODY)), "search_query_too_large");
    // The deepest accepted query parses on a 512 KiB thread (the macOS default for secondary
    // threads), as search does.
    let ok = std::thread::Builder::new()
        .stack_size(512 * 1024)
        .spawn(|| check_query(&nested(32), BODY).is_ok() && code(check_query(&nested(1000), BODY)) != "ok")
        .unwrap()
        .join()
        .unwrap();
    assert!(ok);
}

#[test]
fn query_term_limit_at_and_past() {
    let terms = |n: usize| (1..=n).map(|i| format!("w{i}")).collect::<Vec<_>>().join(" ");
    check_query(&terms(64), BODY).unwrap();
    assert_eq!(code(check_query(&terms(65), BODY)), "search_query_too_large");
    // `NOT` chains recurse in the grammar; each NOT is a term.
    assert_eq!(code(check_query(&format!("{}w1", "NOT ".repeat(64)), BODY)), "search_query_too_large");
    assert_eq!(code(check_query(&"* ".repeat(65), BODY)), "search_query_too_large");
}

#[test]
fn boolean_shapes_are_accepted_except_a_boosted_reordered_group() {
    for q in ["w1 AND w2 AND w3", "+w1 +w2 +w3 w4 -w5", "w9 (+w1 +w2 +w3)", "(w1 AND w2)^2", "(w1 w2 w3)^2", "w1^2 AND w2 AND w3"] {
        check_query(q, BODY).unwrap_or_else(|e| panic!("{q}: {e}"));
    }
    for q in ["(w1 AND w2 AND w3)^2", "(+w1 w2 w3)^1.5", "(* w1)^2"] {
        assert_eq!(code(check_query(q, BODY)), "search_query_unsupported", "{q}");
    }
    // A field existence query needs a fast field, which no text field is: refused by the check,
    // which names a column outside the call first.
    assert_eq!(code(check_query("body:*", BODY)), "search_query_unsupported");
    assert_eq!(code(check_query("w1 OR body:*", BODY)), "search_query_unsupported");
    assert_eq!(code(check_query("title:*", BODY)), "search_field_not_allowed");
}

/// The 3-segment corpus with 500 hidden docs inserted into segment 1 (rowids 1,000,000 and up, never
/// allowed), as in bm25_reference's hidden-rows test.
fn with_hidden(docs: &[TDoc]) -> Vec<TDoc> {
    let mut out = Vec::new();
    let mut hidden = 0u64;
    for (p, d) in docs.iter().enumerate() {
        if (p as u64).wrapping_mul(0x9e37_79b9_7f4a_7c15) % 14 == 0 && hidden < 500 {
            out.push(TDoc { rowid: 1_000_000 + hidden as i64, text: random_text(hidden) });
            hidden += 1;
        }
        out.push(d.clone());
    }
    while hidden < 500 {
        out.push(TDoc { rowid: 1_000_000 + hidden as i64, text: random_text(hidden) });
        hidden += 1;
    }
    out
}

const BOOLEAN_QUERIES: [&str; 4] =
    ["w0 AND w1 AND w2", "w0 AND w1 AND w2 AND w3 AND w4", "+w0 +w1 +w2 w3 -w4", "w5 OR (w0 AND w1 AND w2)"];

fn allowed_top(segs: &[&Segment], docs: &[&Vec<TDoc>], allowed: &dyn Fn(i64) -> bool, pop: Population, q: &str, k: usize) -> Vec<(i64, u32)> {
    let live: Vec<Vec<u64>> = docs.iter().map(|d| bitset(d.len(), |_| true)).collect();
    let alw: Vec<Vec<u64>> = docs.iter().map(|d| bitset(d.len(), |p| allowed(d[p].rowid))).collect();
    let lr: Vec<&[u64]> = live.iter().map(|w| w.as_slice()).collect();
    let ar: Vec<&[u64]> = alw.iter().map(|w| w.as_slice()).collect();
    top_k(segs, &lr, &ar, pop, q, BODY, 1.2, 0.75, k).unwrap().iter().map(|h| (h.rowid, h.score.to_bits())).collect()
}

#[test]
fn and_of_3_and_5_terms_ignore_hidden_rows_and_match_a_fresh_index() {
    let docs = corpus();
    let segs: Vec<Segment> = docs.iter().enumerate().map(|(i, d)| build(&format!("/lake/b/{i}.parquet"), d)).collect();
    let hidden1 = with_hidden(&docs[1]);
    let rebuilt = build("/lake/b/1.parquet", &hidden1);
    let allowed = |r: i64| r < 1_000_000 && r % 10 == 0;
    // A fresh index of exactly the allowed docs (every doc alive, its table population).
    let fresh_docs: Vec<TDoc> = docs.iter().flatten().filter(|d| allowed(d.rowid)).cloned().collect();
    let fresh = build("/lake/b/fresh.parquet", &fresh_docs);
    for q in BOOLEAN_QUERIES {
        let before = allowed_top(&[&segs[0], &segs[1], &segs[2]], &[&docs[0], &docs[1], &docs[2]], &allowed, Population::Allowed, q, 50);
        let after =
            allowed_top(&[&segs[0], &rebuilt, &segs[2]], &[&docs[0], &hidden1, &docs[2]], &allowed, Population::Allowed, q, 50);
        assert!(before.len() >= 10, "`{q}`: {} hits", before.len());
        assert_eq!(before, after, "`{q}`: 500 hidden docs changed the answer");
        let f = allowed_top(&[&fresh], &[&fresh_docs], &|_| true, Population::Table, q, 50);
        assert_eq!(before, f, "`{q}`: differs from a fresh index of the allowed docs");
    }
}

/// The f32-order reference for a boolean of terms: `clauses` as (occur, term) with occur '+', ' ' or
/// '-'. A doc matches when it holds every '+' term, no '-' term and, with no '+', one ' ' term; its
/// score is the matching '+' and ' ' terms' scores summed in clause order.
fn bool_reference(population: &[&RefDoc], clauses: &[(char, &str)]) -> Vec<(i64, u32)> {
    let n = population.len() as u64;
    let total: u64 = population.iter().map(|d| d.tokens.len() as u64).sum();
    let avg = total as f32 / n as f32;
    let df = |t: &str| population.iter().filter(|d| d.tokens.iter().any(|x| x == t)).count() as u64;
    let dfs: Vec<u64> = clauses.iter().map(|(_, t)| df(t)).collect();
    let any_required = clauses.iter().any(|(o, _)| *o == '+');
    let mut out = Vec::new();
    for d in population {
        let tf = |t: &str| d.tokens.iter().filter(|x| *x == t).count() as u32;
        let ok = clauses.iter().all(|(o, t)| match o {
            '+' => tf(t) > 0,
            '-' => tf(t) == 0,
            _ => true,
        }) && (any_required || clauses.iter().any(|(o, t)| *o == ' ' && tf(t) > 0));
        if !ok {
            continue;
        }
        let dl = quantized(d.tokens.len() as u32);
        let mut sum: Option<f32> = None;
        for ((o, t), df) in clauses.iter().zip(&dfs) {
            let f = tf(t);
            if *o == '-' || f == 0 {
                continue;
            }
            let x = ((n - df) as f32 + 0.5) / (*df as f32 + 0.5);
            let weight = (1.0f32 + x).ln() * (1.0 + 1.2);
            let norm = 1.2 * (1.0 - 0.75 + 0.75 * dl as f32 / avg);
            let v = weight * (f as f32 / (f as f32 + norm));
            sum = Some(sum.map(|s| s + v).unwrap_or(v));
        }
        out.push((d.rowid, sum.unwrap()));
    }
    out.sort_by(|x, y| y.1.total_cmp(&x.1).then(x.0.cmp(&y.0)));
    out.into_iter().map(|(r, s)| (r, s.to_bits())).collect()
}

#[test]
fn mixed_required_optional_excluded_query_matches_the_reference() {
    let docs = corpus();
    let segs: Vec<Segment> = docs.iter().enumerate().map(|(i, d)| build(&format!("/lake/m/{i}.parquet"), d)).collect();
    let refs: Vec<Vec<RefDoc>> = docs.iter().map(|d| ref_docs(d)).collect();
    let allowed = |r: i64| r % 3 == 0;
    let refs_s: Vec<&Segment> = segs.iter().collect();
    let docs_s: Vec<&Vec<TDoc>> = docs.iter().collect();
    for (q, clauses) in [
        ("+w0 +w1 +w2 w3 -w4", vec![('+', "w0"), ('+', "w1"), ('+', "w2"), (' ', "w3"), ('-', "w4")]),
        ("w3 +w0 w5 +w1 +w2", vec![(' ', "w3"), ('+', "w0"), (' ', "w5"), ('+', "w1"), ('+', "w2")]),
        ("w0 AND w1 AND w2 AND w3 AND w4", vec![('+', "w0"), ('+', "w1"), ('+', "w2"), ('+', "w3"), ('+', "w4")]),
    ] {
        for pop in [Population::Table, Population::Allowed] {
            let got = allowed_top(&refs_s, &docs_s, &allowed, pop, q, 10);
            let population: Vec<&RefDoc> =
                refs.iter().flatten().filter(|d| pop == Population::Table || allowed(d.rowid)).collect();
            let want: Vec<(i64, u32)> =
                bool_reference(&population, &clauses).into_iter().filter(|(r, _)| allowed(*r)).take(10).collect();
            assert_eq!(got, want, "{pop:?} `{q}`");
        }
    }
    // Diagnostic: how often Tantivy's own sum (cost-ordered intersection) differs from the clause
    // order sum on these docs.
    #[cfg(feature = "measurement")]
    {
    let live: Vec<Vec<u64>> = docs.iter().map(|d| bitset(d.len(), |_| true)).collect();
    let lr: Vec<&[u64]> = live.iter().map(|w| w.as_slice()).collect();
    let exact = top_k(&refs_s, &lr, &[], Population::Table, "w2 AND w0 AND w1", BODY, 1.2, 0.75, 200).unwrap();
    let native = top_k_block_wand_measurement_only(&refs_s, &lr, &[], Population::Table, "w2 AND w0 AND w1", BODY, 1.2, 0.75, 200, &Controls::unlimited()).unwrap();
    let by: std::collections::HashMap<i64, u32> = native.iter().map(|h| (h.rowid, h.score.to_bits())).collect();
    let differ = exact.iter().filter(|h| by.get(&h.rowid).map(|b| *b != h.score.to_bits()).unwrap_or(false)).count();
    println!("`w2 AND w0 AND w1` top 200: {differ} docs whose Tantivy sum differs from the clause-order sum");
    }
}

/// The review's reproduction (C2): block 1 of `x`'s postings holds 40 allowed docs (tf 2, dl 21 to
/// 40) and 88 hidden `x`; block 2 the target (tf 2, dl 20, allowed) and 127 hidden `x`; then 370
/// allowed 200-token fillers and 10,000 hidden one-token docs. Under the allowed population's avgdl
/// the target is the top 1, but block 2's stored block-max pair was chosen under the segment's.
fn blockmax_case() -> (Segment, Vec<u64>, Vec<u64>, Vec<(String, bool)>, usize) {
    let mut texts: Vec<(String, bool)> = Vec::new();
    let filler = |n: usize, w: &str| vec![w; n].join(" ");
    for i in 0..40 {
        let dl = 21 + (i % 20);
        texts.push((format!("x x {}", filler(dl - 2, "f")), true));
    }
    for _ in 0..88 {
        texts.push(("x".into(), false));
    }
    texts.push((format!("x x {}", filler(18, "f")), true));
    let target = texts.len() - 1;
    for _ in 0..127 {
        texts.push(("x".into(), false));
    }
    for _ in 0..370 {
        texts.push((filler(200, "z"), true));
    }
    for _ in 0..10_000 {
        texts.push(("y".into(), false));
    }
    let src = Source::new(INDEX_ID, "/data/f.parquet", 1, 1, TABLE_ID, BODY, test_mac_key(INDEX_ID));
    let bytes = bundle::build(
        &src,
        texts.iter().enumerate().map(|(p, (t, _))| Doc::one(p as u64, p as i64, -1, t.clone())),
    )
    .unwrap();
    let seg = bundle::open(&bytes, &src).unwrap();
    let n = texts.len();
    let live = bitset(n, |_| true);
    let allowed = bitset(n, |d| texts[d].1);
    (seg, live, allowed, texts, target)
}

#[test]
fn block_max_bounds_under_another_avgdl_do_not_hide_the_top_doc() {
    let (seg, live, allowed, texts, target) = blockmax_case();
    for k in [1usize, 5] {
        let got = top_k(&[&seg], &[&live], &[&allowed], Population::Allowed, "x", BODY, 1.2, 0.75, k).unwrap();
        assert_eq!(got[0].rowid, target as i64, "k = {k}");
        assert_eq!(got.len(), k);
    }
    // Against the reference over the allowed docs: the whole top 10, rank for rank, equal scores.
    let docs: Vec<TDoc> = texts.iter().enumerate().map(|(p, (t, _))| TDoc { rowid: p as i64, text: t.clone() }).collect();
    let refs = ref_docs(&docs);
    let population: Vec<&RefDoc> = refs.iter().filter(|d| texts[d.rowid as usize].1).collect();
    let want = reference(&population, &|_| true, "x", 1.2, 0.75, true);
    let got = top_k(&[&seg], &[&live], &[&allowed], Population::Allowed, "x", BODY, 1.2, 0.75, 10).unwrap();
    assert_eq!(got.iter().map(|h| h.rowid).collect::<Vec<_>>(), want.iter().take(10).map(|w| w.0).collect::<Vec<_>>());
    assert!(got.iter().zip(&want).all(|(g, w)| g.score as f64 == w.1));
    // The measurement-only block-WAND path misses it: that is why it is not used for results.
    #[cfg(feature = "measurement")]
    {
    let wand = top_k_block_wand_measurement_only(
        &[&seg], &[&live], &[&allowed], Population::Allowed, "x", BODY, 1.2, 0.75, 1, &Controls::unlimited(),
    )
    .unwrap();
    println!("block-WAND top 1: {:?}; exact: {:?}", wand.first().map(|h| (h.rowid, h.score)), got.first().map(|h| (h.rowid, h.score)));
    assert_ne!(wand[0].rowid, target as i64, "Tantivy's block-WAND no longer misses the target: re-check review C2");
    }
}

fn check_against_reference(segs: &[&Segment], docs: &[&Vec<TDoc>], allowed: &dyn Fn(i64) -> bool, population: Population, query: &str) {
    let live: Vec<Vec<u64>> = docs.iter().map(|d| bitset(d.len(), |_| true)).collect();
    let alw: Vec<Vec<u64>> = docs.iter().map(|d| bitset(d.len(), |p| allowed(d[p].rowid))).collect();
    let live_r: Vec<&[u64]> = live.iter().map(|w| w.as_slice()).collect();
    let alw_r: Vec<&[u64]> = alw.iter().map(|w| w.as_slice()).collect();
    let refs: Vec<Vec<RefDoc>> = docs.iter().map(|d| ref_docs(d)).collect();
    let pop: Vec<&RefDoc> = refs
        .iter()
        .flatten()
        .filter(|d| population == Population::Table || allowed(d.rowid))
        .collect();
    let scored = |d: &RefDoc| allowed(d.rowid);
    let want = reference(&pop, &scored, query, 1.2, 0.75, true);
    let got = top_k(segs, &live_r, &alw_r, population, query, BODY, 1.2, 0.75, 10).unwrap();
    let w: Vec<(i64, f64)> = want.into_iter().take(10).collect();
    assert_eq!(got.iter().map(|h| h.rowid).collect::<Vec<_>>(), w.iter().map(|x| x.0).collect::<Vec<_>>(), "{population:?} `{query}`");
    for (g, x) in got.iter().zip(&w) {
        assert_eq!(g.score as f64, x.1, "{population:?} `{query}` rowid {}", g.rowid);
    }
}

#[test]
fn populations_whose_avgdl_is_far_from_the_segments() {
    // One segment: mostly one-token docs plus long docs; only the long docs allowed, then only short.
    let mut d: Vec<TDoc> = Vec::new();
    for i in 0..6_000i64 {
        let text = if i % 50 == 0 { page_text(i as u64) } else { format!("w{}", i % 7) };
        d.push(TDoc { rowid: i, text });
    }
    let s = build("/lake/f/mixed.parquet", &d);
    for q in ["w1", "w0 w3", "w5 w6"] {
        check_against_reference(&[&s], &[&d], &|r| r % 50 == 0, Population::Allowed, q);
        check_against_reference(&[&s], &[&d], &|r| r % 50 != 0, Population::Allowed, q);
    }
    // Two segments of very different average lengths, table population.
    let short: Vec<TDoc> = (0..5_000i64).map(|i| TDoc { rowid: i, text: format!("w{} w{}", i % 3, i % 11) }).collect();
    let long: Vec<TDoc> =
        (0..500i64).map(|i| TDoc { rowid: 10_000 + i, text: format!("{} {}", page_text(i as u64), page_text(i as u64 + 7)) }).collect();
    let a = build("/lake/f/short.parquet", &short);
    let b = build("/lake/f/long.parquet", &long);
    for q in ["w1", "w0 w2", "w3 w7 w10"] {
        check_against_reference(&[&a, &b], &[&short, &long], &|_| true, Population::Table, q);
        check_against_reference(&[&b, &a], &[&long, &short], &|_| true, Population::Table, q);
    }
}

// ---- crafted bundles with valid checksums and a valid MAC (review I1) ----
//
// Since format 3 only a writer holding the index's MAC key (the manager, the indexer) can make the
// parser see such bytes; these tests reseal the MAC to keep the parser's own hardening pinned.

fn le_u64(b: &[u8], at: usize) -> u64 {
    u64::from_le_bytes(b[at..at + 8].try_into().unwrap())
}

/// The (offset, len) of region `name` and the byte offset of its CRC in the footer, in a bundle
/// with its MAC.
fn region(b: &[u8], name: &str) -> (usize, usize, usize) {
    let t = b.len() - bundle::MAC_LEN - 28;
    let fo = le_u64(b, t) as usize;
    let f = &b[fo..t];
    let mut at = 8 + 4;
    for _ in 0..2 {
        let n = u16::from_le_bytes([f[at], f[at + 1]]) as usize;
        at += 2 + n;
    }
    let n = u32::from_le_bytes(f[at..at + 4].try_into().unwrap()) as usize;
    at += 4 + n + 8 + 8 + 8;
    let nf = u32::from_le_bytes(f[at..at + 4].try_into().unwrap()) as usize;
    at += 4 + 8 * nf + 4 + 1;
    let nr = u32::from_le_bytes(f[at..at + 4].try_into().unwrap()) as usize;
    at += 4;
    for _ in 0..nr {
        at += 1;
        let ln = u16::from_le_bytes([f[at], f[at + 1]]) as usize;
        let nm = std::str::from_utf8(&f[at + 2..at + 2 + ln]).unwrap().to_string();
        at += 2 + ln;
        let off = le_u64(f, at) as usize;
        let len = le_u64(f, at + 8) as usize;
        let crc_at = fo + at + 16;
        at += 20;
        if nm == name {
            return (off, len, crc_at);
        }
    }
    panic!("no region {name}");
}

/// Rewrites `idx` so that the postings of `x` (the only term: docs "x", "x", vint deltas 0x80 0x81,
/// then term frequencies 0x81 0x81) read `second` as the second doc's delta, then reseals the
/// region, the footer and the MAC: a bundle a writer holding the index's MAC key could produce.
fn crafted_postings(second: u8) -> (Vec<u8>, Source) {
    let src = Source::new(INDEX_ID, "/data/crafted.parquet", 1, 1, TABLE_ID, BODY, test_mac_key(INDEX_ID));
    let mut b = bundle::build(
        &src,
        ["x", "x"].iter().enumerate().map(|(p, t)| Doc::one(p as u64, p as i64, -1, t.to_string())),
    )
    .unwrap();
    let (off, len, crc_at) = region(&b, "idx");
    assert_eq!(&b[off + 8..off + 12], &[0x80, 0x81, 0x81, 0x81], "the postings layout moved: re-derive the crafted bytes");
    b[off + 9] = second;
    let crc = crc32fast::hash(&b[off..off + len]);
    b[crc_at..crc_at + 4].copy_from_slice(&crc.to_le_bytes());
    let t = b.len() - bundle::MAC_LEN - 28;
    let fo = le_u64(&b, t) as usize;
    let fcrc = crc32fast::hash(&b[fo..t]);
    b[t + 8..t + 12].copy_from_slice(&fcrc.to_le_bytes());
    assert_eq!(bundle::open(&b, &src).err().map(|e| e.code()), Some("bundle_mac_mismatch"));
    reseal(&mut b, &src);
    (b, src)
}

#[test]
fn crafted_repeated_doc_ids_return_an_error() {
    let (b, src) = crafted_postings(0x80); // docs 0, 0
    let seg = bundle::open(&b, &src).expect("the checksums and the open checks pass");
    let live = bitset(2, |_| true);
    let only0 = bitset(2, |d| d == 0);
    // Allowed population of doc 0 alone: N 1, while the postings count doc 0 twice (df clamped).
    for (pop, allowed) in [(Population::Allowed, &only0), (Population::Table, &live)] {
        let e = top_k(&[&seg], &[&live], &[allowed], pop, "x", BODY, 1.2, 0.75, 10).unwrap_err();
        assert_eq!(e.code(), "bundle_corrupt", "{pop:?}: {e}");
    }
}

#[test]
fn crafted_doc_past_the_segment_returns_an_error() {
    let (b, src) = crafted_postings(0x85); // docs 0, 5 in a 2-doc segment
    let seg = bundle::open(&b, &src).expect("the checksums and the open checks pass");
    let live = bitset(2, |_| true);
    let e = top_k(&[&seg], &[&live], &[], Population::Table, "x", BODY, 1.2, 0.75, 10).unwrap_err();
    assert_eq!(e.code(), "bundle_corrupt", "{e}");
    // Through the C ABI, the measurement path reads the field norm of doc 5 inside Tantivy and
    // panics; the panic comes back as an error instead of ending the process.
    #[cfg(feature = "measurement")]
    {
        let cs = Source { record_count: 2, row_id_start: 0, has_internal_row_id: -1, has_internal_snapshot: 0, ..src.clone() };
        let qs = c_open(&b, &cs);
        let segs = [qs as *const _];
        let a = c_args(&segs, &[live.clone()], &[], 0, "x", BODY, 10, std::ptr::null(), u64::MAX);
        let mut out = vec![empty_hit(); 10];
        let mut n_out = 0usize;
        let mut err = std::ptr::null_mut();
        let rc = unsafe {
            qod_search_core::qs_search_block_wand_measurement_only(&a.args, out.as_mut_ptr(), out.len(), &mut n_out, &mut err)
        };
        let msg = unsafe { take_err(err) };
        unsafe { qod_search_core::qs_segment_free(qs) };
        println!("measurement path on the crafted bundle: rc {rc}, {msg}, {n_out} hits");
        assert_ne!(rc, 0);
        assert!(msg.starts_with("search_internal: panic"), "{msg}");
    }
}

// ---- cancellation and the budget of a search (review I3) ----

#[test]
fn search_cancel_and_budget() {
    let docs: Vec<TDoc> = (0..3_000).map(|i| TDoc { rowid: i, text: page_text(i as u64) }).collect();
    let s = build("/lake/c/a.parquet", &docs);
    let live = bitset(docs.len(), |_| true);
    let run = |c: &Controls| -> qod_search_core::Result<Vec<Hit>> {
        search::top_k_with(&[&s], &[&live], &[], Population::Table, "w0 w1", BODY, 1.2, 0.75, 10, c)
    };
    run(&Controls::unlimited()).unwrap();
    let flag = AtomicU8::new(1);
    assert_eq!(run(&Controls::new(Some(&flag), u64::MAX)).unwrap_err().code(), "search_cancelled");
    let off = AtomicU8::new(0);
    run(&Controls::new(Some(&off), u64::MAX)).unwrap();
    assert_eq!(run(&Controls::new(None, 100)).unwrap_err().code(), "search_memory_exceeded");
    // The budget the call needs: three bitsets of 47 words, df entries, the heap.
    let c = Controls::new(None, u64::MAX);
    run(&c).unwrap();
    println!("accounted bytes for a 3,000-doc search: {}", c.used());
    run(&Controls::new(None, c.used())).unwrap();
    assert_eq!(run(&Controls::new(None, c.used() - 1)).unwrap_err().code(), "search_memory_exceeded");
}

#[test]
fn search_cancelled_while_it_runs() {
    // A flag set by another thread while a large search scores: the call ends with search_cancelled,
    // never with a partial answer.
    let docs: Vec<TDoc> = (0..200_000).map(|i| TDoc { rowid: i, text: format!("w{} w{}", i % 7, i % 13) }).collect();
    let s = build("/lake/c/big.parquet", &docs);
    let live = bitset(docs.len(), |_| true);
    let flag = AtomicU8::new(0);
    let r = std::thread::scope(|scope| {
        scope.spawn(|| {
            std::thread::sleep(std::time::Duration::from_millis(2));
            flag.store(1, std::sync::atomic::Ordering::Relaxed);
        });
        let mut last = Ok(Vec::new());
        // Repeat until a call sees the flag (the first may finish before it is set).
        for _ in 0..1_000 {
            last = search::top_k_with(
                &[&s], &[&live], &[], Population::Table, "w1 w2", BODY, 1.2, 0.75, 10, &Controls::new(Some(&flag), u64::MAX),
            );
            if last.is_err() {
                break;
            }
        }
        last
    });
    assert_eq!(r.unwrap_err().code(), "search_cancelled");
}

#[test]
fn one_call_stops_mid_scan_when_cancelled() {
    // One segment of 1,000,000 docs: a flag set by another thread during a single call ends that
    // call with search_cancelled, sooner than a full run (the re-review's single-call form).
    let n = 1_000_000usize;
    let src = Source::new(INDEX_ID, "/lake/c/one.parquet", 1, 1, TABLE_ID, BODY, test_mac_key(INDEX_ID));
    let bytes = bundle::build(
        &src,
        (0..n).map(|p| Doc::one(p as u64, p as i64, -1, format!("w{} w{}", p % 7, p % 13))),
    )
    .unwrap();
    let seg = bundle::open(&bytes, &src).unwrap();
    let live = bitset(n, |_| true);
    for q in ["w1 w2 w3", "w1 AND w2 AND w3"] {
        let run = |c: &Controls| search::top_k_with(&[&seg], &[&live], &[], Population::Table, q, BODY, 1.2, 0.75, 10, c);
        let t = std::time::Instant::now();
        run(&Controls::unlimited()).unwrap();
        let full = t.elapsed();
        let mut cancelled = None;
        for fraction in [0.3f64, 0.2, 0.1, 0.05] {
            let flag = AtomicU8::new(0);
            let delay = full.mul_f64(fraction);
            let (r, took) = std::thread::scope(|scope| {
                scope.spawn(|| {
                    std::thread::sleep(delay);
                    flag.store(1, std::sync::atomic::Ordering::Relaxed);
                });
                let t = std::time::Instant::now();
                let r = run(&Controls::new(Some(&flag), u64::MAX));
                (r, t.elapsed())
            });
            if let Err(e) = r {
                assert_eq!(e.code(), "search_cancelled");
                cancelled = Some((fraction, took));
                break;
            }
        }
        let (fraction, took) = cancelled.expect("no single call saw the flag");
        println!("`{q}`: full run {full:?}; flag at {:.0}% of it ended the call after {took:?}", fraction * 100.0);
        assert!(took < full, "`{q}`: the cancelled call ran as long as a full one");
    }
}
