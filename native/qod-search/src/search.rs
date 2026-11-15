//! The top k of a full-text query over the per-call union of segments (search spec 5.2, 7.5): each
//! doc filtered by live AND allowed, scored by Tantivy's BM25 with QoD's own statistics over the
//! population, hits ordered by score descending then rowid ascending (invariant 7).
//!
//! The top k is exact: every matching alive doc is scored (no block-max pruning) on Tantivy's
//! seek-free scorer, whose union sums a doc's clause scores in query clause order, so a doc's score
//! is a function of the doc and the population's statistics only (invariants 5 and 7). Tantivy's
//! block-WAND is not used for results: its block bounds are chosen at build time under the
//! segment's own average length and do not bound a block under the population's (review C2, a
//! top-1 doc missed because of hidden rows). It stays available as
//! `top_k_block_wand_measurement_only`, a lower bound on what a scorer with valid bounds could reach.
use std::cmp::Ordering;
use std::collections::{BinaryHeap, HashMap};

use tantivy::collector::TopDocs;
use tantivy::query::{AllQuery, BooleanQuery, EnableScoring, Query, QueryParser, Scorer, Weight};
use tantivy::query_grammar::{Occur, UserInputAst};
use tantivy::schema::Field;
use tantivy::{Index, Searcher, TERMINATED};

use crate::bundle::{self, Segment};
use crate::controls::{Controls, SCORED_DOCS_PER_CHECK};
use crate::directory::{self, words};
use crate::stats::PopulationStats;
use crate::{Error, Result};

/// The largest k a call may ask for.
pub const MAX_K: usize = 10_000;
/// Query limits checked before any parse (spec 9, OWNER RULING 2026-11-15): Tantivy's grammar
/// recurses once per parenthesis (and per `NOT`), so a deep nesting overflows the stack.
pub const MAX_QUERY_BYTES: usize = 4_096;
pub const MAX_QUERY_DEPTH: usize = 32;
pub const MAX_QUERY_TERMS: usize = 64;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Population {
    /// The live docs (spec 7.5): no row policy covers the table.
    Table,
    /// The live AND allowed docs: a row policy covers the table.
    Allowed,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Hit {
    pub rowid: i64,
    pub segment: u32,
    pub doc: u32,
    pub score: f32,
}

/// A column name the per-call schema can carry and the query parser can name: ASCII letters, digits
/// and `_`, not starting with a digit, and not the internal position field. A narrower set than
/// DuckDB's column names (Phase 0).
fn check_field_name(field: &str) -> Result<()> {
    let ok = !field.is_empty()
        && field.len() <= 255
        && field.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'_')
        && !field.as_bytes()[0].is_ascii_digit()
        && field != bundle::POSITION_FIELD;
    if ok {
        Ok(())
    } else {
        Err(Error::invalid(&format!("search field name {field:?} is not supported")))
    }
}

fn parser_error(e: tantivy::query::QueryParserError) -> Error {
    use tantivy::query::QueryParserError as E;
    match e {
        E::FieldDoesNotExist(name) => Error::new("search_field_not_allowed", &name),
        other => Error::new("search_query_invalid", &other.to_string()),
    }
}

fn too_large(detail: &str) -> Error {
    Error::new("search_query_too_large", detail)
}

/// The limits of spec 9, on the raw text: its length, its parenthesis depth (every `(` counted,
/// quoted or not, so over-refusal is possible) and its terms (each maximal run of alphanumeric
/// characters, as the TEXT field's tokenizer splits, and each `*`; a field prefix or an operator word
/// counts too, so this over-counts). Every leaf of the grammar holds one of them, and `NOT` is a run,
/// so the term bound also bounds the grammar's `NOT` recursion.
pub fn check_query_limits(query: &str) -> Result<()> {
    if query.len() > MAX_QUERY_BYTES {
        return Err(too_large(&format!("the query is longer than {MAX_QUERY_BYTES} bytes")));
    }
    let mut depth = 0usize;
    let mut max_depth = 0usize;
    let mut terms = 0usize;
    let mut in_run = false;
    for c in query.chars() {
        match c {
            '(' => {
                depth += 1;
                max_depth = max_depth.max(depth);
            }
            ')' => depth = depth.saturating_sub(1),
            _ => {}
        }
        let alnum = c.is_alphanumeric();
        if alnum && !in_run {
            terms += 1;
        }
        if c == '*' {
            terms += 1;
        }
        in_run = alnum;
    }
    if max_depth > MAX_QUERY_DEPTH {
        return Err(too_large(&format!("the query nests parentheses deeper than {MAX_QUERY_DEPTH}")));
    }
    if terms > MAX_QUERY_TERMS {
        return Err(too_large(&format!("the query holds more than {MAX_QUERY_TERMS} terms")));
    }
    Ok(())
}

