//! BM25 statistics over a population (search spec 7.5): N, the exact token total and df counted over
//! the docs of the population only, each segment's population held as a bitset in doc-id space and
//! every pass branchless. Ported from the part 1 probe crate's `population_stats`, which read the
//! population through a doc-to-external-id closure and a HashMap (2.2x slower, spec 7.5). Every pass
//! checks the call's cancel flag (spec 9).
use std::collections::HashMap;
use std::sync::Mutex;

use tantivy::query::Bm25StatisticsProvider;
use tantivy::schema::{Field, IndexRecordOption};
use tantivy::{DocSet, Searcher, TantivyError, Term, COLLECT_BLOCK_BUFFER_LEN};

use crate::controls::{Controls, POSTINGS_PER_CHECK, STATS_DOCS_PER_CHECK};
use crate::Result;

/// The population bitsets of a call, one per segment reader of `searcher`, in reader order.
pub struct PopulationStats<'a> {
    searcher: &'a Searcher,
    field: Field,
    population: Vec<&'a [u64]>,
    controls: &'a Controls<'a>,
    n: u64,
    tokens: u64,
    df: Mutex<HashMap<Term, u64>>,
}

/// Number of set bits of `words` (already masked to the segment's doc count).
pub fn popcount(words: &[u64]) -> u64 {
    words.iter().map(|w| w.count_ones() as u64).sum()
}

/// The sum of `counts[d]` over the docs d set in `words`, branchless: each count is multiplied by its
/// bit.
pub fn token_total(words: &[u64], counts: &[u32]) -> u64 {
    let mut sum = 0u64;
    for (w, chunk) in words.iter().zip(counts.chunks(64)) {
        for (bit, c) in chunk.iter().enumerate() {
            sum += (*c as u64) * ((w >> bit) & 1);
        }
    }
    sum
}

/// The number of docs of `words` among `docs`, branchless. A doc id past `words` counts 0.
#[inline]
fn count_in(words: &[u64], docs: &[u32]) -> u64 {
    let mut c = 0u64;
    for d in docs {
        let w = words.get((*d >> 6) as usize).copied().unwrap_or(0);
        c += (w >> (*d & 63)) & 1;
    }
    c
}

const WORDS_PER_CHECK: usize = STATS_DOCS_PER_CHECK / 64;
const BUFFERS_PER_CHECK: usize = POSTINGS_PER_CHECK / COLLECT_BLOCK_BUFFER_LEN;

impl<'a> PopulationStats<'a> {
    /// `population[i]` is the population of `searcher.segment_readers()[i]`, masked to its doc count;
    /// `counts[i]` are that segment's exact token counts.
    pub fn new(
        searcher: &'a Searcher,
        field: Field,
        population: Vec<&'a [u64]>,
        counts: &[&[u32]],
        controls: &'a Controls<'a>,
    ) -> Result<Self> {
        let mut n = 0u64;
        let mut tokens = 0u64;
        for (w, c) in population.iter().zip(counts) {
            controls.check()?;
            for (i, chunk) in w.chunks(WORDS_PER_CHECK).enumerate() {
                let lo = i * STATS_DOCS_PER_CHECK;
                let hi = (lo + chunk.len() * 64).min(c.len());
                n += popcount(chunk);
                tokens += token_total(chunk, c.get(lo..hi).unwrap_or(&[]));
                controls.check()?;
            }
        }
        Ok(PopulationStats { searcher, field, population, controls, n, tokens, df: Mutex::new(HashMap::new()) })
    }

    pub fn num_docs(&self) -> u64 {
        self.n
    }

    pub fn num_tokens(&self) -> u64 {
        self.tokens
    }

    /// df over the population: every posting of the term in every segment, no skipping, clamped to
    /// N (a crafted segment can repeat a doc id in its postings; Tantivy asserts df <= N).
    fn compute_df(&self, term: &Term) -> tantivy::Result<u64> {
        let cancelled = || TantivyError::InternalError("search_cancelled".to_string());
        let mut count = 0u64;
        let mut buf = [0u32; COLLECT_BLOCK_BUFFER_LEN];
        for (reader, words) in self.searcher.segment_readers().iter().zip(&self.population) {
            if self.controls.is_cancelled() {
                return Err(cancelled());
            }
            let inverted = reader.inverted_index(term.field())?;
            // The postings ignore the segment's alive bitset (the delete file): this counts the
            // population, not the alive docs.
            if let Some(mut postings) = inverted.read_postings(term, IndexRecordOption::Basic)? {
                let mut buffers = 0usize;
                loop {
                    let got = postings.fill_buffer(&mut buf);
                    count += count_in(words, &buf[..got]);
                    if got < COLLECT_BLOCK_BUFFER_LEN {
                        break;
                    }
                    buffers += 1;
                    if buffers % BUFFERS_PER_CHECK == 0 && self.controls.is_cancelled() {
                        return Err(cancelled());
                    }
                }
            }
        }
        Ok(count.min(self.n))
    }

    /// Computes and caches df of `terms` up front, so that cancellation and the budget surface as
    /// this library's errors rather than through Tantivy's weight construction.
    pub fn prefetch(&self, terms: &[Term]) -> Result<()> {
        for term in terms {
            self.controls.check()?;
            self.controls.charge(term.serialized_value_bytes().len() as u64 + 64)?;
            let df = self.compute_df(term).map_err(|e| {
                if self.controls.is_cancelled() {
                    crate::Error::new("search_cancelled", "the call was cancelled")
                } else {
                    crate::Error::internal(&format!("df: {e}"))
                }
            })?;
            if let Ok(mut m) = self.df.lock() {
                m.insert(term.clone(), df);
            }
        }
        Ok(())
    }
}

impl Bm25StatisticsProvider for PopulationStats<'_> {
    fn total_num_tokens(&self, field: Field) -> tantivy::Result<u64> {
        if field != self.field {
            return Err(TantivyError::InvalidArgument("search on another field".to_string()));
        }
        Ok(self.tokens)
    }

    fn total_num_docs(&self) -> tantivy::Result<u64> {
        Ok(self.n)
    }

    /// df is computed on first use per term when `prefetch` did not list it (a query may name terms
    /// Tantivy finds itself), and cached for the call. It never exceeds N.
    fn doc_freq(&self, term: &Term) -> tantivy::Result<u64> {
        if let Some(df) = self.df.lock().ok().and_then(|m| m.get(term).copied()) {
            return Ok(df);
        }
        let df = self.compute_df(term)?;
        if let Ok(mut m) = self.df.lock() {
            m.insert(term.clone(), df);
        }
        Ok(df)
    }
}
