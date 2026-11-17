//! search::top_k against a naive BM25 reference (spec 7.5, invariant 7): the table population and
//! the allowed population, statistics over exactly the population's docs, the quantized fieldnorm as
//! each doc's dl and the exact token total for avgdl (P4, Q5).
mod common;
use common::*;
use qod_search_core::search::{self, Hit, Population};
use qod_search_core::Segment;

const QUERIES: [&str; 4] = ["w0", "w1 w10", "w49999", "w3 w7 w11"];

struct Fixture {
    docs: Vec<Vec<TDoc>>,
    segs: Vec<Segment>,
}

fn fixture() -> Fixture {
    let docs = corpus();
    let segs = docs.iter().enumerate().map(|(i, d)| build(&format!("/lake/main/pages/part-{i}.parquet"), d)).collect();
    Fixture { docs, segs }
}

fn dead_in_segment_0(p: usize) -> bool {
    p == 5 || p == 17 || (4000..4100).contains(&p)
}

fn rowids(h: &[Hit]) -> Vec<i64> {
    h.iter().map(|h| h.rowid).collect()
}

/// Compares `got` with the reference's top k: same rowids, rank for rank, and scores within 1e-6
/// relative of the f32-order reference. Returns the max relative error against both references.
fn compare(
    what: &str,
    got: &[Hit],
    population: &[&RefDoc],
    scored: &dyn Fn(&RefDoc) -> bool,
    query: &str,
    k: usize,
) -> (f64, f64) {
    let want32 = reference(population, scored, query, 1.2, 0.75, true);
    let want64 = reference(population, scored, query, 1.2, 0.75, false);
    let want: Vec<(i64, f64)> = want32.iter().take(k).cloned().collect();
    assert_eq!(rowids(got), want.iter().map(|w| w.0).collect::<Vec<_>>(), "{what} `{query}`");
    let mut max32 = 0f64;
    for (g, w) in got.iter().zip(&want) {
        let r = rel(g.score, w.1);
        assert!(r <= 1e-6, "{what} `{query}`: rowid {} scored {} vs {} ({r:e})", g.rowid, g.score, w.1);
        max32 = max32.max(r);
    }
    let by64: std::collections::HashMap<i64, f64> = want64.into_iter().collect();
    let max64 = got.iter().map(|g| rel(g.score, by64[&g.rowid])).fold(0f64, f64::max);
    (max32, max64)
}

#[test]
fn table_population_matches_reference() {
    let f = fixture();
    // live: every doc except positions 5, 17 and 4000..4100 of segment 0; allowed: all.
    let live: Vec<Vec<u64>> = f
        .docs
        .iter()
        .enumerate()
        .map(|(i, d)| bitset(d.len(), |p| !(i == 0 && dead_in_segment_0(p))))
        .collect();
    let all: Vec<Vec<u64>> = f.docs.iter().map(|d| bitset(d.len(), |_| true)).collect();
    let segs: Vec<&Segment> = f.segs.iter().collect();
    let live_r: Vec<&[u64]> = live.iter().map(|w| w.as_slice()).collect();
    let all_r: Vec<&[u64]> = all.iter().map(|w| w.as_slice()).collect();
    let refs: Vec<Vec<RefDoc>> = f.docs.iter().map(|d| ref_docs(d)).collect();
    let docs_live: Vec<&RefDoc> = refs
        .iter()
        .enumerate()
        .flat_map(|(i, s)| s.iter().enumerate().filter(move |(p, _)| !(i == 0 && dead_in_segment_0(*p))).map(|(_, d)| d))
        .collect();
    assert_eq!(docs_live.len(), 20_000 - 102);
    let (mut m32, mut m64) = (0f64, 0f64);
    for query in QUERIES {
        let got = search::top_k(&segs, &live_r, &all_r, Population::Table, query, BODY, 1.2, 0.75, 10).unwrap();
        let (a, b) = compare("table", &got, &docs_live, &|_| true, query, 10);
        m32 = m32.max(a);
        m64 = m64.max(b);
    }
    println!("table population: max relative error {m32:e} against the f32-order reference, {m64:e} against the f64 reference");
}