/// Whether Tantivy itself sums every doc's clause scores of `ast` in clause order: a group with
/// no required clause (a union, summed in clause order) or with at most two scored clauses (a sum
/// of two is the same either way), recursively, and with no `*` (Tantivy reorders an all-docs
/// clause). Elsewhere its intersection orders the required clauses by postings length, which
/// depends on the segment, hidden rows included.
fn ast_sums_in_clause_order(ast: &UserInputAst) -> bool {
    match ast {
        UserInputAst::Clause(clauses) => {
            let scored: Vec<&(Option<Occur>, UserInputAst)> =
                clauses.iter().filter(|(o, _)| *o != Some(Occur::MustNot)).collect();
            let required = scored.iter().filter(|(o, _)| *o == Some(Occur::Must)).count();
            let all = scored.iter().any(|(_, c)| matches!(c, UserInputAst::Leaf(l) if matches!(**l, tantivy::query_grammar::UserInputLeaf::All)));
            (required == 0 || scored.len() <= 2) && !all && scored.iter().all(|(_, c)| ast_sums_in_clause_order(c))
        }
        UserInputAst::Boost(inner, _) => ast_sums_in_clause_order(inner),
        UserInputAst::Leaf(_) => true,
    }
}

/// The one shape this library cannot score in clause order: a boost on a group whose clauses
/// Tantivy would not sum in clause order (`BoostQuery` exposes no inner query, so the collector
/// cannot score its clauses itself). Refused with `search_query_unsupported`.
fn check_boosted_groups(ast: &UserInputAst) -> Result<()> {
    match ast {
        UserInputAst::Clause(clauses) => clauses.iter().try_for_each(|(_, c)| check_boosted_groups(c)),
        UserInputAst::Boost(inner, _) => {
            if matches!(**inner, UserInputAst::Clause(_)) && !ast_sums_in_clause_order(inner) {
                return Err(Error::new(
                    "search_query_unsupported",
                    "a boost on a group of three or more scored clauses with a required one, or with *",
                ));
            }
            check_boosted_groups(inner)
        }
        UserInputAst::Leaf(_) => Ok(()),
    }
}

/// Refuses a parsed query holding a node the collector cannot score in clause order (Tantivy's
/// query parser does not build one today: this guards a Tantivy upgrade).
fn check_parsed(q: &dyn Query) -> Result<()> {
    if q.downcast_ref::<tantivy::query::DisjunctionMaxQuery>().is_some() {
        return Err(Error::new("search_query_unsupported", "a disjunction-max query"));
    }
    if let Some(b) = q.downcast_ref::<BooleanQuery>() {
        if b.get_minimum_number_should_match() > 1 {
            return Err(Error::new("search_query_unsupported", "a minimum number of optional clauses"));
        }
        return b.clauses().iter().try_for_each(|(_, c)| check_parsed(c.as_ref()));
    }
    Ok(())
}

/// Rule 6 and the query limits: a query may name no field but `field`, must pass the limits of
/// spec 9 (checked before any parse) and must parse. Parsed against a schema holding `field` alone,
/// so any other field name, the internal position field included, is refused.
pub fn check_query(query: &str, field: &str) -> Result<()> {
    check_field_name(field)?;
    check_query_limits(query)?;
    let ast = tantivy::query_grammar::parse_query(query)
        .map_err(|_| Error::new("search_query_invalid", "the query does not parse"))?;
    check_boosted_groups(&ast)?;
    let mut builder = tantivy::schema::Schema::builder();
    let f = builder.add_text_field(field, tantivy::schema::TEXT);
    let index = Index::create_in_ram(builder.build());
    let parsed = QueryParser::for_index(&index, vec![f]).parse_query(query).map_err(parser_error)?;
    check_parsed(parsed.as_ref())
}

