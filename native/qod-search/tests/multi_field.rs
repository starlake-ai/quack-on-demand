//! Multi-field indexes (bundle format 2): the default fields, field prefixes and the column to field
//! id remap (rule 6 before any read), a rename that keeps every bundle, the field set and table id a
//! bundle pins, the v1 refusal, and the clause order of the sums (invariant 7).
mod common;
use common::*;
use qod_search_core::bundle::{self, Source};
use qod_search_core::search::{self, Hit, Population};
use qod_search_core::Segment;
use std::collections::HashMap;

const TITLE_BODY: &[(&str, i64)] = &[("title", 1), ("body", 2)];
const K1: f64 = 1.2;
const B: f64 = 0.75;

struct Fixture {
    docs: Vec<Vec<MDoc>>,
    segs: Vec<Segment>,
    live: Vec<Vec<u64>>,
}

fn dead(rowid: i64) -> bool {
    rowid % 17 == 3
}

/// 2,000 docs over 3 segments, two fields, every 17th doc dead.
fn fixture() -> Fixture {
    let docs = multi_corpus(&[800, 700, 500]);
    let segs = docs.iter().enumerate().map(|(i, d)| build_multi(&format!("/lake/mf/{i}.parquet"), TABLE_ID, TITLE_BODY, d)).collect();
    let live = docs.iter().map(|d| bitset(d.len(), |p| !dead(d[p].rowid))).collect();
    Fixture { docs, segs, live }
}

fn run(f: &Fixture, query: &str, fields: &[(&str, i64)], k: usize) -> Vec<Hit> {
    let segs: Vec<&Segment> = f.segs.iter().collect();
    let live: Vec<&[u64]> = f.live.iter().map(|w| w.as_slice()).collect();
    search::top_k(&segs, &live, &[], Population::Table, query, fields, 1.2, 0.75, k).unwrap()
}

fn by_rowid(hits: &[Hit]) -> HashMap<i64, f32> {
    hits.iter().map(|h| (h.rowid, h.score)).collect()
}

#[test]
fn two_fields_default_query_sums_fields() {
    let f = fixture();
    let got = run(&f, "w1", TITLE_BODY, 10_000);
    // The reference: the live docs, per field N, the exact token total, df and the quantized dl.
    let population: Vec<&MDoc> = f.docs.iter().flatten().filter(|d| !dead(d.rowid)).collect();
    let n = population.len() as u64;
    let toks: Vec<Vec<Vec<String>>> = population
        .iter()
        .map(|d| d.texts.iter().map(|t| t.as_deref().map(tokens).unwrap_or_default()).collect())
        .collect();
    let total: Vec<u64> = (0..2).map(|fi| toks.iter().map(|t| t[fi].len() as u64).sum()).collect();
    let df: Vec<u64> = (0..2).map(|fi| toks.iter().filter(|t| t[fi].iter().any(|x| x == "w1")).count() as u64).collect();
    let mut want: Vec<(i64, f64, f64, f32)> = Vec::new(); // rowid, f64 score, bound, f32-order score
    for (d, t) in population.iter().zip(&toks) {
        let (mut s64, mut bound, mut s32, mut matched) = (0f64, 0f64, None::<f32>, false);
        for fi in 0..2 {
            let tf = t[fi].iter().filter(|x| *x == "w1").count() as u32;
            if tf == 0 {
                continue;
            }
            matched = true;
            let dl = quantized(t[fi].len() as u32);
            s64 += term_score64(n, total[fi], df[fi], tf, dl, K1, B);
            bound += 1e-6 * (1.0 + K1) * idf64(n, df[fi]).max(1.0);
            let v = term_score32(n, total[fi], df[fi], tf, dl, K1 as f32, B as f32);
            s32 = Some(s32.map(|s| s + v).unwrap_or(v));
        }
        if matched {
            want.push((d.rowid, s64, bound, s32.unwrap()));
        }
    }
    assert!(want.len() > 1_000, "{} matches", want.len());
    assert!(want.iter().any(|w| w.2 > 1.1 * 1e-6 * (1.0 + K1)), "no doc matched both fields");
    // Accuracy (invariant 7): every hit within the per-matched-term bound of the f64 reference.
    let by64: HashMap<i64, (f64, f64)> = want.iter().map(|w| (w.0, (w.1, w.2))).collect();
    assert_eq!(got.len(), want.len());
    let mut worst = 0f64;
    for h in &got {
        let (s, bound) = by64[&h.rowid];
        let gap = (h.score as f64 - s).abs();
        assert!(gap <= bound, "rowid {}: {} vs {s} (bound {bound:e})", h.rowid, h.score);
        worst = worst.max(gap / bound);
    }
    // Ranks: the reference's, ties by rowid.
    let mut ranked = want.clone();
    ranked.sort_by(|x, y| y.1.total_cmp(&x.1).then(x.0.cmp(&y.0)));
    assert_eq!(got.iter().map(|h| h.rowid).collect::<Vec<_>>(), ranked.iter().map(|w| w.0).collect::<Vec<_>>());
    // Determinism: bit-identical to the f32-order reference, title then body.
    for h in &got {
        let w = want.iter().find(|w| w.0 == h.rowid).unwrap();
        assert_eq!(h.score.to_bits(), w.3.to_bits(), "rowid {}", h.rowid);
    }
    println!("two fields, `w1`: {} hits, worst gap {worst:.3} of the bound", got.len());
}