fn allowed_case(name: &str, pred: &dyn Fn(i64) -> bool) {
    let f = fixture();
    let live: Vec<Vec<u64>> = f
        .docs
        .iter()
        .enumerate()
        .map(|(i, d)| bitset(d.len(), |p| !(i == 0 && dead_in_segment_0(p))))
        .collect();
    let allowed: Vec<Vec<u64>> = f.docs.iter().map(|d| bitset(d.len(), |p| pred(d[p].rowid))).collect();
    let segs: Vec<&Segment> = f.segs.iter().collect();
    let live_r: Vec<&[u64]> = live.iter().map(|w| w.as_slice()).collect();
    let allowed_r: Vec<&[u64]> = allowed.iter().map(|w| w.as_slice()).collect();
    let refs: Vec<Vec<RefDoc>> = f.docs.iter().map(|d| ref_docs(d)).collect();
    // The reference's population: live AND allowed only (7.5, invariant 5).
    let population: Vec<&RefDoc> = refs
        .iter()
        .enumerate()
        .flat_map(|(i, s)| {
            s.iter().enumerate().filter(move |(p, d)| !(i == 0 && dead_in_segment_0(*p)) && pred(d.rowid)).map(|(_, d)| d)
        })
        .collect();
    let (mut m32, mut m64) = (0f64, 0f64);
    for query in QUERIES {
        let got =
            search::top_k(&segs, &live_r, &allowed_r, Population::Allowed, query, BODY, 1.2, 0.75, 10).unwrap();
        for h in &got {
            assert!(pred(h.rowid), "{name} `{query}`: rowid {} is not allowed", h.rowid);
        }
        let (a, b) = compare(name, &got, &population, &|_| true, query, 10);
        m32 = m32.max(a);
        m64 = m64.max(b);
        // The table population over the same allowed set scores with the table's statistics: the
        // same docs are returned (live AND allowed), with other scores.
        let table =
            search::top_k(&segs, &live_r, &allowed_r, Population::Table, query, BODY, 1.2, 0.75, 10).unwrap();
        for h in &table {
            assert!(pred(h.rowid));
        }
    }
    println!("{name} ({} docs in the population): max relative error {m32:e} against the f32-order reference, {m64:e} against the f64 reference", population.len());
}

#[test]
fn allowed_population_matches_reference_over_the_allowed_docs() {
    // allowed: docs with position % 10 == 0 (10%), then % 100 == 0 (1%), then a clustered 1%.
    allowed_case("allowed 10%", &|r| r % 10 == 0);
    allowed_case("allowed 1%", &|r| r % 100 == 0);
    allowed_case("allowed clustered 1%", &|r| (5_000..5_200).contains(&r));
}

#[test]
fn hidden_rows_never_change_an_allowed_score() {
    // Property: add 500 random docs outside the allowed set to a segment (rebuild it), the
    // allowed-population scores of every query are bit-identical.
    let f = fixture();
    let allowed_pred = |r: i64| r < 1_000_000 && r % 10 == 0;
    let mut with_hidden: Vec<TDoc> = Vec::new();
    let mut hidden = 0u64;
    for (p, d) in f.docs[1].iter().enumerate() {
        // About 500 hidden docs spread over the segment, at positions a hash picks.
        if (p as u64).wrapping_mul(0x9e37_79b9_7f4a_7c15) % 14 == 0 && hidden < 500 {
            with_hidden.push(TDoc { rowid: 1_000_000 + hidden as i64, text: random_text(hidden) });
            hidden += 1;
        }
        with_hidden.push(d.clone());
    }
    while hidden < 500 {
        with_hidden.push(TDoc { rowid: 1_000_000 + hidden as i64, text: random_text(hidden) });
        hidden += 1;
    }
    let rebuilt = build("/lake/main/pages/part-1.parquet", &with_hidden);
    assert!(rebuilt.has_explicit_rowids());
    let run = |segs: Vec<&Segment>, docs: Vec<&Vec<TDoc>>, query: &str| -> Vec<Hit> {
        let live: Vec<Vec<u64>> = docs.iter().map(|d| bitset(d.len(), |_| true)).collect();
        let allowed: Vec<Vec<u64>> = docs.iter().map(|d| bitset(d.len(), |p| allowed_pred(d[p].rowid))).collect();
        let live_r: Vec<&[u64]> = live.iter().map(|w| w.as_slice()).collect();
        let allowed_r: Vec<&[u64]> = allowed.iter().map(|w| w.as_slice()).collect();
        search::top_k(&segs, &live_r, &allowed_r, Population::Allowed, query, BODY, 1.2, 0.75, 50).unwrap()
    };
    for query in QUERIES {
        let before = run(f.segs.iter().collect(), f.docs.iter().collect(), query);
        let after = run(
            vec![&f.segs[0], &rebuilt, &f.segs[2]],
            vec![&f.docs[0], &with_hidden, &f.docs[2]],
            query,
        );
        let a: Vec<(i64, u32)> = before.iter().map(|h| (h.rowid, h.score.to_bits())).collect();
        let b: Vec<(i64, u32)> = after.iter().map(|h| (h.rowid, h.score.to_bits())).collect();
        assert_eq!(a, b, "`{query}`");
        // The table population does see the hidden docs (a negative control).
        let all: Vec<Vec<u64>> = [&f.docs[0], &with_hidden, &f.docs[2]].iter().map(|d| bitset(d.len(), |_| true)).collect();
        let allowed: Vec<Vec<u64>> = [&f.docs[0], &with_hidden, &f.docs[2]]
            .iter()
            .map(|d| bitset(d.len(), |p| allowed_pred(d[p].rowid)))
            .collect();
        let all_r: Vec<&[u64]> = all.iter().map(|w| w.as_slice()).collect();
        let allowed_r: Vec<&[u64]> = allowed.iter().map(|w| w.as_slice()).collect();
        let table = search::top_k(
            &[&f.segs[0], &rebuilt, &f.segs[2]],
            &all_r,
            &allowed_r,
            Population::Table,
            query,
            BODY,
            1.2,
            0.75,
            50,
        )
        .unwrap();
        let t: Vec<(i64, u32)> = table.iter().map(|h| (h.rowid, h.score.to_bits())).collect();
        assert_ne!(t, b, "`{query}`: the table population ignored the hidden docs");
    }
}