/// Whether Tantivy sums `q` in clause order (the parsed-query twin of `ast_sums_in_clause_order`).
/// When it does not, the collector runs the query on its own clause tree (`Plan`, `Cursor`).
fn sums_in_clause_order(q: &dyn Query) -> bool {
    match q.downcast_ref::<BooleanQuery>() {
        None => true,
        Some(b) => {
            let scored: Vec<&(Occur, Box<dyn Query>)> = b.clauses().iter().filter(|(o, _)| *o != Occur::MustNot).collect();
            let required = scored.iter().filter(|(o, _)| *o == Occur::Must).count();
            let all = scored.iter().any(|(_, c)| c.downcast_ref::<AllQuery>().is_some());
            (required == 0 || scored.len() <= 2) && !all && scored.iter().all(|(_, c)| sums_in_clause_order(c.as_ref()))
        }
    }
}

/// The clause tree of a query, built once per call: a leaf is any non-boolean query (its weight is
/// `leaves[i]`), a group keeps its scored clauses in clause order with their role, and its excluded
/// clauses apart (they decide matching only).
enum Plan {
    Leaf(usize),
    /// `*`: matches every doc of the segment; scored by the group rule of `Cursor::score`.
    All,
    Group { scored: Vec<(bool, Plan)>, must_not: Vec<Plan> },
}

fn plan<'q>(q: &'q dyn Query, leaves: &mut Vec<&'q dyn Query>) -> Plan {
    match q.downcast_ref::<BooleanQuery>() {
        Some(b) => {
            let mut scored = Vec::new();
            let mut must_not = Vec::new();
            for (o, c) in b.clauses() {
                let child = plan(c.as_ref(), leaves);
                match o {
                    Occur::Must => scored.push((true, child)),
                    Occur::Should => scored.push((false, child)),
                    Occur::MustNot => must_not.push(child),
                }
            }
            Plan::Group { scored, must_not }
        }
        None if q.downcast_ref::<AllQuery>().is_some() => Plan::All,
        None => {
            leaves.push(q);
            Plan::Leaf(leaves.len() - 1)
        }
    }
}

/// Shared state of one segment's run of the clause tree: the call's controls, a work counter for
/// the cancel checks, and the first error, after which every cursor reads as exhausted.
struct Run<'c> {
    controls: &'c Controls<'c>,
    work: u32,
    err: Option<Error>,
}

impl Run<'_> {
    /// Counts one postings move; false once the run must stop.
    fn tick(&mut self) -> bool {
        self.work = self.work.wrapping_add(1);
        if self.work % SCORED_DOCS_PER_CHECK == 0 && self.err.is_none() && self.controls.is_cancelled() {
            self.err = Some(Error::new("search_cancelled", "the call was cancelled"));
        }
        self.err.is_none()
    }
    fn corrupt(&mut self) -> u32 {
        self.err.get_or_insert_with(|| {
            Error::corrupt("the segment's postings name docs out of order or past its doc count")
        });
        TERMINATED
    }
}

/// A plan instantiated on one segment. Every cursor moves forward only. `seek(target)` positions a
/// cursor on its first matching doc at or after `target`, a group applying Tantivy's boolean rules:
/// every required clause, no excluded one, and with no required clause at least one optional one.
/// `score(doc)` is then the sum, in clause order, of the scores of its scored clauses that match
/// `doc` (the required ones all do). The conjunction is a leapfrog over the required clauses in
/// cost order, which decides only how the cursors move, never the order of the sum: each leaf's
/// postings are decoded once, by its own scorer.
enum Cursor {
    // `cur` caches the scorer's doc (one dynamic call fewer per move).
    Leaf { scorer: Box<dyn Scorer>, max_doc: u32, cur: u32 },
    All { max_doc: u32, cur: u32 },
    Group {
        scored: Vec<(bool, Cursor)>,
        must: Vec<usize>,
        must_not: Vec<Cursor>,
        doc: Option<u32>,
        /// How a `*` among the scored clauses scores (Tantivy's rule, see `score`).
        all_rule: AllRule,
    },
}