#[test]
fn field_prefix_restricts() {
    let f = fixture();
    // A one-field index of the titles alone (field id 1), the same docs and liveness.
    let title_only: Vec<Segment> = f
        .docs
        .iter()
        .enumerate()
        .map(|(i, d)| {
            let one: Vec<MDoc> = d.iter().map(|m| MDoc { rowid: m.rowid, texts: vec![m.texts[0].clone()] }).collect();
            build_multi(&format!("/lake/mf/t{i}.parquet"), TABLE_ID, &[("title", 1)], &one)
        })
        .collect();
    let one = Fixture { docs: Vec::new(), segs: title_only, live: f.live.clone() };
    let got = run(&f, "title:w1", TITLE_BODY, 10_000);
    let want = run(&one, "w1", &[("title", 1)], 10_000);
    assert!(!want.is_empty());
    let g: Vec<(i64, u32)> = got.iter().map(|h| (h.rowid, h.score.to_bits())).collect();
    let w: Vec<(i64, u32)> = want.iter().map(|h| (h.rowid, h.score.to_bits())).collect();
    assert_eq!(g, w);
    // `body:w1` is another answer.
    let body = run(&f, "body:w1", TITLE_BODY, 10_000);
    assert_ne!(body.len(), got.len());
}

#[test]
fn field_outside_columns_refused() {
    // Rust level, and no union is built for a refused call.
    let f = fixture();
    let segs: Vec<&Segment> = f.segs.iter().collect();
    let live: Vec<&[u64]> = f.live.iter().map(|w| w.as_slice()).collect();
    #[cfg(feature = "test-hooks")]
    let before = qod_search_core::qs_test_union_builds();
    let e = search::top_k(&segs, &live, &[], Population::Table, "body:w1", &[("title", 1)], 1.2, 0.75, 10).unwrap_err();
    assert_eq!(e.message(), "search_field_not_allowed: body");
    #[cfg(feature = "test-hooks")]
    assert_eq!(qod_search_core::qs_test_union_builds(), before, "a refused call built a union");
    assert_eq!(search::check_query("body:w1", &[("title", 1)]).unwrap_err().message(), "search_field_not_allowed: body");
    // A field id is not a column name, and neither is the internal position field.
    assert_eq!(search::check_query("f2:w1", TITLE_BODY).unwrap_err().message(), "search_field_not_allowed: f2");
    assert_eq!(
        search::check_query("__qod_position:3", TITLE_BODY).unwrap_err().message(),
        "search_field_not_allowed: __qod_position"
    );
    // C ABI: a NULL segment pointer is never dereferenced when the query names another column.
    let segments = [std::ptr::null::<qod_search_core::QsSegment>()];
    let live = vec![vec![u64::MAX; 13]];
    let a = c_args(&segments, &live, &[], 0, "body:w1", &[("title", 1)], 10, std::ptr::null(), u64::MAX);
    assert_eq!(c_search(&a.args).unwrap_err(), "search_field_not_allowed: body");
    let mut err = std::ptr::null_mut();
    let m = unsafe { qod_search_core::qs_matches_open(&a.args, 1, &mut err) };
    assert!(m.is_null());
    assert_eq!(unsafe { take_err(err) }, "search_field_not_allowed: body");
    let cf = c_fields(&[("title", 1)]);
    let q = std::ffi::CString::new("body:w1").unwrap();
    let rc = unsafe { qod_search_core::qs_check_query(q.as_ptr(), cf.fields.as_ptr(), cf.fields.len(), &mut err) };
    assert_ne!(rc, 0);
    assert_eq!(unsafe { take_err(err) }, "search_field_not_allowed: body");
    // A query naming only allowed columns reaches the segment check.
    let a = c_args(&segments, &live, &[], 0, "title:w1", &[("title", 1)], 10, std::ptr::null(), u64::MAX);
    assert_eq!(c_search(&a.args).unwrap_err(), "search_invalid_argument: a NULL segment");
}

