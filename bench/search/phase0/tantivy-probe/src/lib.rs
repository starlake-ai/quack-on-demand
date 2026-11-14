//! Search Phase 0 probe (spec Q5): separately built Tantivy segments opened as one index, filtered by
//! per-segment alive bitsets written as delete files, scored with a statistics provider over a chosen
//! population. Exact token counts per doc are kept at build (a column of the segment in the real
//! design), because Tantivy's own average field length uses exact totals, not quantized fieldnorms.
//!
//! Written against tantivy 0.27.0. Adaptations from the 0.24 / 0.25 API the brief targeted:
//! `SegmentMeta::list_files` is gone (replaced by `IndexMeta::list_segment_files`, which also names
//! files a segment may not have, so only existing ones are copied); `IndexMeta` gained
//! `persisted_custom_extensions`; `TopDocs::with_limit` is no longer a collector on its own and needs
//! `.order_by_score()`; segment files are copied with `atomic_read` and the delete file written
//! through the managed directory's `open_write` + `terminate`, since every component carries a
//! footer that `open_read` strips and `atomic_write` does not add; `BitSet` is re-exported as
//! `tantivy::BitSet`, so no direct `tantivy-common` dependency; `SegmentComponent` lives at `tantivy::index::SegmentComponent`.
use std::collections::HashMap;
use std::path::Path;
use tantivy::collector::TopDocs;
use tantivy::directory::{Directory, RamDirectory, TerminatingWrite};
use tantivy::index::SegmentComponent;
use tantivy::query::{Bm25StatisticsProvider, QueryParser};
use tantivy::schema::{Field, IndexRecordOption, Schema, Value, FAST, STORED, TEXT};
use tantivy::{doc, BitSet, DocSet, Index, IndexMeta, Searcher, TantivyDocument, Term, TERMINATED};

#[derive(Clone, Copy, Debug)]
pub enum Population {
    Table,
    Allowed,
}

pub struct Built {
    pub index: Index,
    pub ids: Vec<u64>,          // doc id -> external id
    pub token_counts: Vec<u64>, // doc id -> exact token count of the body field
}

pub fn schema() -> (Schema, Field, Field) {
    let mut builder = Schema::builder();
    let id = builder.add_u64_field("id", FAST | STORED);
    let body = builder.add_text_field("body", TEXT);
    (builder.build(), id, body)
}

/// One segment: a single-threaded writer and one commit.
pub fn build_segment(docs: &[(u64, &str)]) -> Built {
    let (schema, id, body) = schema();
    let index = Index::create_in_ram(schema);
    let mut writer: tantivy::IndexWriter = index.writer_with_num_threads(1, 50_000_000).unwrap();
    let mut tokenizer = index.tokenizer_for_field(body).unwrap();
    let mut token_counts = Vec::with_capacity(docs.len());
    for (doc_id, text) in docs {
        writer.add_document(doc!(id => *doc_id, body => *text)).unwrap();
        let mut stream = tokenizer.token_stream(text);
        let mut n = 0u64;
        while stream.advance() {
            n += 1;
        }
        token_counts.push(n);
    }
    writer.commit().unwrap();
    let metas = index.searchable_segment_metas().unwrap();
    assert_eq!(metas.len(), 1, "expected one segment per build");
    Built { index, ids: docs.iter().map(|d| d.0).collect(), token_counts }
}

/// One index over every part's segment: the parts' files copied into one directory and a meta.json
/// listing every segment. The real design does this with a Directory over DuckDB's FileSystem.
pub fn union(parts: &[&Built]) -> (Index, RamDirectory) {
    let dir = RamDirectory::create();
    let mut segments = Vec::new();
    for part in parts {
        let source = part.index.directory();
        let metas = part.index.load_metas().unwrap();
        for file in metas.list_segment_files() {
            // list_segment_files names every component a segment may have (delete, temp store,
            // every plugin extension); copy the ones this segment has.
            if !source.exists(&file).unwrap() {
                continue;
            }
            // atomic_read returns the stored bytes footer included (the managed directory's
            // open_read strips and checks the footer, and the union's own open_read expects it).
            let bytes = source.atomic_read(&file).unwrap();
            dir.atomic_write(&file, &bytes).unwrap();
        }
        segments.extend(metas.segments);
    }
    let first = parts[0].index.load_metas().unwrap();
    let meta = IndexMeta {
        index_settings: first.index_settings,
        persisted_custom_extensions: first.persisted_custom_extensions,
        segments,
        schema: first.schema,
        opstamp: first.opstamp,
        payload: None,
    };
    dir.atomic_write(Path::new("meta.json"), serde_json::to_string(&meta).unwrap().as_bytes())
        .unwrap();
    (Index::open(dir.clone()).unwrap(), dir)
}

