//! Q5: two segments built separately, opened as one index, with per-segment alive bitsets and our own
//! BM25 statistics, give the same top-k and scores (1e-6 relative) as a fresh index over exactly the
//! population, filtered to the allowed docs. Checked for both populations of spec 7.5.
use tantivy_probe::*;

fn corpus(n: u64, seed: u64) -> Vec<(u64, String)> {
    corpus_up_to(n, seed, 40)
}

/// Docs of 3 to max_len - 1 tokens. Tantivy's fieldnorm codes are exact up to 40 tokens
/// (`FIELD_NORMS_TABLE`: 0..=40, then 42, 44, ...), so the brief's corpus (3..40) never quantizes.
fn corpus_up_to(n: u64, seed: u64, max_len: usize) -> Vec<(u64, String)> {
    use rand::{rngs::StdRng, Rng, SeedableRng};
    let words = ["lake", "house", "duck", "quack", "search", "table", "row", "snapshot", "file", "index"];
    let mut rng = StdRng::seed_from_u64(seed);
    (0..n)
        .map(|i| {
            let len = rng.gen_range(3..max_len as i32); // i32, as the brief's literal range inferred: same corpus
            let text: Vec<&str> = (0..len).map(|_| words[(rng.gen::<f64>().powi(3) * words.len() as f64) as usize]).collect();
            (i, text.join(" "))
        })
        .collect()
}

fn check(population: Population) {
    check_on(population, corpus(4000, 7));
}

/// `got` (the union's top k) against `want_all` (every allowed hit of the reference, sorted by score
/// descending then id): same length as the reference's top k, scores within 1e-6 relative position
/// by position, and the same ids except among docs tied with the k-th score, where a top-k may keep
/// any of them; each such id must still be a reference hit with that score. Returns whether such a
/// tie crossed the k boundary.
fn assert_same_top(got: &[(u64, f32)], want_all: &[(u64, f32)], k: usize, what: &str) -> bool {
    let want = &want_all[..want_all.len().min(k)];
    assert_eq!(got.len(), want.len(), "{what}: hit counts differ");
    let rel = |x: f32, y: f32| ((x - y) / y).abs();
    let Some(last) = want.last().map(|w| w.1) else { return false };
    let mut boundary_tie = false;
    for (g, w) in got.iter().zip(want.iter()) {
        assert!(rel(g.1, w.1) < 1e-6, "{what}: score at doc {} differs: {} vs {}", g.0, g.1, w.1);
        if rel(w.1, last) < 1e-6 {
            let hit = want_all.iter().find(|h| h.0 == g.0).unwrap_or_else(|| panic!("{what}: doc {} is no reference hit", g.0));
            assert!(rel(g.1, hit.1) < 1e-6, "{what}: doc {} scored {} vs {}", g.0, g.1, hit.1);
            boundary_tie |= g.0 != w.0;
        } else {
            assert_eq!(g.0, w.0, "{what}: rank order differs: got {got:?} want {want:?}");
        }
    }
    let tied_beyond = want_all.len() > k && rel(want_all[k].1, last) < 1e-6;
    boundary_tie || tied_beyond
}

/// A corpus where the rare term `heron` is appended to about 1% of the docs.
fn corpus_with_rare(n: u64, seed: u64) -> Vec<(u64, String)> {
    corpus(n, seed)
        .into_iter()
        .map(|(i, t)| if i % 97 == 0 { (i, format!("{t} heron")) } else { (i, t) })
        .collect()
}

fn check_on(population: Population, docs: Vec<(u64, String)>) {
    check_query(population, &docs, "lake OR quack");
}

fn check_query(population: Population, docs: &[(u64, String)], q: &str) {
    let (a, b) = docs.split_at(2500);
    let a_ref: Vec<(u64, &str)> = a.iter().map(|(i, t)| (*i, t.as_str())).collect();
    let b_ref: Vec<(u64, &str)> = b.iter().map(|(i, t)| (*i, t.as_str())).collect();
    let seg_a = build_segment(&a_ref);
    let seg_b = build_segment(&b_ref);

    let live = |id: u64| id % 13 != 0;             // dead docs: deletes and rows above S
    let allowed = |id: u64| id % 4 == 0 || id % 9 == 0; // the governed input
    let in_population = |id: u64| match population {
        Population::Table => live(id),
        Population::Allowed => live(id) && allowed(id),
    };

    let got = search_union(&[&seg_a, &seg_b], q, 10, &live, &allowed, &in_population);
    let want_all = search_reference(docs, q, docs.len(), &in_population, &allowed);
    let tie = assert_same_top(&got, &want_all, 10, &format!("{population:?} `{q}`"));
    println!("{population:?} `{q}`: {} hits in the top 10 of {} allowed hits, tie at the k boundary {tie}", got.len(), want_all.len());
}