#[test]
fn rename_keeps_bundle() {
    let docs = multi_corpus(&[600, 400]);
    let built = |i: usize| source_with(&format!("/lake/mf/r{i}.parquet"), TABLE_ID, TITLE_BODY);
    let renamed_fields: &[(&str, i64)] = &[("headline", 1), ("body", 2)];
    let bytes: Vec<Vec<u8>> = docs.iter().enumerate().map(|(i, d)| build_multi_bytes(&built(i), d)).collect();
    let old: Vec<Segment> = bytes.iter().enumerate().map(|(i, b)| bundle::open(b, &built(i)).unwrap()).collect();
    let new: Vec<Segment> = bytes
        .iter()
        .enumerate()
        .map(|(i, b)| bundle::open(b, &source_with(&format!("/lake/mf/r{i}.parquet"), TABLE_ID, renamed_fields)).unwrap())
        .collect();
    let live: Vec<Vec<u64>> = docs.iter().map(|d| bitset(d.len(), |_| true)).collect();
    let fo = Fixture { docs: Vec::new(), segs: old, live: live.clone() };
    let fne = Fixture { docs: Vec::new(), segs: new, live };
    let before = run(&fo, "title:w1", TITLE_BODY, 10_000);
    let after = run(&fne, "headline:w1", renamed_fields, 10_000);
    assert!(!before.is_empty());
    let b: Vec<(i64, u32)> = before.iter().map(|h| (h.rowid, h.score.to_bits())).collect();
    let a: Vec<(i64, u32)> = after.iter().map(|h| (h.rowid, h.score.to_bits())).collect();
    assert_eq!(a, b);
    // The old name is gone after the rename.
    let segs: Vec<&Segment> = fne.segs.iter().collect();
    let lr: Vec<&[u64]> = fne.live.iter().map(|w| w.as_slice()).collect();
    let e = search::top_k(&segs, &lr, &[], Population::Table, "title:w1", renamed_fields, 1.2, 0.75, 10).unwrap_err();
    assert_eq!(e.message(), "search_field_not_allowed: title");
}

fn mismatch(bytes: &[u8], src: &Source) -> String {
    match bundle::open(bytes, src) {
        Ok(_) => panic!("opened"),
        Err(e) => e.message(),
    }
}

#[test]
fn field_set_mismatch_reads_missing() {
    let path = "/lake/mf/set.parquet";
    let docs = multi_corpus(&[200]).remove(0);
    let src = source_with(path, 7, TITLE_BODY);
    let bytes = build_multi_bytes(&src, &docs);
    bundle::open(&bytes, &src).unwrap();
    for fields in [&[("title", 1)][..], &[("title", 1), ("x", 3)][..], &[("body", 2), ("title", 1)][..]] {
        let msg = mismatch(&bytes, &source_with(path, 7, fields));
        assert!(msg.starts_with("bundle_mismatch: "), "{fields:?}: {msg}");
    }
    let msg = mismatch(&bytes, &source_with(path, 8, TITLE_BODY));
    assert!(msg.starts_with("bundle_mismatch: "), "{msg}");
    // The same through the C ABI.
    let cs = c_source(&source_with(path, 8, TITLE_BODY));
    let mut err = std::ptr::null_mut();
    let seg = unsafe { qod_search_core::qs_segment_open(bytes.as_ptr(), bytes.len(), &cs.source, &mut err) };
    assert!(seg.is_null());
    assert!(unsafe { take_err(err) }.starts_with("bundle_mismatch: "));
}

