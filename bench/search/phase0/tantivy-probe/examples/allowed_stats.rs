//! Q8: cost of the allowed population (spec 7.5) on one segment of N docs, single-threaded, warm:
//! the statistics (N and the exact token total over the allowed docs, the sum of their quantized
//! fieldnorms as 7.5 states it, df of each query term over the allowed docs, and the library's
//! `population_stats` end to end) and the top-10 search with the allowed set as the segment's alive
//! bitset (a delete file, `with_alive`), at 100%, 50%, 10% and 1% allowed (striped over the docs)
//! and 1% clustered (one contiguous doc-id range). Each search is also run on a fresh index of
//! exactly the allowed docs, whose own statistics are the allowed population's: the cost the search
//! would have if dead docs cost nothing, and as a filter-driven intersection (the allowed set as a
//! required clause, so only allowed matching docs are scored, with no block-WAND pruning). The cost of
//! fingerprinting the allowed set (SipHash over its packed bits) is measured for a statistics cache.
//!
//! Usage: cargo run --release --example allowed_stats -- <n_docs> [repetitions]
//!
//! Deviation from the brief's sketch: `build_segment` (one writer thread, 50 MB) flushes 10
//! segments at 1M docs and fails its one-segment assertion, so this example builds with several
//! writer threads, no merge policy, and one explicit merge into a single segment, then maps doc ids
//! to external ids through the `id` fast field (the merge does not keep the insertion order).
use rand::{rngs::StdRng, Rng, SeedableRng};
use std::collections::hash_map::DefaultHasher;
use std::collections::HashMap;
use std::fmt;
use std::hash::Hasher;
use std::hint::black_box;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tantivy::collector::Count;
use tantivy::index::SegmentReader;
use tantivy::indexer::NoMergePolicy;
use tantivy::query::{
    BitSetDocSet, BooleanQuery, ConstScorer, EnableScoring, Explanation, Occur, Query, QueryParser,
    Scorer, Weight,
};
use tantivy::schema::IndexRecordOption;
use tantivy::{doc, BitSet, DocSet, Index, ReloadPolicy, Score, Searcher, Term, TERMINATED};
use tantivy_probe::{
    parts_in_reader_order, population_stats, schema, top, union, with_alive, Built,
};

/// (label, query text). The default query parser reads `w1 w10` as `w1 OR w10`.
const QUERIES: [(&str, &str); 3] = [
    ("common", "w0"),
    ("rare", "w49999"),
    ("2-term OR", "w1 w10"),
];
const K: usize = 10;
const WARMUP: usize = 3;

/// The brief's corpus: 20 to 199 words per doc, word rank ~ u^4 over a 50,000-word vocabulary.
fn generate(n: u64) -> Vec<String> {
    let vocabulary: Vec<String> = (0..50_000).map(|i| format!("w{i}")).collect();
    let mut rng = StdRng::seed_from_u64(42);
    (0..n)
        .map(|_| {
            let len = rng.gen_range(20..200);
            let words: Vec<&str> = (0..len)
                .map(|_| {
                    let rank = (rng.gen::<f64>().powi(4) * vocabulary.len() as f64) as usize;
                    vocabulary[rank].as_str()
                })
                .collect();
            words.join(" ")
        })
        .collect()
}

/// One segment holding the docs `keep` accepts (external id = position in `texts`).
fn build(texts: &[String], counts: &[u64], keep: &dyn Fn(u64) -> bool, threads: usize) -> Built {
    let (schema, id, body) = schema();
    let index = Index::create_in_ram(schema);
    let mut writer: tantivy::IndexWriter = index
        .writer_with_num_threads(threads, threads * 1_000_000_000)
        .unwrap();
    writer.set_merge_policy(Box::new(NoMergePolicy));
    for (i, text) in texts.iter().enumerate() {
        if keep(i as u64) {
            writer
                .add_document(doc!(id => i as u64, body => text.as_str()))
                .unwrap();
        }
    }
    writer.commit().unwrap();
    let segments = index.searchable_segment_ids().unwrap();
    if segments.len() > 1 {
        writer.merge(&segments).wait().unwrap();
    }
    writer.wait_merging_threads().unwrap();
    let searcher = manual_searcher(&index);
    assert_eq!(
        searcher.segment_readers().len(),
        1,
        "one segment after the merge"
    );
    let reader = &searcher.segment_readers()[0];
    let column = reader.fast_fields().u64("id").unwrap();
    let ids: Vec<u64> = (0..reader.max_doc())
        .map(|d| column.first(d).unwrap())
        .collect();
    let token_counts: Vec<u64> = ids.iter().map(|i| counts[*i as usize]).collect();
    let total: u64 = token_counts.iter().sum();
    assert_eq!(
        reader.inverted_index(body).unwrap().total_num_tokens(),
        total,
        "exact token total"
    );
    Built {
        index,
        ids,
        token_counts,
    }
}