#[test]
fn ties_break_by_rowid() {
    // 100 identical docs over two segments, their rowids descending with position, plus filler:
    // k = 10 returns the 10 smallest rowids, whatever the doc addresses.
    let mut a = Vec::new();
    let mut b = Vec::new();
    for p in 0..50 {
        a.push(TDoc { rowid: 99 - p, text: "alpha beta".into() });
        a.push(TDoc { rowid: 1000 + p, text: "gamma delta".into() });
    }
    for p in 0..50 {
        b.push(TDoc { rowid: 49 - p, text: "alpha beta".into() });
        b.push(TDoc { rowid: 2000 + p, text: "gamma".into() });
    }
    let sa = build("/lake/t/a.parquet", &a);
    let sb = build("/lake/t/b.parquet", &b);
    let la = bitset(a.len(), |_| true);
    let lb = bitset(b.len(), |_| true);
    let got = search::top_k(&[&sa, &sb], &[&la, &lb], &[], Population::Table, "alpha", BODY, 1.2, 0.75, 10).unwrap();
    assert_eq!(rowids(&got), (0..10).collect::<Vec<i64>>());
    assert!(got.iter().all(|h| h.score == got[0].score));
    // Segment order does not matter.
    let got = search::top_k(&[&sb, &sa], &[&lb, &la], &[], Population::Table, "alpha", BODY, 1.2, 0.75, 10).unwrap();
    assert_eq!(rowids(&got), (0..10).collect::<Vec<i64>>());
    assert!(got.iter().all(|h| h.segment == 0));
    // 30 identical docs, k = 10: the 10 smallest rowids.
    let c: Vec<TDoc> = (0..30).map(|p| TDoc { rowid: 300 - p * 7, text: "alpha".into() }).collect();
    let sc = build("/lake/t/c.parquet", &c);
    let lc = bitset(c.len(), |_| true);
    let got = search::top_k(&[&sc], &[&lc], &[], Population::Table, "alpha", BODY, 1.2, 0.75, 10).unwrap();
    let mut want: Vec<i64> = c.iter().map(|d| d.rowid).collect();
    want.sort();
    want.truncate(10);
    assert_eq!(rowids(&got), want);
}

#[test]
fn arguments_are_checked() {
    let f = fixture();
    let segs: Vec<&Segment> = f.segs.iter().collect();
    let all: Vec<Vec<u64>> = f.docs.iter().map(|d| bitset(d.len(), |_| true)).collect();
    let all_r: Vec<&[u64]> = all.iter().map(|w| w.as_slice()).collect();
    let err = search::top_k(&segs, &all_r, &[], Population::Table, "w0", BODY, 1.5, 0.75, 10).unwrap_err();
    assert_eq!(err.code(), "search_invalid_argument");
    let err = search::top_k(&segs, &all_r, &[], Population::Table, "w0", BODY, 1.2, 0.75, 10_001).unwrap_err();
    assert_eq!(err.code(), "search_invalid_argument");
    let err = search::top_k(&segs, &all_r, &[], Population::Table, "title:w0", BODY, 1.2, 0.75, 10).unwrap_err();
    assert_eq!(err.message(), "search_field_not_allowed: title");
    let err = search::top_k(&segs, &all_r[..2], &[], Population::Table, "w0", BODY, 1.2, 0.75, 10).unwrap_err();
    assert_eq!(err.code(), "search_invalid_argument");
    let err = search::top_k(&segs, &all_r, &[], Population::Table, "w0", BODY, 1.2, 0.75, 0).unwrap_err();
    assert_eq!(err.message(), "search_invalid_argument: k is 1 to 10000");
    // Nothing alive: no hits.
    let none: Vec<Vec<u64>> = f.docs.iter().map(|d| bitset(d.len(), |_| false)).collect();
    let none_r: Vec<&[u64]> = none.iter().map(|w| w.as_slice()).collect();
    assert!(search::top_k(&segs, &none_r, &[], Population::Allowed, "w0", BODY, 1.2, 0.75, 10).unwrap().is_empty());
    // k = 10000 is accepted and returns every matching live doc up to k, in order.
    let got = search::top_k(&segs, &all_r, &[], Population::Table, "w49999", BODY, 1.2, 0.75, 10_000).unwrap();
    assert!(got.windows(2).all(|w| w[0].score > w[1].score || (w[0].score == w[1].score && w[0].rowid < w[1].rowid)));
}
