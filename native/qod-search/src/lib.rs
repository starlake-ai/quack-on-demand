//! qod_search_core, Phase 0 link probe (spec Q2): one C entry point that keeps Tantivy linked.
use tantivy::collector::Count;
use tantivy::query::QueryParser;
use tantivy::schema::{Schema, TEXT};
use tantivy::{doc, Index, IndexWriter};

#[no_mangle]
pub extern "C" fn qod_search_probe(docs: u64) -> u64 {
    let mut builder = Schema::builder();
    let body = builder.add_text_field("body", TEXT);
    let index = Index::create_in_ram(builder.build());
    let mut writer: IndexWriter = match index.writer_with_num_threads(1, 15_000_000) {
        Ok(w) => w,
        Err(_) => return u64::MAX,
    };
    for i in 0..docs {
        let text = if i % 3 == 0 { "duck lake search" } else { "quack house" };
        if writer.add_document(doc!(body => text)).is_err() {
            return u64::MAX;
        }
    }
    if writer.commit().is_err() {
        return u64::MAX;
    }
    let searcher = match index.reader() {
        Ok(r) => r.searcher(),
        Err(_) => return u64::MAX,
    };
    let query = match QueryParser::for_index(&index, vec![body]).parse_query("lake") {
        Ok(q) => q,
        Err(_) => return u64::MAX,
    };
    searcher.search(&query, &Count).map(|n| n as u64).unwrap_or(u64::MAX)
}
