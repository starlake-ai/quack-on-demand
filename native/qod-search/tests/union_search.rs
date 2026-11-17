//! Q5's check, ported from bench/search/phase0/tantivy-probe/tests/union_segments.rs to the bundle
//! format: the per-call union of separately built segments, alive = live AND allowed, with QoD's own
//! statistics, gives the top k and scores (1e-6 relative) of a fresh index of exactly the
//! population, for both populations of spec 7.5. Also rule 6's query check.
mod common;
use common::*;
use qod_search_core::search::{self, Hit, Population};
use qod_search_core::Segment;

fn assert_same(got: &[Hit], want: &[Hit], what: &str) -> f64 {
    assert_eq!(
        got.iter().map(|h| h.rowid).collect::<Vec<_>>(),
        want.iter().map(|h| h.rowid).collect::<Vec<_>>(),
        "{what}"
    );
    let mut max = 0f64;
    for (g, w) in got.iter().zip(want) {
        let r = rel(g.score, w.score as f64);
        assert!(r <= 1e-6, "{what}: rowid {} scored {} vs {}", g.rowid, g.score, w.score);
        max = max.max(r);
    }
    max
}

/// The probe's `search_reference`: a plain Tantivy index of exactly `docs`, with Tantivy's own
/// statistics, every hit kept by `keep`, sorted by score then rowid, the first k.
fn tantivy_fresh(docs: &[TDoc], q: &str, keep: &dyn Fn(i64) -> bool, k: usize) -> Vec<Hit> {
    use tantivy::collector::TopDocs;
    use tantivy::query::QueryParser;
    use tantivy::schema::{Schema, FAST, TEXT};
    use tantivy::{doc, Index};
    let mut sb = Schema::builder();
    let body = sb.add_text_field("body", TEXT);
    let id = sb.add_i64_field("id", FAST);
    let index = Index::create_in_ram(sb.build());
    let mut w: tantivy::IndexWriter = index.writer_with_num_threads(1, 200_000_000).unwrap();
    for d in docs {
        w.add_document(doc!(body => d.text.as_str(), id => d.rowid)).unwrap();
    }
    w.commit().unwrap();
    let searcher = index.reader().unwrap().searcher();
    let query = QueryParser::for_index(&index, vec![body]).parse_query(q).unwrap();
    let hits = searcher.search(query.as_ref(), &TopDocs::with_limit(docs.len()).order_by_score()).unwrap();
    let mut out: Vec<Hit> = hits
        .into_iter()
        .map(|(score, addr)| {
            let col = searcher.segment_reader(addr.segment_ord).fast_fields().i64("id").unwrap();
            Hit { rowid: col.first(addr.doc_id).unwrap(), segment: 0, doc: 0, score }
        })
        .filter(|h| keep(h.rowid))
        .collect();
    out.sort_by(|x, y| y.score.total_cmp(&x.score).then(x.rowid.cmp(&y.rowid)));
    out.truncate(k);
    out
}

fn check(population: Population, queries: &[&str], long_docs: bool) -> f64 {
    let text = |i: u64| {
        if long_docs {
            // Docs of up to about 2000 tokens, where fieldnorms quantize far from the exact count.
            (0..1 + i % 10).map(|r| page_text(i * 31 + r)).collect::<Vec<_>>().join(" ")
        } else {
            page_text(i)
        }
    };
    let n = 6_000u64;
    let docs: Vec<TDoc> = (0..n).map(|i| TDoc { rowid: i as i64, text: text(i) }).collect();
    let (a, rest) = docs.split_at(2_500);
    let (b, c) = rest.split_at(2_000);
    let segs: Vec<Segment> = [a, b, c].iter().enumerate().map(|(i, d)| build(&format!("/lake/u/{i}.parquet"), d)).collect();
    let live = |r: i64| r % 13 != 0; // dead docs: deletes and rows above S
    let allowed = |r: i64| r % 4 == 0 || r % 9 == 0; // the governed input
    let in_population = |r: i64| match population {
        Population::Table => live(r),
        Population::Allowed => live(r) && allowed(r),
    };
    let lives: Vec<Vec<u64>> = [a, b, c].iter().map(|d| bitset(d.len(), |p| live(d[p].rowid))).collect();
    let alloweds: Vec<Vec<u64>> = [a, b, c].iter().map(|d| bitset(d.len(), |p| allowed(d[p].rowid))).collect();
    // A fresh index of exactly the population, every doc alive, scored over its own table population.
    let fresh_docs: Vec<TDoc> = docs.iter().filter(|d| in_population(d.rowid)).cloned().collect();
    let fresh = build("/lake/u/fresh.parquet", &fresh_docs);
    let fresh_all = bitset(fresh_docs.len(), |_| true);
    let fresh_allowed = bitset(fresh_docs.len(), |p| allowed(fresh_docs[p].rowid));
    let refs: Vec<&Segment> = segs.iter().collect();
    let live_r: Vec<&[u64]> = lives.iter().map(|w| w.as_slice()).collect();
    let allowed_r: Vec<&[u64]> = alloweds.iter().map(|w| w.as_slice()).collect();
    let mut max = 0f64;
    for q in queries {
        let got = search::top_k(&refs, &live_r, &allowed_r, population, q, BODY, 1.2, 0.75, 10).unwrap();
        let want =
            search::top_k(&[&fresh], &[&fresh_all], &[&fresh_allowed], Population::Table, q, BODY, 1.2, 0.75, 10)
                .unwrap();
        assert!(!want.is_empty(), "`{q}` matches nothing");
        max = max.max(assert_same(&got, &want, &format!("{population:?} long={long_docs} `{q}`")));
        // And against Tantivy's own statistics on a plain index of the population (the probe's check).
        let plain = tantivy_fresh(&fresh_docs, q, &|r| allowed(r), 10);
        max = max.max(assert_same(&got, &plain, &format!("{population:?} long={long_docs} `{q}` vs Tantivy")));
    }
    max
}

const QUERIES: [&str; 6] = ["w0", "w1 w10", "w3 OR w7", "w2 AND w5", "\"w0 w1\"", "w4999"];

#[test]
fn table_population_matches_a_fresh_index() {
    let m = check(Population::Table, &QUERIES, false);
    println!("table population: max relative score gap to a fresh index {m:e}");
}

#[test]
fn allowed_population_matches_a_fresh_index() {
    let m = check(Population::Allowed, &QUERIES, false);
    println!("allowed population: max relative score gap to a fresh index {m:e}");
}

#[test]
fn both_populations_match_on_long_docs() {
    let a = check(Population::Table, &QUERIES[..3], true);
    let b = check(Population::Allowed, &QUERIES[..3], true);
    println!("long docs: max relative score gap {a:e} (table), {b:e} (allowed)");
}

#[test]
fn rule_6_query_fields() {
    assert_eq!(search::check_query("title:x", BODY).unwrap_err().message(), "search_field_not_allowed: title");
    search::check_query("body:x w1", BODY).unwrap();
    assert_eq!(
        search::check_query("__qod_position:3", BODY).unwrap_err().message(),
        "search_field_not_allowed: __qod_position"
    );
    assert_eq!(search::check_query("body:(x", BODY).unwrap_err().code(), "search_query_invalid");
    // Column names are free (a bundle names fields by field id), but not empty.
    search::check_query("x", &[("__qod_position", 1)]).unwrap();
    search::check_query("x", &[("my col", 1)]).unwrap();
    assert_eq!(search::check_query("x", &[("", 1)]).unwrap_err().code(), "search_invalid_argument");
}