/// The allowed set of the single segment as a query matching exactly those docs, scored 0, so that
/// `allowed AND query` keeps the query's BM25 scores and scores only allowed docs.
#[derive(Clone)]
struct AllowedFilter(Arc<BitSet>);

impl fmt::Debug for AllowedFilter {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "AllowedFilter({} docs)", self.0.len())
    }
}

impl Query for AllowedFilter {
    fn weight(&self, _: EnableScoring<'_>) -> tantivy::Result<Box<dyn Weight>> {
        Ok(Box::new(AllowedWeight(self.0.clone())))
    }
}

struct AllowedWeight(Arc<BitSet>);

impl Weight for AllowedWeight {
    fn scorer(&self, _: &SegmentReader, _: Score) -> tantivy::Result<Box<dyn Scorer>> {
        Ok(Box::new(ConstScorer::new(
            BitSetDocSet::from((*self.0).clone()),
            0.0,
        )))
    }
    fn explain(&self, _: &SegmentReader, _: u32) -> tantivy::Result<Explanation> {
        Ok(Explanation::new("allowed", 0.0))
    }
}

fn manual_searcher(index: &Index) -> Searcher {
    index
        .reader_builder()
        .reload_policy(ReloadPolicy::Manual)
        .try_into()
        .unwrap()
        .searcher()
}

/// p50 and p95 of `reps` timed runs after a warm-up, and the last run's value.
fn measure<T, F: FnMut() -> T>(reps: usize, mut f: F) -> (Duration, Duration, T) {
    let mut value = black_box(f());
    for _ in 1..WARMUP {
        value = black_box(f());
    }
    let mut times = Vec::with_capacity(reps);
    for _ in 0..reps {
        let t = Instant::now();
        value = black_box(f());
        times.push(t.elapsed());
    }
    times.sort();
    let p95 = times[((reps as f64 * 0.95).ceil() as usize).saturating_sub(1)];
    (times[reps / 2], p95, value)
}

fn ms(d: Duration) -> String {
    format!("{:.3}", d.as_secs_f64() * 1000.0)
}