/// Tantivy's scoring of `*` in a group (`BooleanWeight` removes every all-docs scorer and then
/// adds back): beside a required clause that is not `*`, every `*` adds 0; with `*` as the only
/// kind of required clause, the group scores 1.0 plus its optional clauses; in a group with no
/// required clause, one or more optional `*` add 1.0 once, after the other optional clauses.
#[derive(Clone, Copy)]
enum AllRule {
    Zero,
    RequiredOne,
    OptionalOne,
    None,
}

impl Cursor {
    fn is_all(&self) -> bool {
        matches!(self, Cursor::All { .. })
    }
}

impl Cursor {
    fn new(plan: &Plan, weights: &[Box<dyn Weight>], reader: &tantivy::SegmentReader) -> tantivy::Result<Cursor> {
        Ok(match plan {
            Plan::Leaf(i) => {
                let scorer = weights[*i].scorer(reader, 1.0)?;
                let cur = scorer.doc();
                Cursor::Leaf { scorer, max_doc: reader.max_doc(), cur }
            }
            Plan::All => Cursor::All { max_doc: reader.max_doc(), cur: 0 },
            Plan::Group { scored, must_not } => {
                let scored: Vec<(bool, Cursor)> = scored
                    .iter()
                    .map(|(m, p)| Cursor::new(p, weights, reader).map(|c| (*m, c)))
                    .collect::<tantivy::Result<_>>()?;
                let mut must: Vec<usize> = (0..scored.len()).filter(|i| scored[*i].0).collect();
                must.sort_by_key(|i| scored[*i].1.cost());
                let must_not = must_not.iter().map(|p| Cursor::new(p, weights, reader)).collect::<tantivy::Result<_>>()?;
                let real_required = scored.iter().any(|(r, c)| *r && !c.is_all());
                let all_required = scored.iter().any(|(r, c)| *r && c.is_all());
                let all_optional = scored.iter().any(|(r, c)| !*r && c.is_all());
                let all_rule = if real_required && (all_required || all_optional) {
                    AllRule::Zero
                } else if all_required {
                    AllRule::RequiredOne
                } else if all_optional {
                    AllRule::OptionalOne
                } else {
                    AllRule::None
                };
                Cursor::Group { scored, must, must_not, doc: None, all_rule }
            }
        })
    }

    /// An estimate of how many docs the cursor yields (to order the leapfrog).
    fn cost(&self) -> u64 {
        match self {
            Cursor::Leaf { scorer, .. } => scorer.size_hint() as u64,
            Cursor::All { max_doc, .. } => *max_doc as u64,
            Cursor::Group { scored, must, .. } => {
                if must.is_empty() {
                    scored.iter().map(|(_, c)| c.cost()).sum()
                } else {
                    must.iter().map(|i| scored[*i].1.cost()).min().unwrap_or(0)
                }
            }
        }
    }