#[test]
fn table_population_matches_a_fresh_index() {
    check(Population::Table);
}

#[test]
fn allowed_population_matches_a_fresh_index() {
    check(Population::Allowed);
}

/// Both populations on a rare term (single-term block-WAND), a rare term ORed with a frequent one,
/// the rarest vocabulary word, and an AND (block_wand_intersection).
#[test]
fn both_populations_match_on_rare_and_and_queries() {
    let docs = corpus_with_rare(4000, 7);
    for population in [Population::Table, Population::Allowed] {
        for q in ["heron", "heron OR quack", "index", "lake AND quack", "heron AND lake"] {
            check_query(population, &docs, q);
        }
    }
}

/// The same two checks on docs of up to 2000 tokens, where fieldnorms quantize.
#[test]
fn both_populations_match_on_long_docs() {
    check_on(Population::Table, corpus_up_to(4000, 7, 2000));
    check_on(Population::Allowed, corpus_up_to(4000, 7, 2000));
}

/// What spec 7.5 rev 4 prescribes (avgdl from the sum of quantized fieldnorms) against the
/// reference, on long docs: the exact-total provider matches (test above), the quantized one does not.
#[test]
fn quantized_avgdl_does_not_match_a_fresh_index_on_long_docs() {
    use tantivy::query::QueryParser;
    let docs = corpus_up_to(4000, 7, 2000);
    let (a, b) = docs.split_at(2500);
    let a_ref: Vec<(u64, &str)> = a.iter().map(|(i, t)| (*i, t.as_str())).collect();
    let b_ref: Vec<(u64, &str)> = b.iter().map(|(i, t)| (*i, t.as_str())).collect();
    let seg_a = build_segment(&a_ref);
    let seg_b = build_segment(&b_ref);
    let live = |id: u64| id % 13 != 0;
    let allowed = |id: u64| id % 4 == 0 || id % 9 == 0;
    for population in [Population::Table, Population::Allowed] {
        let in_p = |id: u64| match population {
            Population::Table => live(id),
            Population::Allowed => live(id) && allowed(id),
        };
        let (_, id, body) = schema();
        let parts = [&seg_a, &seg_b];
        let (index, dir) = union(&parts);
        let alive: Vec<Vec<bool>> = parts.iter().map(|p| p.ids.iter().map(|i| live(*i) && allowed(*i)).collect()).collect();
        let index = with_alive(&dir, &index, &alive);
        let searcher = index.reader().unwrap().searcher();
        let ordered = parts_in_reader_order(&searcher, &parts);
        let query = QueryParser::for_index(&index, vec![body]).parse_query("lake OR quack").unwrap();
        let mut terms = Vec::new();
        query.query_terms(&mut |t, _| terms.push(t.clone()));
        let mut stats = population_stats(&searcher, &ordered, body, &terms, &in_p);
        let exact_tokens = stats.tokens;
        let mut quantized_tokens = 0u64;
        for (reader, part) in searcher.segment_readers().iter().zip(ordered.iter()) {
            let norms = reader.get_fieldnorms_reader(body).unwrap();
            for (doc, ext) in part.ids.iter().enumerate() {
                if in_p(*ext) { quantized_tokens += norms.fieldnorm(doc as u32) as u64; }
            }
        }
        stats.tokens = quantized_tokens;
        let got = top(&searcher, query.as_ref(), 10, &stats, id);
        let want = search_reference(&docs, "lake OR quack", 10, &in_p, &allowed);
        let same_order = got.iter().zip(want.iter()).all(|(g, w)| g.0 == w.0);
        let max_rel = got.iter().zip(want.iter()).map(|(g, w)| ((g.1 - w.1) / w.1).abs()).fold(0f32, f32::max);
        println!("{population:?} long docs: exact tokens {exact_tokens}, quantized {quantized_tokens} ({:.3}% lower); quantized avgdl vs fresh index: same top-10 order {same_order}, max relative score gap {max_rel:e}",
            100.0 * (exact_tokens - quantized_tokens) as f64 / exact_tokens as f64);
        assert!(max_rel > 1e-6);
    }
}