fn main() {
    let n: u64 = std::env::args()
        .nth(1)
        .map(|s| s.parse().unwrap())
        .unwrap_or(1_000_000);
    let reps: usize = std::env::args()
        .nth(2)
        .map(|s| s.parse().unwrap())
        .unwrap_or(50);
    let threads = std::thread::available_parallelism()
        .map(|p| p.get())
        .unwrap_or(4)
        .min(8);
    let (_, id, body) = schema();

    let t = Instant::now();
    let texts = generate(n);
    let counts: Vec<u64> = texts.iter().map(|t| t.split(' ').count() as u64).collect();
    println!(
        "generated {n} docs in {:?}, {} tokens",
        t.elapsed(),
        counts.iter().sum::<u64>()
    );

    let t = Instant::now();
    let built = build(&texts, &counts, &|_| true, threads);
    println!(
        "built one segment of {n} docs in {:?} ({threads} writer threads + merge)",
        t.elapsed()
    );
    // Word count = Tantivy's token count for these texts (checked on a sample; the full total is
    // asserted against the segment's own total_num_tokens in `build`).
    let mut tokenizer = built.index.tokenizer_for_field(body).unwrap();
    for (text, count) in texts.iter().zip(counts.iter()).take(1000) {
        let mut stream = tokenizer.token_stream(text);
        let mut c = 0u64;
        while stream.advance() {
            c += 1;
        }
        assert_eq!(c, *count);
    }

    let (mut index, dir) = union(&[&built]);
    let fractions: [(&str, u64, bool); 5] = [
        ("100%", 1, false),
        ("50%", 2, false),
        ("10%", 10, false),
        ("1%", 100, false),
        ("1% clustered", 100, true),
    ];
    println!("reps={reps} warmup={WARMUP} k={K}; times in ms as p50/p95");
    for (label, every, clustered) in fractions {
        // The allowed set by doc id (what the alive bitset holds) and by external id (what the
        // library's population predicate reads).
        let allowed_doc: Vec<bool> = if clustered {
            (0..built.ids.len() as u64).map(|d| d < n / every).collect()
        } else {
            built.ids.iter().map(|i| i % every == 0).collect()
        };
        let mut allowed_ext = vec![false; n as usize];
        for (d, a) in allowed_doc.iter().enumerate() {
            if *a {
                allowed_ext[built.ids[d] as usize] = true;
            }
        }
        let allowed_count = allowed_doc.iter().filter(|a| **a).count();
        index = with_alive(&dir, &index, &[allowed_doc.clone()]);
        let searcher = manual_searcher(&index);
        assert_eq!(searcher.num_docs() as usize, allowed_count);
        let parts = parts_in_reader_order(&searcher, &[&built]);
        let reader = &searcher.segment_readers()[0];
        let inverted = reader.inverted_index(body).unwrap();
        let fieldnorms = reader.get_fieldnorms_reader(body).unwrap();
        let head = format!("n={n} allowed={label} ({allowed_count} docs)");

        let (p50, p95, (count, tokens)) = measure(reps, || {
            let allowed_doc = black_box(&allowed_doc);
            let token_counts = black_box(&built.token_counts);
            let count = allowed_doc.iter().filter(|a| **a).count() as u64;
            let tokens: u64 = allowed_doc
                .iter()
                .zip(token_counts.iter())
                .filter(|(a, _)| **a)
                .map(|(_, c)| *c)
                .sum();
            (count, tokens)
        });
        assert_eq!(count as usize, allowed_count);
        println!(
            "{head} stat=N+exact_tokens tokens={tokens} {}/{}",
            ms(p50),
            ms(p95)
        );
        let (p50, p95, norms) = measure(reps, || {
            let allowed_doc = black_box(&allowed_doc);
            let fieldnorms = black_box(&fieldnorms);
            let mut sum = 0u64;
            for (d, a) in allowed_doc.iter().enumerate() {
                if *a {
                    sum += fieldnorms.fieldnorm(d as u32) as u64;
                }
            }
            sum
        });
        println!(
            "{head} stat=fieldnorm_sum sum={norms} {}/{}",
            ms(p50),
            ms(p95)
        );
        let mut packed = vec![0u8; allowed_doc.len().div_ceil(8)];
        for (d, a) in allowed_doc.iter().enumerate() {
            if *a {
                packed[d / 8] |= 1 << (d % 8);
            }
        }
        let (p50, p95, _) = measure(reps, || {
            let mut hasher = DefaultHasher::new();
            hasher.write(black_box(&packed));
            hasher.finish()
        });
        println!(
            "{head} stat=fingerprint bytes={} {}/{}",
            packed.len(),
            ms(p50),
            ms(p95)
        );
        let mut bitset = BitSet::with_max_value(allowed_doc.len() as u32);
        for (d, a) in allowed_doc.iter().enumerate() {
            if *a {
                bitset.insert(d as u32);
            }
        }
        let filter: Box<dyn Query> = Box::new(AllowedFilter(Arc::new(bitset)));
        // The timed df of each term, checked below against `population_stats` of every query.
        let mut timed_df: HashMap<Term, u64> = HashMap::new();
        for term_text in ["w0", "w1", "w10", "w49999"] {
            let term = Term::from_field_text(body, term_text);
            let full_df = inverted.doc_freq(&term).unwrap();
            let (p50, p95, df) = measure(reps, || {
                let allowed_doc = black_box(&allowed_doc);
                let term = black_box(&term);
                let mut df = 0u64;
                if let Some(mut postings) = inverted
                    .read_postings(term, IndexRecordOption::Basic)
                    .unwrap()
                {
                    let mut doc = postings.doc();
                    while doc != TERMINATED {
                        if allowed_doc[doc as usize] {
                            df += 1;
                        }
                        doc = postings.advance();
                    }
                }
                df
            });
            timed_df.insert(term.clone(), df);
            println!(
                "{head} stat=df term={term_text} postings={full_df} df={df} {}/{}",
                ms(p50),
                ms(p95)
            );
        }

        let fresh = if label == "100%" {
            None
        } else {
            let fresh = build(&texts, &counts, &|i| allowed_ext[i as usize], threads);
            Some(fresh)
        };
        for (qlabel, qtext) in QUERIES {
            let query = QueryParser::for_index(&index, vec![body])
                .parse_query(qtext)
                .unwrap();
            let mut terms = Vec::new();
            query.query_terms(&mut |term, _| terms.push(term.clone()));
            let in_p = |i: u64| allowed_ext[i as usize];
            let (s50, s95, _) = measure(reps, || {
                population_stats(black_box(&searcher), &parts, body, black_box(&terms), &in_p).n
            });
            let stats = population_stats(&searcher, &parts, body, &terms, &in_p);
            // The timed component statistics equal the library's, at every fraction.
            assert_eq!(stats.n, count, "timed N");
            assert_eq!(stats.tokens, tokens, "timed token total");
            for term in &terms {
                assert_eq!(stats.df[term], timed_df[term], "timed df of {term:?}");
            }
            let matching = searcher.search(query.as_ref(), &Count).unwrap();
            let (q50, q95, _) = measure(reps, || {
                top(
                    black_box(&searcher),
                    black_box(query.as_ref()),
                    K,
                    black_box(&stats),
                    id,
                )
                .len() as u64
            });
            let hits = top(&searcher, query.as_ref(), K, &stats, id);
            let filtered = BooleanQuery::new(vec![
                (Occur::Must, filter.box_clone()),
                (Occur::Must, query.box_clone()),
            ]);
            let (r50, r95, _) = measure(reps, || {
                top(
                    black_box(&searcher),
                    black_box(&filtered),
                    K,
                    black_box(&stats),
                    id,
                )
                .len() as u64
            });
            let rhits = top(&searcher, &filtered, K, &stats, id);
            let same_filtered =
                hits.len() == rhits.len() && hits.iter().zip(rhits.iter()).all(|(a, b)| a.1 == b.1);
            let mut line = format!(
                "{head} query={qlabel} '{qtext}' alive_matching={matching} population_stats={}/{} search={}/{} filter_driven_search={}/{} filter_same_scores={same_filtered}",
                ms(s50),
                ms(s95),
                ms(q50),
                ms(q95),
                ms(r50),
                ms(r95)
            );
            if let Some(fresh) = &fresh {
                let fs = manual_searcher(&fresh.index);
                let fparts = parts_in_reader_order(&fs, &[fresh]);
                let fquery = QueryParser::for_index(&fresh.index, vec![body])
                    .parse_query(qtext)
                    .unwrap();
                let fstats = population_stats(&fs, &fparts, body, &terms, &|_| true);
                assert_eq!(
                    (fstats.n, fstats.tokens),
                    (stats.n, stats.tokens),
                    "same population"
                );
                let (f50, f95, _) = measure(reps, || {
                    top(
                        black_box(&fs),
                        black_box(fquery.as_ref()),
                        K,
                        black_box(&fstats),
                        id,
                    )
                    .len() as u64
                });
                let fhits = top(&fs, fquery.as_ref(), K, &fstats, id);
                let gap = hits
                    .iter()
                    .zip(fhits.iter())
                    .map(|(a, b)| ((a.1 - b.1) / b.1).abs())
                    .fold(0f32, f32::max);
                let same_ids = hits.iter().map(|h| h.0).eq(fhits.iter().map(|h| h.0));
                line.push_str(&format!(
                    " fresh_index_search={}/{} same_len={} same_ids={same_ids} max_rel_gap={gap:e}",
                    ms(f50),
                    ms(f95),
                    hits.len() == fhits.len()
                ));
            }
            println!("{line}");
        }
    }
}