    fn seek(&mut self, target: u32, run: &mut Run) -> u32 {
        match self {
            Cursor::Leaf { scorer, max_doc, cur } => {
                if *cur >= target {
                    if *cur != TERMINATED && *cur >= *max_doc {
                        return run.corrupt();
                    }
                    return *cur;
                }
                if !run.tick() {
                    return TERMINATED;
                }
                // The next doc is the common case of a dense conjunction: advance, which reads the
                // current block, is cheaper than seek, which consults the skip list first.
                let d = if target == *cur + 1 { scorer.advance() } else { scorer.seek(target) };
                if d != TERMINATED && (d < target || d >= *max_doc) {
                    return run.corrupt();
                }
                *cur = d;
                d
            }
            Cursor::All { max_doc, cur } => {
                if target > *cur {
                    *cur = target;
                }
                if *cur >= *max_doc {
                    TERMINATED
                } else {
                    *cur
                }
            }
            Cursor::Group { scored, must, must_not, doc, .. } => {
                if run.err.is_some() {
                    return TERMINATED;
                }
                if let Some(d) = *doc {
                    if d >= target {
                        return d;
                    }
                }
                let mut t = target;
                loop {
                    let c = if !must.is_empty() {
                        let mut c = t;
                        'align: loop {
                            for i in must.iter() {
                                let d = scored[*i].1.seek(c, run);
                                if d == TERMINATED {
                                    c = TERMINATED;
                                    break 'align;
                                }
                                if d > c {
                                    c = d;
                                    continue 'align;
                                }
                            }
                            break;
                        }
                        c
                    } else {
                        let mut m = TERMINATED;
                        for (_, ch) in scored.iter_mut() {
                            m = m.min(ch.seek(t, run));
                        }
                        m
                    };
                    if c == TERMINATED || run.err.is_some() {
                        *doc = Some(TERMINATED);
                        return TERMINATED;
                    }
                    let mut excluded = false;
                    for n in must_not.iter_mut() {
                        if n.seek(c, run) == c {
                            excluded = true;
                            break;
                        }
                    }
                    if !excluded {
                        *doc = Some(c);
                        return c;
                    }
                    t = c + 1;
                }
            }
        }
    }

    /// The score of `doc`, on which the cursor stands.
    fn score(&mut self, doc: u32, run: &mut Run) -> f32 {
        match self {
            Cursor::Leaf { scorer, .. } => scorer.score(),
            // A `*` on its own (outside a group) scores 1.0, like Tantivy's all-docs scorer.
            Cursor::All { .. } => 1.0,
            Cursor::Group { scored, all_rule, .. } => {
                // The clause-order sum of the matching scored clauses that are not `*`...
                let mut sum: Option<f32> = None;
                for (required, ch) in scored.iter_mut() {
                    if ch.is_all() || (!*required && ch.seek(doc, run) != doc) {
                        continue;
                    }
                    let v = ch.score(doc, run);
                    sum = Some(match sum {
                        None => v,
                        Some(t) => t + v,
                    });
                }
                // ...then `*` as Tantivy scores it.
                match (*all_rule, sum) {
                    (AllRule::Zero | AllRule::None, s) => s.unwrap_or(0.0),
                    (AllRule::RequiredOne, None) | (AllRule::OptionalOne, None) => 1.0,
                    (AllRule::RequiredOne, Some(v)) => 1.0 + v,
                    (AllRule::OptionalOne, Some(v)) => v + 1.0,
                }
            }
        }
    }
}

/// Offers `c` to the top-k heap (its greatest element is the worst kept).
fn offer(heap: &mut BinaryHeap<Candidate>, k: usize, c: Candidate) {
    if heap.len() < k {
        heap.push(c);
    } else if heap.peek().map(|worst| c < *worst).unwrap_or(false) {
        heap.pop();
        heap.push(c);
    }
}

/// `words_in` masked to a segment of `n` docs: bits past `n` cleared. Its length must be
/// ceil(n / 64).
fn masked(n: u32, words_in: &[u64]) -> Result<Vec<u64>> {
    let nw = words(n);
    if words_in.len() != nw {
        return Err(Error::invalid(&format!("a bitset of {} words for a segment of {n} docs (expected {nw})", words_in.len())));
    }
    let mut out = words_in.to_vec();
    if n % 64 != 0 {
        if let Some(last) = out.last_mut() {
            *last &= (1u64 << (n % 64)) - 1;
        }
    }
    Ok(out)
}

/// What every search path shares: checked arguments, the bitsets, the union, the statistics.
struct Prepared {
    alive: Vec<Vec<u64>>,
    pop: Vec<Vec<u64>>,
}