/// Per-segment alive bitsets (live AND allowed), written as Tantivy delete files, so the top-k
/// collector's block-WAND pruning skips the other docs exactly as it skips deleted ones.
///
/// `alive[i]` is the bitset of the i-th segment of meta.json, which `union` writes in the parts'
/// order (not the searcher's reader order: see `parts_in_reader_order`), one flag per doc. It
/// rewrites `dir` in place (a new `.del` file per segment and a new meta.json at opstamp + 1) and
/// returns a fresh Index over it; `index` is stale afterwards and must not be searched again.
pub fn with_alive(dir: &RamDirectory, index: &Index, alive: &[Vec<bool>]) -> Index {
    let mut metas = index.load_metas().unwrap();
    assert_eq!(metas.segments.len(), alive.len(), "one alive bitset per segment");
    let opstamp = metas.opstamp + 1;
    let mut segments = Vec::new();
    for (meta, alive) in metas.segments.iter().zip(alive.iter()) {
        assert_eq!(meta.max_doc() as usize, alive.len(), "one alive flag per doc");
        let mut bitset = BitSet::with_max_value(alive.len() as u32);
        let mut deleted = 0u32;
        for (doc, keep) in alive.iter().enumerate() {
            if *keep {
                bitset.insert(doc as u32);
            } else {
                deleted += 1;
            }
        }
        let with_deletes = meta.clone().with_delete_meta(deleted, opstamp);
        let path = with_deletes.relative_path(SegmentComponent::Delete);
        // Written through the index's managed directory, as Tantivy's own delete path does, so
        // the file carries the footer every component is opened with.
        let mut file = index.directory().open_write(&path).unwrap();
        tantivy::fastfield::write_alive_bitset(&bitset, &mut file).unwrap();
        file.terminate().unwrap();
        segments.push(with_deletes);
    }
    metas.segments = segments;
    metas.opstamp = opstamp;
    dir.atomic_write(Path::new("meta.json"), serde_json::to_string(&metas).unwrap().as_bytes())
        .unwrap();
    Index::open(dir.clone()).unwrap()
}

/// BM25 statistics over a population: N, exact token total, and df counted by walking each term's
/// postings in every segment and keeping the docs in the population.
pub struct PopulationStats {
    pub n: u64,
    pub tokens: u64,
    pub df: HashMap<Term, u64>,
}

impl Bm25StatisticsProvider for PopulationStats {
    fn total_num_tokens(&self, _field: Field) -> tantivy::Result<u64> {
        Ok(self.tokens)
    }
    fn total_num_docs(&self) -> tantivy::Result<u64> {
        Ok(self.n)
    }
    fn doc_freq(&self, term: &Term) -> tantivy::Result<u64> {
        Ok(*self.df.get(term).unwrap_or(&0))
    }
}

/// N and the exact token total come from the per-doc counts kept at build; df walks the full
/// postings of each term (no block skipping), keeping the docs of the population. `parts[i]` must
/// be the part behind `searcher.segment_readers()[i]`.
pub fn population_stats(
    searcher: &Searcher,
    parts: &[&Built],
    body: Field,
    terms: &[Term],
    in_p: &dyn Fn(u64) -> bool,
) -> PopulationStats {
    let mut n = 0;
    let mut tokens = 0;
    for part in parts {
        for (doc, id) in part.ids.iter().enumerate() {
            if in_p(*id) {
                n += 1;
                tokens += part.token_counts[doc];
            }
        }
    }
    let mut df = HashMap::new();
    for term in terms {
        let mut count = 0;
        for (ord, reader) in searcher.segment_readers().iter().enumerate() {
            let inverted = reader.inverted_index(body).unwrap();
            if let Some(mut postings) =
                inverted.read_postings(term, IndexRecordOption::Basic).unwrap()
            {
                // The postings ignore the segment's alive bitset, so this counts over the
                // population, never over the alive docs.
                let mut doc = postings.doc();
                while doc != TERMINATED {
                    if in_p(parts[ord].ids[doc as usize]) {
                        count += 1;
                    }
                    doc = postings.advance();
                }
            }
        }
        df.insert(term.clone(), count);
    }
    PopulationStats { n, tokens, df }
}

