//! Task 7 fix round 2 (re-review R1): nested boolean shapes against an f32 boolean reference
//! written here, independent of the collector. The reference evaluates the query's grammar tree
//! with Tantivy's boolean matching rules (every required clause, no excluded one, at least one
//! optional one when nothing is required) and sums the matching scored clauses in clause order,
//! each term scored by Tantivy's BM25 formula in its f32 order over the population. The docs it
//! matches are checked against Tantivy's own matching on a plain Tantivy index.
mod common;
use common::*;
use qod_search_core::search::{top_k, Population};
use qod_search_core::Segment;
use std::collections::BTreeSet;
use tantivy::query_grammar::{Occur, UserInputAst, UserInputLeaf};

/// A query tree: a term, `*`, or a group of (occur, child) with occur '+', ' ' or '-'.
#[derive(Debug)]
enum Q {
    T(String),
    All,
    G(Vec<(char, Q)>),
}

fn from_ast(a: &UserInputAst) -> Q {
    match a {
        UserInputAst::Clause(cs) => Q::G(
            cs.iter()
                .map(|(o, c)| {
                    let occ = match o {
                        Some(Occur::Must) => '+',
                        Some(Occur::MustNot) => '-',
                        _ => ' ',
                    };
                    (occ, from_ast(c))
                })
                .collect(),
        ),
        UserInputAst::Leaf(l) => match l.as_ref() {
            UserInputLeaf::Literal(lit) => {
                assert_eq!(tokens(&lit.phrase).len(), 1, "one token per literal in these tests");
                Q::T(tokens(&lit.phrase).remove(0))
            }
            UserInputLeaf::All => Q::All,
            other => panic!("unexpected leaf {other:?}"),
        },
        UserInputAst::Boost(..) => panic!("no boosts in these tests"),
    }
}

struct Stats {
    n: u64,
    avg: f32,
}

fn term_score(stats: &Stats, df: u64, tf: u32, dl: u32) -> f32 {
    let x = ((stats.n - df) as f32 + 0.5) / (df as f32 + 0.5);
    let weight = (1.0f32 + x).ln() * (1.0 + 1.2);
    let norm = 1.2 * (1.0 - 0.75 + 0.75 * dl as f32 / stats.avg);
    weight * (tf as f32 / (tf as f32 + norm))
}

/// The score of `doc` under `q`, None when it does not match.
fn eval(q: &Q, d: &RefDoc, stats: &Stats, df: &dyn Fn(&str) -> u64) -> Option<f32> {
    match q {
        Q::T(t) => {
            let tf = d.tokens.iter().filter(|x| *x == t).count() as u32;
            if tf == 0 {
                None
            } else {
                Some(term_score(stats, df(t), tf, quantized(d.tokens.len() as u32)))
            }
        }
        Q::All => Some(1.0),
        Q::G(cs) => {
            // Matching: every required clause, no excluded one, and with no required clause at
            // least one optional one. Scoring: the clause-order sum of the matching scored clauses
            // other than `*`; then `*` as Tantivy's boolean scores it: nothing beside a required
            // clause that is not `*`, 1.0 before the optional sum when `*` is the only kind of
            // required clause, 1.0 once after the optional sum in a group with no required clause.
            let mut sum: Option<f32> = None;
            let mut any_required = false;
            let mut any_optional_match = false;
            let (mut real_required, mut all_required, mut all_optional) = (false, false, false);
            for (o, c) in cs {
                let v = eval(c, d, stats, df);
                let is_all = matches!(c, Q::All);
                match o {
                    '-' => {
                        if v.is_some() {
                            return None;
                        }
                        continue;
                    }
                    '+' => {
                        any_required = true;
                        v?;
                        if is_all {
                            all_required = true;
                        } else {
                            real_required = true;
                        }
                    }
                    _ => {
                        any_optional_match |= v.is_some();
                        all_optional |= is_all;
                    }
                }
                if is_all {
                    continue;
                }
                if let Some(v) = v {
                    sum = Some(sum.map(|s| s + v).unwrap_or(v));
                }
            }
            if !any_required && !any_optional_match {
                return None;
            }
            Some(if real_required {
                sum.unwrap_or(0.0)
            } else if all_required {
                sum.map(|v| 1.0 + v).unwrap_or(1.0)
            } else if all_optional {
                sum.map(|v| v + 1.0).unwrap_or(1.0)
            } else {
                sum.unwrap_or(0.0)
            })
        }
    }
}

/// Tantivy's own matches of `q` on a plain index of `docs` (ids = rowids), with Tantivy's own
/// scores (its statistics are the table population's: one index, every doc alive).
fn tantivy_matches(docs: &[TDoc], q: &str) -> std::collections::BTreeMap<i64, f32> {
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
    searcher
        .search(query.as_ref(), &TopDocs::with_limit(docs.len()).order_by_score())
        .unwrap()
        .into_iter()
        .map(|(score, addr)| {
            (searcher.segment_reader(addr.segment_ord).fast_fields().i64("id").unwrap().first(addr.doc_id).unwrap(), score)
        })
        .collect()
}

const QUERIES: [&str; 13] = [
    // An optional AND group inside an OR (the re-review's first probe).
    "w5 OR (w0 AND w1 AND w2)",
    // A group with an excluded clause.
    "w5 OR (w0 AND -w1) OR (w0 AND w1 AND w2)",
    // Doubly nested groups.
    "w5 OR (w0 AND (w1 OR (w2 AND w3 AND w4)))",
    "(w6 OR (w0 AND w1 AND w2)) AND (w3 OR w4)",
    // A term repeated across clauses.
    "w0 OR (w0 AND w1 AND w2)",
    // `*` inside a group.
    "w5 OR (w0 AND w1 AND w2) OR *",
    "w5 OR (+w0 +w1 *)",
    // `*` beside required clauses (it adds 0 there, as in Tantivy), nested too, and `*` as the
    // only required clause (it adds 1.0 there).
    "+w0 +w1 +w2 *",
    "w5 OR (w0 AND w1 AND w2 AND *)",
    "+* w1 w2 w3",
    "w5 OR (+* w0 w1 w2)",
    // Flat mixed shapes.
    "+w0 +w1 +w2 w3 -w4",
    "w3 +w0 w5 +w1 +w2",
];