#[allow(clippy::too_many_arguments)]
fn prepare(
    segments: &[&Segment],
    live: &[&[u64]],
    allowed: &[&[u64]],
    population: Population,
    query: &str,
    field: &str,
    k1: f32,
    b: f32,
    k: usize,
    controls: &Controls,
) -> Result<Option<Prepared>> {
    check_query(query, field)?;
    if k1 != 1.2 || b != 0.75 {
        return Err(Error::invalid("k1 and b other than 1.2 and 0.75 are not supported (Tantivy 0.27 fixes them)"));
    }
    if k > MAX_K {
        return Err(Error::invalid(&format!("k is above {MAX_K}")));
    }
    if live.len() != segments.len() || !(allowed.is_empty() || allowed.len() == segments.len()) {
        return Err(Error::invalid("one live bitset (and one allowed bitset, or none) per segment"));
    }
    if segments.len() > u32::MAX as usize {
        return Err(Error::invalid("too many segments"));
    }
    controls.check()?;
    if k == 0 || segments.is_empty() {
        return Ok(None);
    }
    // Per input segment: alive = live AND allowed, population = live or live AND allowed.
    let mut alive = Vec::with_capacity(segments.len());
    let mut pop = Vec::with_capacity(segments.len());
    for (i, seg) in segments.iter().enumerate() {
        controls.check()?;
        let n = seg.num_docs();
        controls.charge(3 * 8 * words(n) as u64)?;
        let l = masked(n, live[i])?;
        let a: Vec<u64> = match allowed.get(i) {
            Some(al) => l.iter().zip(masked(n, al)?).map(|(x, y)| x & y).collect(),
            None => l.clone(),
        };
        pop.push(match population {
            Population::Table => l,
            Population::Allowed => a.clone(),
        });
        alive.push(a);
    }
    if alive.iter().all(|a| a.iter().all(|w| *w == 0)) {
        return Ok(None);
    }
    Ok(Some(Prepared { alive, pop }))
}

/// Reader ord -> input segment, by segment id (spec 5.2, never by position).
fn reader_order(searcher: &Searcher, n: usize) -> Result<Vec<usize>> {
    let by_id: HashMap<_, usize> =
        (0..n).map(|i| directory::segment_id_for(i).map(|id| (id, i))).collect::<Result<_>>()?;
    let order: Vec<usize> = searcher
        .segment_readers()
        .iter()
        .map(|r| by_id.get(&r.segment_id()).copied().ok_or_else(|| Error::internal("a reader of no input segment")))
        .collect::<Result<_>>()?;
    if order.len() != n {
        return Err(Error::internal("the union lost a segment"));
    }
    Ok(order)
}

/// A candidate, ordered so that the greatest is the worst: lower score, then higher rowid (then
/// segment and doc, so that the order is total).
#[derive(Clone, Copy, Debug)]
struct Candidate(Hit);

impl Ord for Candidate {
    fn cmp(&self, other: &Self) -> Ordering {
        other
            .0
            .score
            .total_cmp(&self.0.score)
            .then(self.0.rowid.cmp(&other.0.rowid))
            .then(self.0.segment.cmp(&other.0.segment))
            .then(self.0.doc.cmp(&other.0.doc))
    }
}
impl PartialOrd for Candidate {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}
impl PartialEq for Candidate {
    fn eq(&self, other: &Self) -> bool {
        self.cmp(other) == Ordering::Equal
    }
}
impl Eq for Candidate {}

/// The top `k` hits of `query` on `field` over `segments`, exact.
///
/// `live[i]` and `allowed[i]` are bitsets over segment i's docs (bit d of word d / 64) of exactly
/// ceil(num_docs / 64) words; bits past the doc count are ignored. An empty `allowed` means every
/// doc is allowed. Statistics are over live (`Table`) or live AND allowed (`Allowed`); only live AND
/// allowed docs are returned, ordered by score descending then rowid ascending. Tantivy 0.27 fixes
/// BM25's k1 and b at 1.2 and 0.75, so other values are refused.
#[allow(clippy::too_many_arguments)]
pub fn top_k(
    segments: &[&Segment],
    live: &[&[u64]],
    allowed: &[&[u64]],
    population: Population,
    query: &str,
    field: &str,
    k1: f32,
    b: f32,
    k: usize,
) -> Result<Vec<Hit>> {
    top_k_with(segments, live, allowed, population, query, field, k1, b, k, &Controls::unlimited())
}