#[test]
fn v1_bundle_refused() {
    // Built once by the prototype's builder (search/phase0 at db43bfbc): three docs of
    // /lake/main/pages/v1.parquet, file size 1,000,027, footer size 4,027.
    let bytes = std::fs::read(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/data/bundle-v1.bin")).unwrap();
    let src = Source::new(INDEX_ID, "/lake/main/pages/v1.parquet", 1_000_027, 4_027, TABLE_ID, BODY, test_mac_key(INDEX_ID));
    // Format 1 carries no MAC: it reads as forged, before any byte of it is parsed.
    let msg = mismatch(&bytes, &src);
    assert!(msg.starts_with("bundle_mac_mismatch: "), "{msg}");
    // Under a valid MAC its footer reads as another format.
    let msg = mismatch(&seal(&bytes, &src), &src);
    assert!(msg.starts_with("bundle_mismatch: format version 1, expected 3"), "{msg}");
}

#[test]
fn clause_order_pinned() {
    let f = fixture();
    let got = run(&f, "w1 OR w2", TITLE_BODY, 10_000);
    // Each clause's own score, under the same statistics: one field and one term per search.
    let clause = |q: &str| by_rowid(&run(&f, q, TITLE_BODY, 10_000));
    let (t1, b1, t2, b2) = (clause("title:w1"), clause("body:w1"), clause("title:w2"), clause("body:w2"));
    let add = |acc: Option<f32>, v: Option<f32>| match (acc, v) {
        (a, None) => a,
        (None, Some(v)) => Some(v),
        (Some(a), Some(v)) => Some(a + v),
    };
    let mut differs = 0usize;
    for h in &got {
        let r = h.rowid;
        let parts = [t1.get(&r).copied(), b1.get(&r).copied(), t2.get(&r).copied(), b2.get(&r).copied()];
        // The parser's tree: one group per term holding its per-field clauses, in `fields` order.
        let g1 = add(parts[0], parts[1]);
        let g2 = add(parts[2], parts[3]);
        let grouped = add(g1, g2).unwrap();
        let flat = parts.iter().fold(None, |acc, v| add(acc, *v)).unwrap();
        assert_eq!(h.score.to_bits(), grouped.to_bits(), "rowid {r}: {} vs grouped {grouped}", h.score);
        differs += (flat.to_bits() != grouped.to_bits()) as usize;
    }
    println!("`w1 OR w2` over two fields: {} hits, {differs} whose flat fold differs from the grouped sum", got.len());
    assert!(differs > 0, "no doc of the corpus tells the two association orders apart: the pin is vacuous");
}

#[test]
fn column_prefix_unwritable() {
    let fields: &[(&str, i64)] = &[("my col", 1)];
    let docs = multi_corpus(&[300]).remove(0);
    let one: Vec<MDoc> = docs.iter().map(|m| MDoc { rowid: m.rowid, texts: vec![m.texts[1].clone()] }).collect();
    let seg = build_multi("/lake/mf/col.parquet", TABLE_ID, fields, &one);
    let f = Fixture { docs: Vec::new(), segs: vec![seg], live: vec![bitset(one.len(), |_| true)] };
    let plain = run(&f, "w1", fields, 10_000);
    assert!(!plain.is_empty());
    let segs: Vec<&Segment> = f.segs.iter().collect();
    let live: Vec<&[u64]> = f.live.iter().map(|w| w.as_slice()).collect();
    let e = search::top_k(&segs, &live, &[], Population::Table, "\"my col\":w1", fields, 1.2, 0.75, 10).unwrap_err();
    assert_eq!(e.code(), "search_query_invalid", "{e}");
    // A name starting with '-' cannot be written: the grammar reads `-x:w1` as excluding `x:w1`.
    assert_eq!(search::check_query("-x:w1 w2", &[("-x", 1)]).unwrap_err().message(), "search_field_not_allowed: x");
    // The grammar's own escape writes the space: the same answer as the default field.
    let escaped = run(&f, "my\\ col:w1", fields, 10_000);
    let a: Vec<(i64, u32)> = plain.iter().map(|h| (h.rowid, h.score.to_bits())).collect();
    let b: Vec<(i64, u32)> = escaped.iter().map(|h| (h.rowid, h.score.to_bits())).collect();
    assert_eq!(a, b);
}

#[test]
fn arguments_of_a_multi_field_call() {
    let f = fixture();
    let segs: Vec<&Segment> = f.segs.iter().collect();
    let live: Vec<&[u64]> = f.live.iter().map(|w| w.as_slice()).collect();
    let call = |fields: &[(&str, i64)], k1: f32, b: f32| {
        search::top_k(&segs, &live, &[], Population::Table, "w1", fields, k1, b, 10).map(|_| ()).map_err(|e| e.message())
    };
    call(TITLE_BODY, 1.2, 0.75).unwrap();
    call(&[("body", 2)], 1.2, 0.75).unwrap();
    // D10: k1 and b are fixed.
    for (k1, b) in [(1.5, 0.75), (1.2, 0.5), (0.0, 0.0)] {
        assert!(call(TITLE_BODY, k1, b).unwrap_err().starts_with("search_invalid_argument: "));
    }
    // A field id the index does not hold, duplicates, none, too many.
    for fields in [
        &[("title", 3)][..],
        &[("title", 1), ("title", 2)][..],
        &[("title", 1), ("body", 1)][..],
        &[][..],
    ] {
        let msg = call(fields, 1.2, 0.75).unwrap_err();
        assert!(msg.starts_with("search_invalid_argument: "), "{fields:?}: {msg}");
    }
    let many: Vec<(String, i64)> = (0..33).map(|i| (format!("c{i}"), i)).collect();
    let many_r: Vec<(&str, i64)> = many.iter().map(|(c, i)| (c.as_str(), *i)).collect();
    assert!(search::check_query("w1", &many_r).unwrap_err().message().starts_with("search_invalid_argument: "));
    // Segments of different field sets in one call.
    let other = build_multi("/lake/mf/other.parquet", TABLE_ID, &[("title", 1)], &[MDoc { rowid: 0, texts: vec![Some("w1".into())] }]);
    let one = bitset(1, |_| true);
    let e = search::top_k(&[&f.segs[0], &other], &[&f.live[0], &one], &[], Population::Table, "w1", &[("title", 1)], 1.2, 0.75, 10)
        .unwrap_err();
    assert_eq!(e.code(), "search_invalid_argument", "{e}");
    // C ABI: the path (Task 15 deferred) and k1.
    let ptrs: Vec<*const qod_search_core::QsSegment> = Vec::new();
    let mut a = c_args(&ptrs, &[], &[], 0, "w1", TITLE_BODY, 10, std::ptr::null(), u64::MAX);
    assert!(c_search(&a.args).unwrap().is_empty());
    a.args.path = 1;
    assert!(c_search(&a.args).unwrap_err().starts_with("search_invalid_argument: "));
    a.args.path = 0;
    a.args.k1 = 1.3;
    assert!(c_search(&a.args).unwrap_err().starts_with("search_invalid_argument: "));
}

fn bits(hits: &[Hit]) -> Vec<(i64, u32)> {
    hits.iter().map(|h| (h.rowid, h.score.to_bits())).collect()
}

#[test]
fn column_case_is_ascii_insensitive() {
    // Prefixes match their column ignoring ASCII case, as DuckDB compares identifiers.
    let f = fixture();
    let want = bits(&run(&f, "title:w1", TITLE_BODY, 10_000));
    assert!(!want.is_empty());
    for q in ["TITLE:w1", "Title:w1", "tItLe:w1"] {
        assert_eq!(bits(&run(&f, q, TITLE_BODY, 10_000)), want, "`{q}`");
    }
    // The call's own spelling does not matter either.
    assert_eq!(bits(&run(&f, "title:w1", &[("TITLE", 1), ("Body", 2)], 10_000)), want);
    // The refusal names the column as the query wrote it.
    assert_eq!(search::check_query("TiTlE:w1", &[("body", 2)]).unwrap_err().message(), "search_field_not_allowed: TiTlE");
    // Two columns equal under the fold are one column twice: refused as call fields...
    let twice: &[(&str, i64)] = &[("title", 1), ("Title", 2)];
    assert_eq!(search::check_query("w1", twice).unwrap_err().code(), "search_invalid_argument");
    let segs: Vec<&Segment> = f.segs.iter().collect();
    let live: Vec<&[u64]> = f.live.iter().map(|w| w.as_slice()).collect();
    let e = search::top_k(&segs, &live, &[], Population::Table, "w1", twice, 1.2, 0.75, 10).unwrap_err();
    assert_eq!(e.code(), "search_invalid_argument", "{e}");
    let cf = c_fields(twice);
    let q = std::ffi::CString::new("w1").unwrap();
    let mut err = std::ptr::null_mut();
    assert_ne!(unsafe { qod_search_core::qs_check_query(q.as_ptr(), cf.fields.as_ptr(), 2, &mut err) }, 0);
    assert!(unsafe { take_err(err) }.starts_with("search_invalid_argument: "));
    // ...and as index fields: the builder, the opener, and both through the C ABI.
    let bad = source_with("/lake/mf/case.parquet", TABLE_ID, twice);
    assert_eq!(bad.validate().unwrap_err().code(), "search_invalid_argument");
    assert_eq!(bundle::Builder::new(&bad).err().unwrap().code(), "search_invalid_argument");
    let good = source_with("/lake/mf/case.parquet", TABLE_ID, TITLE_BODY);
    let bytes = build_multi_bytes(&good, &multi_corpus(&[50]).remove(0));
    assert_eq!(bundle::open(&bytes, &bad).err().unwrap().code(), "search_invalid_argument");
    let cs = c_source(&bad);
    assert!(unsafe { qod_search_core::qs_builder_new(&cs.source, std::ptr::null(), u64::MAX, &mut err) }.is_null());
    assert!(unsafe { take_err(err) }.starts_with("search_invalid_argument: "));
    assert!(unsafe { qod_search_core::qs_segment_open(bytes.as_ptr(), bytes.len(), &cs.source, &mut err) }.is_null());
    assert!(unsafe { take_err(err) }.starts_with("search_invalid_argument: "));
}

#[test]
fn escaped_and_non_ascii_columns() {
    let docs = multi_corpus(&[400]);
    let live = vec![bitset(400, |_| true)];
    // An escaped column name matches whatever its case.
    let spaced: &[(&str, i64)] = &[("My Col", 1), ("body", 2)];
    let s = Fixture { docs: Vec::new(), segs: vec![build_multi("/lake/mf/sp.parquet", TABLE_ID, spaced, &docs[0])], live: live.clone() };
    let want = bits(&run(&s, "My\\ Col:w1", spaced, 10_000));
    assert!(!want.is_empty());
    for q in ["my\\ col:w1", "MY\\ COL:w1"] {
        assert_eq!(bits(&run(&s, q, spaced, 10_000)), want, "`{q}`");
    }
    // A non-ASCII pair stays two columns: the fold is ASCII only, as DuckDB's.
    let accents: &[(&str, i64)] = &[("\u{c9}", 1), ("\u{e9}", 2)];
    let a = Fixture { docs: Vec::new(), segs: vec![build_multi("/lake/mf/acc.parquet", TABLE_ID, accents, &docs[0])], live: live.clone() };
    let plain = Fixture { docs: Vec::new(), segs: vec![build_multi("/lake/mf/pl.parquet", TABLE_ID, TITLE_BODY, &docs[0])], live };
    let upper = bits(&run(&a, "\u{c9}:w1", accents, 10_000));
    let lower = bits(&run(&a, "\u{e9}:w1", accents, 10_000));
    assert_eq!(upper, bits(&run(&plain, "title:w1", TITLE_BODY, 10_000)));
    assert_eq!(lower, bits(&run(&plain, "body:w1", TITLE_BODY, 10_000)));
    assert_ne!(upper, lower);
    assert_eq!(search::check_query("\u{c9}:w1", &[("\u{e9}", 2)]).unwrap_err().message(), "search_field_not_allowed: \u{c9}");
}

#[test]
fn grammar_leaf_kinds_pinned() {
    // Today's behaviour of Tantivy 0.27's other leaves on a text field, pinned so that a Tantivy bump
    // cannot change it silently; each one's column goes through the remap (rule 6).
    let f = fixture();
    let live_docs: Vec<&MDoc> = f.docs.iter().flatten().filter(|d| !dead(d.rowid)).collect();
    let title_has = |p: &dyn Fn(&str) -> bool| -> Vec<i64> {
        let mut r: Vec<i64> = live_docs
            .iter()
            .filter(|d| d.texts[0].as_deref().map(tokens).unwrap_or_default().iter().any(|t| p(t)))
            .map(|d| d.rowid)
            .collect();
        r.sort();
        r
    };
    let rowids = |hits: &[Hit]| {
        let mut r: Vec<i64> = hits.iter().map(|h| h.rowid).collect();
        r.sort();
        r
    };
    // A single term with a slop matches nothing.
    assert!(!run(&f, "title:w1", TITLE_BODY, 10_000).is_empty());
    assert!(run(&f, "title:w1~1", TITLE_BODY, 10_000).is_empty());
    // A range on a text field: the docs holding a token in the range, by byte order, every score 1.0.
    for (q, p) in [
        ("title:[w1 TO w3]", &(|t: &str| ("w1"..="w3").contains(&t)) as &dyn Fn(&str) -> bool),
        ("title:{w1 TO w3}", &|t: &str| t > "w1" && t < "w3"),
        ("TITLE:[w1 TO w3]", &|t: &str| ("w1"..="w3").contains(&t)),
    ] {
        let hits = run(&f, q, TITLE_BODY, 10_000);
        assert!(hits.len() > 100, "`{q}`: {} hits", hits.len());
        assert_eq!(rowids(&hits), title_has(p), "`{q}`");
        assert!(hits.iter().all(|h| h.score == 1.0), "`{q}`");
    }
    // A set: the docs holding one of its terms, every score 1.0.
    let hits = run(&f, "title: IN [w1 w2]", TITLE_BODY, 10_000);
    assert_eq!(rowids(&hits), title_has(&|t: &str| t == "w1" || t == "w2"));
    assert!(!hits.is_empty() && hits.iter().all(|h| h.score == 1.0));
    // Rule 6 holds for each of them.
    for q in ["body:w1~1", "body:[w1 TO w3]", "body:{w1 TO w3}", "body: IN [w1 w2]", "w1 OR BODY:[a TO b]"] {
        let e = search::check_query(q, &[("title", 1)]).unwrap_err();
        assert!(e.message() == "search_field_not_allowed: body" || e.message() == "search_field_not_allowed: BODY", "`{q}`: {e}");
    }
}

#[test]
fn k_is_one_to_ten_thousand() {
    let f = fixture();
    let segs: Vec<&Segment> = f.segs.iter().collect();
    let live: Vec<&[u64]> = f.live.iter().map(|w| w.as_slice()).collect();
    for k in [0usize, 10_001] {
        let e = search::top_k(&segs, &live, &[], Population::Table, "w1", TITLE_BODY, 1.2, 0.75, k).unwrap_err();
        assert_eq!(e.code(), "search_invalid_argument", "k = {k}: {e}");
    }
    assert_eq!(run(&f, "w1", TITLE_BODY, 1).len(), 1);
    // C ABI: qs_search refuses k 0 (even with no segment); qs_matches_open ignores k.
    let ptrs: Vec<*const qod_search_core::QsSegment> = Vec::new();
    let a = c_args(&ptrs, &[], &[], 0, "w1", TITLE_BODY, 0, std::ptr::null(), u64::MAX);
    let mut out = vec![empty_hit(); 1];
    let (mut n, mut err) = (0usize, std::ptr::null_mut());
    assert_ne!(unsafe { qod_search_core::qs_search(&a.args, out.as_mut_ptr(), 1, &mut n, &mut err) }, 0);
    assert_eq!(unsafe { take_err(err) }, "search_invalid_argument: k is 1 to 10000");
    let m = unsafe { qod_search_core::qs_matches_open(&a.args, 1, &mut err) };
    assert!(!m.is_null(), "{}", unsafe { take_err(err) });
    assert_eq!(unsafe { qod_search_core::qs_matches_next(m, out.as_mut_ptr(), 1, &mut n, &mut err) }, 0);
    assert_eq!(n, 0);
    unsafe { qod_search_core::qs_matches_free(m) };
}