#[test]
fn nested_shapes_match_the_independent_reference() {
    // The re-review's three docs, then 6,000 corpus docs, in two segments.
    let mut docs: Vec<TDoc> = vec![
        TDoc { rowid: 0, text: "w5 w0 zz".into() },
        TDoc { rowid: 1, text: "w5 zz zz".into() },
        TDoc { rowid: 2, text: "w0 w1 w2".into() },
    ];
    docs.extend((3..6_003).map(|i| TDoc { rowid: i, text: page_text(i as u64) }));
    let (a, b) = docs.split_at(2_500);
    let (a, b) = (a.to_vec(), b.to_vec());
    let segs: Vec<Segment> = [&a, &b].iter().enumerate().map(|(i, d)| build(&format!("/lake/n/{i}.parquet"), d)).collect();
    let refs = ref_docs(&docs);
    let live: Vec<Vec<u64>> = [&a, &b].iter().map(|d| bitset(d.len(), |_| true)).collect();
    let lr: Vec<&[u64]> = live.iter().map(|w| w.as_slice()).collect();
    let segs_r: Vec<&Segment> = segs.iter().collect();
    for q in QUERIES {
        let tree = from_ast(&tantivy::query_grammar::parse_query(q).unwrap());
        let tantivy_set = tantivy_matches(&docs, q);
        for (pop, allowed) in [(Population::Table, &(|_: i64| true) as &dyn Fn(i64) -> bool), (Population::Allowed, &|r: i64| r % 3 != 1)] {
            let population: Vec<&RefDoc> = refs.iter().filter(|d| pop == Population::Table || allowed(d.rowid)).collect();
            let n = population.len() as u64;
            let total: u64 = population.iter().map(|d| d.tokens.len() as u64).sum();
            let stats = Stats { n, avg: total as f32 / n as f32 };
            // df of every term of the query over the population, counted once.
            let mut dfs = std::collections::HashMap::new();
            for t in ["w0", "w1", "w2", "w3", "w4", "w5", "w6"] {
                dfs.insert(t.to_string(), population.iter().filter(|d| d.tokens.iter().any(|x| x == t)).count() as u64);
            }
            let df = |t: &str| *dfs.get(t).expect("a term of the test queries");
            let mut want: Vec<(i64, f32)> = refs
                .iter()
                .filter(|d| allowed(d.rowid))
                .filter_map(|d| eval(&tree, d, &stats, &df).map(|s| (d.rowid, s)))
                .collect();
            want.sort_by(|x, y| y.1.total_cmp(&x.1).then(x.0.cmp(&y.0)));
            // The reference matches exactly the docs Tantivy matches.
            let want_set: BTreeSet<i64> = want.iter().map(|w| w.0).collect();
            let tantivy_allowed: BTreeSet<i64> = tantivy_set.keys().copied().filter(|r| allowed(*r)).collect();
            // Over the table population Tantivy's own scores are the same BM25 values summed in its
            // own order: equal within f32 rounding, `*` included.
            if pop == Population::Table {
                let mut bit_equal = 0usize;
                for (r, s) in &want {
                    let t = tantivy_set[r];
                    assert!(((s - t) / t).abs() <= 1e-6, "`{q}` rowid {r}: {s} vs Tantivy's {t}");
                    bit_equal += (s.to_bits() == t.to_bits()) as usize;
                }
                println!("`{q}`: {bit_equal} of {} scores bit-equal to Tantivy's own (the rest within 1e-6)", want.len());
            }
            assert_eq!(want_set, tantivy_allowed, "{pop:?} `{q}`: reference and Tantivy match different docs");
            assert!(!want.is_empty(), "`{q}` matches nothing");
            let alw: Vec<Vec<u64>> = [&a, &b].iter().map(|d| bitset(d.len(), |p| allowed(d[p].rowid))).collect();
            let ar: Vec<&[u64]> = alw.iter().map(|w| w.as_slice()).collect();
            let got = top_k(&segs_r, &lr, &ar, pop, q, "body", 1.2, 0.75, 10_000).unwrap();
            let got: Vec<(i64, u32)> = got.iter().map(|h| (h.rowid, h.score.to_bits())).collect();
            let want: Vec<(i64, u32)> = want.iter().map(|(r, s)| (*r, s.to_bits())).collect();
            assert_eq!(got.len(), want.len(), "{pop:?} `{q}`: hit counts");
            assert!(got == want, "{pop:?} `{q}`: first difference at {:?}", got.iter().zip(&want).position(|(g, w)| g != w));
        }
    }
    // The re-review's probe: doc 0 (`w5 w0`) does not satisfy the AND, so it scores as doc 1.
    let got = top_k(&segs_r, &lr, &[], Population::Table, "w5 OR (w0 AND w1 AND w2)", "body", 1.2, 0.75, 10_000).unwrap();
    let s0 = got.iter().find(|h| h.rowid == 0).unwrap().score;
    let s1 = got.iter().find(|h| h.rowid == 1).unwrap().score;
    println!("`w5 OR (w0 AND w1 AND w2)`: doc 0 {s0}, doc 1 {s1}");
}