/// `top_k` under a cancel flag and a memory budget (spec 9).
#[allow(clippy::too_many_arguments)]
pub fn top_k_with(
    segments: &[&Segment],
    live: &[&[u64]],
    allowed: &[&[u64]],
    population: Population,
    query: &str,
    field: &str,
    k1: f32,
    b: f32,
    k: usize,
    controls: &Controls,
) -> Result<Vec<Hit>> {
    let Some(p) = prepare(segments, live, allowed, population, query, field, k1, b, k, controls)? else {
        return Ok(Vec::new());
    };
    let (index, reader) = directory::union_index(segments, None, field, controls)?;
    let searcher = reader.searcher();
    let order = reader_order(&searcher, segments.len())?;
    let (text, parsed) = parse_on(&index, field, query)?;
    let stats = statistics(&searcher, text, &order, &p, segments, controls)?;
    if stats.num_docs() == 0 {
        return Ok(Vec::new());
    }
    prefetch(&stats, parsed.as_ref())?;
    let scoring = EnableScoring::enabled_from_statistics_provider(&stats, &searcher);
    // The clause tree when Tantivy would not sum in clause order (invariants 5 and 7): one weight
    // per leaf, the weights Tantivy's boolean builds for its clauses; else Tantivy's own scorer.
    let mut leaves = Vec::new();
    let tree = if sums_in_clause_order(parsed.as_ref()) { None } else { Some(plan(parsed.as_ref(), &mut leaves)) };
    let weight = match tree {
        None => Some(parsed.weight(scoring).map_err(|e| weight_error(e, controls))?),
        Some(_) => None,
    };
    let leaf_weights: Vec<Box<dyn Weight>> =
        leaves.iter().map(|q| q.weight(scoring)).collect::<tantivy::Result<_>>().map_err(|e| weight_error(e, controls))?;
    controls.charge((k as u64 + 1) * std::mem::size_of::<Candidate>() as u64)?;
    let mut heap: BinaryHeap<Candidate> = BinaryHeap::with_capacity(k + 1);
    for (ord, reader) in searcher.segment_readers().iter().enumerate() {
        controls.check()?;
        let input = order[ord];
        let alive = &p.alive[input];
        let seg = segments[input];
        let max_doc = reader.max_doc();
        let candidate = |doc: u32, score: f32| -> Result<Candidate> {
            let rowid = seg.rowid(doc).ok_or_else(|| Error::internal("a hit past the segment's docs"))?;
            Ok(Candidate(Hit { rowid, segment: input as u32, doc, score }))
        };
        let is_alive = |doc: u32| (alive[(doc >> 6) as usize] >> (doc & 63)) & 1 == 1;
        if let (Some(tree), None) = (&tree, &weight) {
            let mut root = Cursor::new(tree, &leaf_weights, reader).map_err(|e| weight_error(e, controls))?;
            let mut run = Run { controls, work: 0, err: None };
            let mut target = 0u32;
            loop {
                let d = root.seek(target, &mut run);
                if let Some(e) = run.err.take() {
                    return Err(e);
                }
                if d == TERMINATED {
                    break;
                }
                if d >= max_doc || d < target {
                    return Err(Error::corrupt("the segment's postings name docs out of order or past its doc count"));
                }
                if is_alive(d) {
                    let score = root.score(d, &mut run);
                    if let Some(e) = run.err.take() {
                        return Err(e);
                    }
                    offer(&mut heap, k, candidate(d, score)?);
                }
                if !run.tick() {
                    if let Some(e) = run.err.take() {
                        return Err(e);
                    }
                }
                target = d + 1;
            }
            continue;
        }
        let Some(weight) = &weight else { return Err(Error::internal("no weight")) };
        let mut scorer = weight.scorer(reader, 1.0).map_err(|e| weight_error(e, controls))?;
        let mut doc = scorer.doc();
        let mut prev: Option<u32> = None;
        let mut seen = 0u32;
        while doc != TERMINATED {
            // A scorer yields each doc once, ascending, below max_doc: anything else is a crafted or
            // broken segment, refused before its doc is read.
            if doc >= max_doc || prev.map(|p| doc <= p).unwrap_or(false) {
                return Err(Error::corrupt("the segment's postings name docs out of order or past its doc count"));
            }
            prev = Some(doc);
            if is_alive(doc) {
                offer(&mut heap, k, candidate(doc, scorer.score())?);
            }
            seen = seen.wrapping_add(1);
            if seen % SCORED_DOCS_PER_CHECK == 0 {
                controls.check()?;
            }
            doc = scorer.advance();
        }
    }
    controls.check()?;
    Ok(heap.into_sorted_vec().into_iter().map(|c| c.0).collect())
}