#[test]
fn quantized_fieldnorm_total_on_long_docs() {
    let docs = corpus_up_to(4000, 7, 2000);
    let refs: Vec<(u64, &str)> = docs.iter().map(|(i, t)| (*i, t.as_str())).collect();
    let built = build_segment(&refs);
    let (_, _, body) = schema();
    let searcher = built.index.reader().unwrap().searcher();
    let reader = &searcher.segment_readers()[0];
    let norms = reader.get_fieldnorms_reader(body).unwrap();
    let quantized: u64 = (0..reader.max_doc()).map(|d| norms.fieldnorm(d) as u64).sum();
    let exact: u64 = built.token_counts.iter().sum();
    let tantivy_total = reader.inverted_index(body).unwrap().total_num_tokens();
    println!("long docs (3..2000 tokens): exact tokens {exact}, sum of quantized fieldnorms {quantized}, tantivy total_num_tokens {tantivy_total}");
    assert_eq!(exact, tantivy_total);
}

#[test]
fn quantized_fieldnorm_total_differs_from_the_exact_total() {
    let docs = corpus(4000, 7);
    let refs: Vec<(u64, &str)> = docs.iter().map(|(i, t)| (*i, t.as_str())).collect();
    let built = build_segment(&refs);
    let (_, _, body) = schema();
    let searcher = built.index.reader().unwrap().searcher();
    let reader = &searcher.segment_readers()[0];
    let norms = reader.get_fieldnorms_reader(body).unwrap();
    let quantized: u64 = (0..reader.max_doc()).map(|d| norms.fieldnorm(d) as u64).sum();
    let exact: u64 = built.token_counts.iter().sum();
    let tantivy_total = reader.inverted_index(body).unwrap().total_num_tokens();
    println!("exact tokens {exact}, sum of quantized fieldnorms {quantized}, tantivy total_num_tokens {tantivy_total}");
    assert!(exact > 0);
}

/// Negative control: on the same union with the same alive bitsets, Tantivy's own statistics
/// (the Searcher, which counts max_doc and every token of the segment, dead docs included) do not
/// give the reference scores, so the matching scores above come from PopulationStats.
#[test]
fn default_statistics_do_not_match_the_reference() {
    use tantivy::collector::TopDocs;
    use tantivy::query::QueryParser;
    use tantivy::schema::Value;
    let docs = corpus(4000, 7);
    let (a, b) = docs.split_at(2500);
    let a_ref: Vec<(u64, &str)> = a.iter().map(|(i, t)| (*i, t.as_str())).collect();
    let b_ref: Vec<(u64, &str)> = b.iter().map(|(i, t)| (*i, t.as_str())).collect();
    let seg_a = build_segment(&a_ref);
    let seg_b = build_segment(&b_ref);
    let live = |id: u64| id % 13 != 0;
    let allowed = |id: u64| id % 4 == 0 || id % 9 == 0;
    let in_population = |id: u64| live(id) && allowed(id);
    let (_, id, body) = schema();
    let parts = [&seg_a, &seg_b];
    let (index, dir) = union(&parts);
    let alive: Vec<Vec<bool>> = parts.iter().map(|p| p.ids.iter().map(|i| live(*i) && allowed(*i)).collect()).collect();
    let index = with_alive(&dir, &index, &alive);
    let searcher = index.reader().unwrap().searcher();
    let expected_alive = docs.iter().filter(|(i, _)| in_population(*i)).count() as u64;
    println!("union: max_doc {} num_docs {} (alive expected {expected_alive})",
        searcher.segment_readers().iter().map(|r| r.max_doc() as u64).sum::<u64>(), searcher.num_docs());
    assert_eq!(searcher.num_docs(), expected_alive, "the delete files are honored");
    let query = QueryParser::for_index(&index, vec![body]).parse_query("lake OR quack").unwrap();
    let hits = searcher.search(query.as_ref(), &TopDocs::with_limit(10).order_by_score()).unwrap();
    let default: Vec<(u64, f32)> = hits.into_iter().map(|(s, addr)| {
        let d: tantivy::TantivyDocument = searcher.doc(addr).unwrap();
        (d.get_first(id).unwrap().as_u64().unwrap(), s)
    }).collect();
    let want = search_reference(&docs, "lake OR quack", 10, &in_population, &allowed);
    let max_rel = default.iter().zip(want.iter()).map(|(g, w)| ((g.1 - w.1) / w.1).abs()).fold(0f32, f32::max);
    println!("default statistics vs reference: max relative score gap {max_rel}");
    assert!(max_rel > 1e-6);
}