/// Maps the segment readers of a union searcher back to the parts, by segment id, since the
/// searcher's reader order is not promised to be meta.json's.
pub fn parts_in_reader_order<'a>(searcher: &Searcher, parts: &[&'a Built]) -> Vec<&'a Built> {
    searcher
        .segment_readers()
        .iter()
        .map(|reader| {
            let id = reader.segment_id();
            *parts
                .iter()
                .find(|p| p.index.searchable_segment_ids().unwrap().contains(&id))
                .expect("every reader comes from a part")
        })
        .collect()
}

/// The top k of `query` on `searcher`, scored with `stats` through Tantivy's score-ordered top-k
/// collector (block-WAND, dead docs dropped by the segment's alive bitset), as (external id,
/// score) sorted by score descending then id. Ids are read from the stored `id` field.
pub fn top(
    searcher: &Searcher,
    query: &dyn tantivy::query::Query,
    k: usize,
    stats: &PopulationStats,
    id: Field,
) -> Vec<(u64, f32)> {
    let hits = searcher
        .search_with_statistics_provider(query, &TopDocs::with_limit(k).order_by_score(), stats)
        .unwrap();
    let mut out: Vec<(u64, f32)> = hits
        .into_iter()
        .map(|(score, addr)| {
            let doc: TantivyDocument = searcher.doc(addr).unwrap();
            (doc.get_first(id).unwrap().as_u64().unwrap(), score)
        })
        .collect();
    out.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap().then(a.0.cmp(&b.0)));
    out
}

/// The design under test: union of segments, alive = live AND allowed, statistics over the population.
pub fn search_union(
    parts: &[&Built],
    q: &str,
    k: usize,
    live: &dyn Fn(u64) -> bool,
    allowed: &dyn Fn(u64) -> bool,
    in_p: &dyn Fn(u64) -> bool,
) -> Vec<(u64, f32)> {
    let (_, id, body) = schema();
    let (index, dir) = union(parts);
    let searcher0 = index.reader().unwrap().searcher();
    let ordered = parts_in_reader_order(&searcher0, parts);
    // with_alive takes the bitsets in meta.json order, which is the parts' order.
    let alive: Vec<Vec<bool>> = parts
        .iter()
        .map(|p| p.ids.iter().map(|i| live(*i) && allowed(*i)).collect())
        .collect();
    let index = with_alive(&dir, &index, &alive);
    let searcher = index.reader().unwrap().searcher();
    let ordered_now = parts_in_reader_order(&searcher, parts);
    assert!(ordered.iter().zip(ordered_now.iter()).all(|(a, b)| std::ptr::eq(*a, *b)));
    let query = QueryParser::for_index(&index, vec![body]).parse_query(q).unwrap();
    let mut terms = Vec::new();
    query.query_terms(&mut |term, _| terms.push(term.clone()));
    let stats = population_stats(&searcher, &ordered_now, body, &terms, in_p);
    top(&searcher, query.as_ref(), k, &stats, id)
}

/// The reference: a fresh index over exactly the population (so Tantivy's own statistics are the
/// population's), its hits filtered to the allowed docs.
pub fn search_reference(
    docs: &[(u64, String)],
    q: &str,
    k: usize,
    in_p: &dyn Fn(u64) -> bool,
    allowed: &dyn Fn(u64) -> bool,
) -> Vec<(u64, f32)> {
    let (_, id, body) = schema();
    let population: Vec<(u64, &str)> =
        docs.iter().filter(|(i, _)| in_p(*i)).map(|(i, t)| (*i, t.as_str())).collect();
    let built = build_segment(&population);
    let searcher = built.index.reader().unwrap().searcher();
    let query = QueryParser::for_index(&built.index, vec![body]).parse_query(q).unwrap();
    let all = searcher
        .search(query.as_ref(), &TopDocs::with_limit(docs.len()).order_by_score())
        .unwrap();
    let mut out: Vec<(u64, f32)> = all
        .into_iter()
        .map(|(score, addr)| {
            let doc: TantivyDocument = searcher.doc(addr).unwrap();
            (doc.get_first(id).unwrap().as_u64().unwrap(), score)
        })
        .filter(|(i, _)| allowed(*i))
        .collect();
    out.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap().then(a.0.cmp(&b.0)));
    out.truncate(k);
    out
}