/// MEASUREMENT ONLY, never for results: Tantivy's own block-WAND top k (the alive bitsets as delete
/// files, `TopDocs` ordered by score) over the same union and statistics, its k hits sorted by score
/// then rowid. Its block bounds are not valid under QoD's statistics, so it can miss docs of the
/// exact top k (review C2); Task 13 reports its time as a lower bound of what a scorer with valid
/// bounds could reach.
#[allow(clippy::too_many_arguments)]
pub fn top_k_block_wand_measurement_only(
    segments: &[&Segment],
    live: &[&[u64]],
    allowed: &[&[u64]],
    population: Population,
    query: &str,
    field: &str,
    k1: f32,
    b: f32,
    k: usize,
    controls: &Controls,
) -> Result<Vec<Hit>> {
    let Some(p) = prepare(segments, live, allowed, population, query, field, k1, b, k, controls)? else {
        return Ok(Vec::new());
    };
    let (index, reader) = directory::union_index(segments, Some(&p.alive), field, controls)?;
    let searcher = reader.searcher();
    let order = reader_order(&searcher, segments.len())?;
    let (text, parsed) = parse_on(&index, field, query)?;
    let stats = statistics(&searcher, text, &order, &p, segments, controls)?;
    if stats.num_docs() == 0 {
        return Ok(Vec::new());
    }
    prefetch(&stats, parsed.as_ref())?;
    let found = searcher
        .search_with_statistics_provider(parsed.as_ref(), &TopDocs::with_limit(k).order_by_score(), &stats)
        .map_err(|e| weight_error(e, controls))?;
    let mut hits = Vec::with_capacity(found.len());
    for (score, addr) in found {
        let input = *order.get(addr.segment_ord as usize).ok_or_else(|| Error::internal("a hit of no segment"))?;
        let rowid = segments[input].rowid(addr.doc_id).ok_or_else(|| Error::internal("a hit past the segment's docs"))?;
        hits.push(Hit { rowid, segment: input as u32, doc: addr.doc_id, score });
    }
    hits.sort_by(|x, y| y.score.total_cmp(&x.score).then(x.rowid.cmp(&y.rowid)));
    Ok(hits)
}

fn parse_on(index: &Index, field: &str, query: &str) -> Result<(Field, Box<dyn Query>)> {
    let text = index.schema().get_field(field).map_err(|e| Error::internal(&format!("no field: {e}")))?;
    let parsed = QueryParser::for_index(index, vec![text]).parse_query(query).map_err(parser_error)?;
    Ok((text, parsed))
}

fn statistics<'a>(
    searcher: &'a Searcher,
    text: Field,
    order: &[usize],
    p: &'a Prepared,
    segments: &[&'a Segment],
    controls: &'a Controls<'a>,
) -> Result<PopulationStats<'a>> {
    PopulationStats::new(
        searcher,
        text,
        order.iter().map(|i| p.pop[*i].as_slice()).collect(),
        &order.iter().map(|i| segments[*i].token_counts()).collect::<Vec<_>>(),
        controls,
    )
}

fn prefetch(stats: &PopulationStats, query: &dyn Query) -> Result<()> {
    let mut terms = Vec::new();
    query.query_terms(&mut |t, _| terms.push(t.clone()));
    stats.prefetch(&terms)
}

fn weight_error(e: tantivy::TantivyError, controls: &Controls) -> Error {
    if controls.is_cancelled() {
        Error::new("search_cancelled", "the call was cancelled")
    } else {
        Error::internal(&format!("search: {e}"))
    }
}