/// Block-WAND with the alive bitsets as delete files: drive the same `for_each_pruning` the top-k
/// score collector drives (Tantivy 0.27 `SortBySimilarityScore::collect_segment_top_k`: a dead doc
/// returns the current threshold unchanged) and count the docs passed to the callback, against the
/// docs that match the query, then check the pruned top k against the unpruned one.
fn pruning(population: Population, live_mod: u64, allowed_pred: &dyn Fn(u64) -> bool, n: u64, q: &str) {
    use tantivy::query::{EnableScoring, QueryParser};
    let docs = corpus(n, 11);
    let half = (n / 2) as usize;
    let (a, b) = docs.split_at(half);
    let a_ref: Vec<(u64, &str)> = a.iter().map(|(i, t)| (*i, t.as_str())).collect();
    let b_ref: Vec<(u64, &str)> = b.iter().map(|(i, t)| (*i, t.as_str())).collect();
    let seg_a = build_segment(&a_ref);
    let seg_b = build_segment(&b_ref);
    let live = |id: u64| id % live_mod != 0;
    let in_p = |id: u64| match population {
        Population::Table => live(id),
        Population::Allowed => live(id) && allowed_pred(id),
    };
    let (_, _, body) = schema();
    let parts = [&seg_a, &seg_b];
    let (index, dir) = union(&parts);
    let alive: Vec<Vec<bool>> = parts.iter().map(|p| p.ids.iter().map(|i| live(*i) && allowed_pred(*i)).collect()).collect();
    let index = with_alive(&dir, &index, &alive);
    let searcher = index.reader().unwrap().searcher();
    let ordered = parts_in_reader_order(&searcher, &parts);
    let query = QueryParser::for_index(&index, vec![body]).parse_query(q).unwrap();
    let mut terms = Vec::new();
    query.query_terms(&mut |t, _| terms.push(t.clone()));
    let stats = population_stats(&searcher, &ordered, body, &terms, &in_p);
    let weight = query.weight(EnableScoring::enabled_from_statistics_provider(&stats, &searcher)).unwrap();
    let k = 10;
    // passed: docs block-WAND hands to the collector callback, which it does only for a doc whose
    // score beats the current threshold (block_wand_union.rs:210, block_wand_intersection.rs:169),
    // so it is a lower bound on the docs block-WAND scores; the true count is not measured (the
    // term scorers block-WAND drives are built inside BooleanWeight, with no public hook).
    let (mut passed, mut matching, mut alive_matching) = (0u64, 0u64, 0u64);
    let mut all_alive: Vec<(u64, f32)> = Vec::new(); // every alive hit, unpruned
    for (reader, part) in searcher.segment_readers().iter().zip(ordered.iter()) {
        let alive_bits = reader.alive_bitset().expect("a delete file per segment");
        weight.for_each(reader, &mut |doc, score| {
            matching += 1;
            if alive_bits.is_alive(doc) {
                alive_matching += 1;
                all_alive.push((part.ids[doc as usize], score));
            }
        }).unwrap();
        let mut heap: Vec<f32> = Vec::new(); // the k best alive scores of this segment
        let mut threshold = f32::MIN;
        weight.for_each_pruning(f32::MIN, reader, &mut |doc, score| {
            passed += 1;
            if alive_bits.is_deleted(doc) { return threshold; }
            heap.push(score);
            heap.sort_by(|x, y| y.partial_cmp(x).unwrap());
            heap.truncate(k);
            if heap.len() == k { threshold = heap[k - 1]; }
            threshold
        }).unwrap();
    }
    let got = top(&searcher, query.as_ref(), k, &stats, schema().1);
    // Exactness at this size: the pruned top k against the top k of every alive hit.
    all_alive.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap().then(a.0.cmp(&b.0)));
    let tie = assert_same_top(&got, &all_alive, k, &format!("{population:?} n={n} `{q}`"));
    println!("{population:?} n={n} `{q}` alive {} of {}: matching {matching}, alive matching {alive_matching}, passed to the collector (score above threshold, a lower bound on docs scored) {passed} ({:.1}% of matching); top-{k} equals the unpruned top-{k} (tie at the k boundary {tie})",
        searcher.num_docs(), n, 100.0 * passed as f64 / matching as f64);
    assert_eq!(got.len(), k);
}

#[test]
fn block_wand_prunes_with_alive_bitsets_as_delete_files() {
    let allowed = |id: u64| id % 4 == 0 || id % 9 == 0; // about 33%
    let allowed_10 = |id: u64| id % 10 == 0;
    let allowed_1 = |id: u64| id % 100 == 0;
    let everyone = |_: u64| true;
    let or = "lake OR quack";
    pruning(Population::Table, 13, &everyone, 200_000, or);
    pruning(Population::Table, 13, &allowed, 200_000, or);
    pruning(Population::Allowed, 13, &allowed, 200_000, or);
    pruning(Population::Allowed, 13, &allowed_10, 200_000, or);
    pruning(Population::Allowed, 13, &allowed_1, 200_000, or);
    pruning(Population::Allowed, 13, &allowed_10, 200_000, "lake AND quack");
    pruning(Population::Allowed, 13, &allowed_1, 200_000, "lake AND quack");
}
