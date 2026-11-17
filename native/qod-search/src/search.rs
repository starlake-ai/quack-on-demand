//! The top k of a full-text query over the per-call union of segments (search spec 5.2, 7.5): each
//! doc filtered by live AND allowed, scored by Tantivy's BM25 with QoD's own statistics over the
//! population, hits ordered by score descending then rowid ascending (invariant 7).
//!
//! The query is parsed by Tantivy's query grammar into its user-input AST, each leaf's column
//! remapped to its field's Tantivy name (`f<field_id>`, rule 6: a column outside the call's fields
//! is refused before any segment is read), and the AST built into a query by Tantivy's query parser
//! with the call's fields as default fields. The parser builds each term with no field prefix as a
//! group of one clause per default field, and the groups are scored as built, never refolded.
//!
//! The top k is exact: every matching alive doc is scored (no block-max pruning) on Tantivy's
//! seek-free scorer, whose union sums a doc's clause scores in query clause order, or on the
//! library's own clause tree where Tantivy would sum in another order, so a doc's score is a function
//! of the doc and the population's statistics only (invariants 5 and 7). The same compiled weights
//! and the same per-segment doc stream serve the matches iterator (`crate::matches`). Tantivy's
//! block-WAND is not used for results: its block bounds are chosen at build time under the segment's
//! own average length and do not bound a block under the population's (review C2, a top-1 doc
//! missed because of hidden rows). It stays available behind the `measurement` feature as
//! `top_k_block_wand_measurement_only`, a lower bound on what a scorer with valid bounds could reach.
use std::cmp::Ordering;
use std::collections::{BinaryHeap, HashMap};

#[cfg(feature = "measurement")]
use tantivy::collector::TopDocs;
use tantivy::query::{AllQuery, BooleanQuery, EnableScoring, Query, QueryParser, Scorer, Weight};
use tantivy::query_grammar::{Occur, UserInputAst, UserInputLeaf};
use tantivy::schema::{Field, Schema};
use tantivy::tokenizer::TokenizerManager;
use tantivy::{Index, IndexReader, Searcher, SegmentReader, TERMINATED};

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
/// The only BM25 parameters through Phase 1 (D10, OWNER RULING 2026-11-16).
pub const K1: f32 = 1.2;
pub const B: f32 = 0.75;

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
    /// NAN for a hit of a matches iterator opened without scores.
    pub score: f32,
}

fn parser_error(e: tantivy::query::QueryParserError) -> Error {
    Error::new("search_query_invalid", &e.to_string())
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
/// depends on the segment, hidden rows included. The per-field groups the parser adds for a term
/// with no field prefix are unions of terms, which Tantivy sums in clause order, so the AST alone
/// decides.
fn ast_sums_in_clause_order(ast: &UserInputAst) -> bool {
    match ast {
        UserInputAst::Clause(clauses) => {
            let scored: Vec<&(Option<Occur>, UserInputAst)> =
                clauses.iter().filter(|(o, _)| *o != Some(Occur::MustNot)).collect();
            let required = scored.iter().filter(|(o, _)| *o == Some(Occur::Must)).count();
            let all = scored
                .iter()
                .any(|(_, c)| matches!(c, UserInputAst::Leaf(l) if matches!(**l, UserInputLeaf::All)));
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

/// Rewrites every field name of `ast` (a column, as the query writes it) to its field's Tantivy
/// name, columns compared as DuckDB compares identifiers (`bundle::same_column`: ignoring ASCII case
/// only); a column outside `fields` is `search_field_not_allowed: <column>`, spelled as the query
/// wrote it.
fn remap(ast: UserInputAst, fields: &[(&str, i64)]) -> Result<UserInputAst> {
    let to_field = |column: String| -> Result<String> {
        fields
            .iter()
            .find(|(c, _)| bundle::same_column(c, &column))
            .map(|(_, id)| bundle::field_name(*id))
            .ok_or_else(|| Error::new("search_field_not_allowed", &column))
    };
    remap_with(ast, &to_field)
}

fn remap_with(ast: UserInputAst, to_field: &dyn Fn(String) -> Result<String>) -> Result<UserInputAst> {
    Ok(match ast {
        UserInputAst::Clause(clauses) => UserInputAst::Clause(
            clauses.into_iter().map(|(o, c)| remap_with(c, to_field).map(|c| (o, c))).collect::<Result<_>>()?,
        ),
        UserInputAst::Boost(inner, boost) => UserInputAst::Boost(Box::new(remap_with(*inner, to_field)?), boost),
        UserInputAst::Leaf(leaf) => UserInputAst::Leaf(Box::new(match *leaf {
            UserInputLeaf::Literal(mut literal) => {
                if let Some(column) = literal.field_name.take() {
                    literal.field_name = Some(to_field(column)?);
                }
                UserInputLeaf::Literal(literal)
            }
            UserInputLeaf::All => UserInputLeaf::All,
            UserInputLeaf::Range { field, lower, upper } => {
                UserInputLeaf::Range { field: field.map(to_field).transpose()?, lower, upper }
            }
            UserInputLeaf::Set { field, elements } => UserInputLeaf::Set { field: field.map(to_field).transpose()?, elements },
            // `column:*` asks whether a doc has the field, which Tantivy answers from fast fields
            // only: the text fields are not, so the search could only fail. Refused here, after the
            // column check, so that the check and the search agree.
            UserInputLeaf::Exists { field } => {
                to_field(field)?;
                return Err(Error::new("search_query_unsupported", "a field existence query (column:*)"));
            }
            UserInputLeaf::Regex { field, pattern } => UserInputLeaf::Regex { field: field.map(to_field).transpose()?, pattern },
        })),
    })
}

/// Rule 6 and the limits, in order: the fields, the limits of spec 9 (before any parse), the
/// grammar, the shapes the collector cannot score in clause order, then the remap of every column
/// to its field. Reads no segment.
fn compile_ast(query: &str, fields: &[(&str, i64)]) -> Result<UserInputAst> {
    bundle::check_fields(fields)?;
    check_query_limits(query)?;
    let ast = tantivy::query_grammar::parse_query(query)
        .map_err(|_| Error::new("search_query_invalid", "the query does not parse"))?;
    check_boosted_groups(&ast)?;
    remap(ast, fields)
}

/// The query of a remapped AST, the call's fields as default fields in their order.
fn build_query(schema: &Schema, tokenizers: &TokenizerManager, ast: UserInputAst, fields: &[(&str, i64)]) -> Result<Box<dyn Query>> {
    let defaults: Vec<Field> = fields
        .iter()
        .map(|(_, id)| schema.get_field(&bundle::field_name(*id)).map_err(|_| Error::invalid(&format!("field id {id} is not a field of the index"))))
        .collect::<Result<_>>()?;
    let parser = QueryParser::new(schema.clone(), defaults, tokenizers.clone());
    let parsed = parser.build_query_from_user_input_ast(ast).map_err(parser_error)?;
    check_parsed(parsed.as_ref())?;
    Ok(parsed)
}

/// Rule 6 and the query limits: a query may name no column outside `fields` (`(column, field id)`,
/// 1 to 32, columns distinct ignoring ASCII case, ids distinct; a column prefix matches its column
/// ignoring ASCII case), must pass the limits of spec 9 (checked before any parse) and must parse.
/// Reads no segment.
pub fn check_query(query: &str, fields: &[(&str, i64)]) -> Result<()> {
    let ast = compile_ast(query, fields)?;
    let ids: Vec<i64> = fields.iter().map(|(_, id)| *id).collect();
    let (schema, _, _) = bundle::schema(&ids);
    build_query(&schema, &TokenizerManager::default(), ast, fields).map(|_| ())
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
pub(crate) enum Plan {
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

/// What every search path shares: checked arguments, the bitsets, the remapped query.
struct Prepared {
    alive: Vec<Vec<u64>>,
    pop: Vec<Vec<u64>>,
    ast: UserInputAst,
}

/// Checks the arguments (the query and its fields first, before any segment is read), then builds
/// the alive and population bitsets. None when nothing can match. `k` is None for the matches
/// iterator (which has no k); a top k asks for 1 to `MAX_K`.
#[allow(clippy::too_many_arguments)]
fn prepare(
    segments: &[&Segment],
    live: &[&[u64]],
    allowed: &[&[u64]],
    population: Population,
    query: &str,
    fields: &[(&str, i64)],
    k1: f32,
    b: f32,
    k: Option<usize>,
    controls: &Controls,
) -> Result<Option<Prepared>> {
    let ast = compile_ast(query, fields)?;
    if k1 != K1 || b != B {
        return Err(Error::invalid("k1 and b other than 1.2 and 0.75 are not supported through Phase 1"));
    }
    if k.map(|k| k == 0 || k > MAX_K).unwrap_or(false) {
        return Err(Error::invalid(&format!("k is 1 to {MAX_K}")));
    }
    if live.len() != segments.len() || !(allowed.is_empty() || allowed.len() == segments.len()) {
        return Err(Error::invalid("one live bitset (and one allowed bitset, or none) per segment"));
    }
    if segments.len() > u32::MAX as usize {
        return Err(Error::invalid("too many segments"));
    }
    if let Some(first) = segments.first() {
        let ids = first.field_ids();
        if segments.iter().any(|s| s.field_ids() != ids) {
            return Err(Error::invalid("the segments of one call hold different field sets"));
        }
        if let Some((column, id)) = fields.iter().find(|(_, id)| !ids.contains(id)) {
            return Err(Error::invalid(&format!("field id {id} of column {column} is not a field of the index")));
        }
    }
    controls.check()?;
    if segments.is_empty() {
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
    Ok(Some(Prepared { alive, pop, ast }))
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

/// The weights a call scores with: Tantivy's own when it sums in clause order, else one weight per
/// leaf of the library's clause tree.
pub(crate) enum Exec {
    Native(Box<dyn Weight>),
    Tree { plan: Plan, leaves: Vec<Box<dyn Weight>> },
}

/// A call compiled once: the alive bitsets per input segment, the union, and the weights built under
/// the population's statistics. Owns everything; the segments stay the caller's.
pub(crate) struct Compiled {
    pub(crate) alive: Vec<Vec<u64>>,
    /// Reader ord -> input segment.
    pub(crate) order: Vec<usize>,
    pub(crate) exec: Exec,
    pub(crate) searcher: Searcher,
    _reader: IndexReader,
    _index: Index,
}

/// Prepares a call and compiles its weights. None when nothing can match.
#[allow(clippy::too_many_arguments)]
pub(crate) fn compile(
    segments: &[&Segment],
    live: &[&[u64]],
    allowed: &[&[u64]],
    population: Population,
    query: &str,
    fields: &[(&str, i64)],
    k1: f32,
    b: f32,
    k: Option<usize>,
    controls: &Controls,
) -> Result<Option<Compiled>> {
    let Some(Prepared { alive, pop, ast }) = prepare(segments, live, allowed, population, query, fields, k1, b, k, controls)? else {
        return Ok(None);
    };
    let (index, reader) = directory::union_index(segments, None, controls)?;
    let searcher = reader.searcher();
    let order = reader_order(&searcher, segments.len())?;
    let schema = index.schema();
    // A range or set leaf reads each segment through a doc bitset of the segment's docs (Tantivy's weight for it),
    // one segment's at a time (Task 11 fix round 1, review M1): charged before any weight is made.
    // A set leaf's weight also builds an FST of its terms first, through a builder whose registry takes about a
    // megabyte whatever the set holds, freed once the weight is made (one at a time): charged once a call.
    let max_docs = segments.iter().map(|s| s.num_docs()).max().unwrap_or(0);
    let (ranges, sets) = doc_set_leaves(&ast, fields.len() as u64);
    controls.charge(
        (ranges + sets)
            .saturating_mul(8 * words(max_docs) as u64 + DOC_SET_LEAF_BYTES)
            .saturating_add(if sets > 0 { SET_BUILD_BYTES } else { 0 }),
    )?;
    let parsed = build_query(&schema, index.tokenizers(), ast, fields)?;
    // The state that grows with the query (Task 11, Task 2 review 7): every term's weight and, while one
    // segment is read, its scorer (block buffers, the BM25 cache), up to 64 terms over 32 fields. Charged
    // before any weight or scorer is made; one segment's scorers live at a time (top k and matches alike).
    controls.charge(query_state_bytes(parsed.as_ref()))?;
    let exec = {
        let stats = statistics(&searcher, &schema, &order, &pop, segments, fields, controls)?;
        if stats.num_docs() == 0 {
            return Ok(None);
        }
        prefetch(&stats, parsed.as_ref())?;
        let scoring = EnableScoring::enabled_from_statistics_provider(&stats, &searcher);
        // The clause tree when Tantivy would not sum in clause order (invariants 5 and 7): one weight
        // per leaf, the weights Tantivy's boolean builds for its clauses; else Tantivy's own scorer.
        if sums_in_clause_order(parsed.as_ref()) {
            Exec::Native(parsed.weight(scoring).map_err(|e| weight_error(e, controls))?)
        } else {
            let mut leaves = Vec::new();
            let tree = plan(parsed.as_ref(), &mut leaves);
            let weights = leaves
                .iter()
                .map(|q| q.weight(scoring))
                .collect::<tantivy::Result<_>>()
                .map_err(|e| weight_error(e, controls))?;
            Exec::Tree { plan: tree, leaves: weights }
        }
    };
    Ok(Some(Compiled { alive, order, exec, searcher, _reader: reader, _index: index }))
}

/// What one term of a query holds through a call: its weight and one segment's scorer, about 4 KiB on Tantivy
/// 0.27 for a term query and less for a phrase's term (`matches_scorer_state_charged` measures both), with room
/// to spare.
pub const TERM_STATE_BYTES: u64 = 6 * 1024;

/// What a range or set leaf holds besides its doc bitset (its weight and scorer).
pub const DOC_SET_LEAF_BYTES: u64 = 1024;
/// What building a set leaf's weight takes for a moment (Tantivy's FST builder registry): about 960 KB measured
/// whatever the set's size (`matches_doc_set_leaves_charged`).
pub const SET_BUILD_BYTES: u64 = 3 << 19;

/// The range and the set leaves of `ast` (a leaf naming no column counts once per call field, as it would
/// expand; Tantivy refuses an unprefixed range today). Regex leaves are refused by the query parser.
fn doc_set_leaves(ast: &UserInputAst, n_fields: u64) -> (u64, u64) {
    match ast {
        UserInputAst::Clause(clauses) => clauses.iter().fold((0, 0), |(r, s), (_, c)| {
            let (cr, cs) = doc_set_leaves(c, n_fields);
            (r + cr, s + cs)
        }),
        UserInputAst::Boost(inner, _) => doc_set_leaves(inner, n_fields),
        UserInputAst::Leaf(leaf) => {
            let count = |field: &Option<String>| if field.is_some() { 1 } else { n_fields };
            match leaf.as_ref() {
                UserInputLeaf::Range { field, .. } => (count(field), 0),
                UserInputLeaf::Set { field, .. } => (0, count(field)),
                _ => (0, 0),
            }
        }
    }
}

/// The bytes `query`'s terms hold through a call, `TERM_STATE_BYTES` each.
pub(crate) fn query_state_bytes(query: &dyn Query) -> u64 {
    let mut bytes = 0u64;
    query.query_terms(&mut |_, _| bytes = bytes.saturating_add(TERM_STATE_BYTES));
    bytes
}

fn corrupt_postings() -> Error {
    Error::corrupt("the segment's postings name docs out of order or past its doc count")
}

/// The matching docs of one segment, in doc order, under a call's weights: the one stream both the
/// exact collector and the matches iterator read, so a doc scores the same through both. A doc it
/// yields may be dead; the reader filters by its alive bitset.
pub(crate) struct DocStream {
    kind: Stream,
    max_doc: u32,
    work: u32,
}

enum Stream {
    Native { scorer: Box<dyn Scorer>, started: bool, prev: Option<u32> },
    Tree { root: Cursor, target: u32 },
}

impl DocStream {
    pub(crate) fn new(exec: &Exec, reader: &SegmentReader, controls: &Controls) -> Result<DocStream> {
        let kind = match exec {
            Exec::Native(weight) => {
                Stream::Native { scorer: weight.scorer(reader, 1.0).map_err(|e| weight_error(e, controls))?, started: false, prev: None }
            }
            Exec::Tree { plan, leaves } => {
                Stream::Tree { root: Cursor::new(plan, leaves, reader).map_err(|e| weight_error(e, controls))?, target: 0 }
            }
        };
        Ok(DocStream { kind, max_doc: reader.max_doc(), work: 0 })
    }

    /// The next matching doc, None when the segment has no more. Checks the cancel flag every
    /// `SCORED_DOCS_PER_CHECK` moves.
    pub(crate) fn next(&mut self, controls: &Controls) -> Result<Option<u32>> {
        match &mut self.kind {
            Stream::Native { scorer, started, prev } => {
                let doc = if *started {
                    scorer.advance()
                } else {
                    *started = true;
                    scorer.doc()
                };
                if doc == TERMINATED {
                    return Ok(None);
                }
                // A scorer yields each doc once, ascending, below max_doc: anything else is a crafted
                // or broken segment, refused before its doc is read.
                if doc >= self.max_doc || prev.map(|p| doc <= p).unwrap_or(false) {
                    return Err(corrupt_postings());
                }
                *prev = Some(doc);
                self.work = self.work.wrapping_add(1);
                if self.work % SCORED_DOCS_PER_CHECK == 0 {
                    controls.check()?;
                }
                Ok(Some(doc))
            }
            Stream::Tree { root, target } => {
                let mut run = Run { controls, work: self.work, err: None };
                let d = root.seek(*target, &mut run);
                if run.err.is_none() && d != TERMINATED {
                    run.tick();
                }
                self.work = run.work;
                if let Some(e) = run.err {
                    return Err(e);
                }
                if d == TERMINATED {
                    return Ok(None);
                }
                if d >= self.max_doc || d < *target {
                    return Err(corrupt_postings());
                }
                *target = d + 1;
                Ok(Some(d))
            }
        }
    }

    /// The score of `doc`, the doc `next` returned last.
    pub(crate) fn score(&mut self, doc: u32, controls: &Controls) -> Result<f32> {
        match &mut self.kind {
            Stream::Native { scorer, .. } => Ok(scorer.score()),
            Stream::Tree { root, .. } => {
                let mut run = Run { controls, work: self.work, err: None };
                let s = root.score(doc, &mut run);
                self.work = run.work;
                match run.err {
                    Some(e) => Err(e),
                    None => Ok(s),
                }
            }
        }
    }
}

/// Whether bit `doc` of `words` is set.
pub(crate) fn bit(words: &[u64], doc: u32) -> bool {
    (words[(doc >> 6) as usize] >> (doc & 63)) & 1 == 1
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

/// The top `k` hits of `query` over `segments`, exact. `fields` are the call's columns, each with its
/// field id: a subset of the index's fields, the default fields of a term with no prefix.
///
/// `live[i]` and `allowed[i]` are bitsets over segment i's docs (bit d of word d / 64) of exactly
/// ceil(num_docs / 64) words; bits past the doc count are ignored. An empty `allowed` means every
/// doc is allowed. Statistics are over live (`Table`) or live AND allowed (`Allowed`); only live AND
/// allowed docs are returned, ordered by score descending then rowid ascending. k1 and b are 1.2
/// and 0.75 through Phase 1 (D10); other values are refused.
#[allow(clippy::too_many_arguments)]
pub fn top_k(
    segments: &[&Segment],
    live: &[&[u64]],
    allowed: &[&[u64]],
    population: Population,
    query: &str,
    fields: &[(&str, i64)],
    k1: f32,
    b: f32,
    k: usize,
) -> Result<Vec<Hit>> {
    top_k_with(segments, live, allowed, population, query, fields, k1, b, k, &Controls::unlimited())
}

/// `top_k` under a cancel flag and a memory budget (spec 9).
#[allow(clippy::too_many_arguments)]
pub fn top_k_with(
    segments: &[&Segment],
    live: &[&[u64]],
    allowed: &[&[u64]],
    population: Population,
    query: &str,
    fields: &[(&str, i64)],
    k1: f32,
    b: f32,
    k: usize,
    controls: &Controls,
) -> Result<Vec<Hit>> {
    let Some(c) = compile(segments, live, allowed, population, query, fields, k1, b, Some(k), controls)? else {
        return Ok(Vec::new());
    };
    controls.charge((k as u64 + 1) * std::mem::size_of::<Candidate>() as u64)?;
    let mut heap: BinaryHeap<Candidate> = BinaryHeap::with_capacity(k + 1);
    for (ord, reader) in c.searcher.segment_readers().iter().enumerate() {
        controls.check()?;
        let input = c.order[ord];
        let alive = &c.alive[input];
        let seg = segments[input];
        let mut stream = DocStream::new(&c.exec, reader, controls)?;
        while let Some(doc) = stream.next(controls)? {
            if bit(alive, doc) {
                let score = stream.score(doc, controls)?;
                let rowid = seg.rowid(doc).ok_or_else(|| Error::internal("a hit past the segment's docs"))?;
                offer(&mut heap, k, Candidate(Hit { rowid, segment: input as u32, doc, score }));
            }
        }
    }
    controls.check()?;
    Ok(heap.into_sorted_vec().into_iter().map(|c| c.0).collect())
}

/// MEASUREMENT ONLY, never for results: Tantivy's own block-WAND top k (the alive bitsets as delete
/// files, `TopDocs` ordered by score) over the same union and statistics, its k hits sorted by score
/// then rowid. Its block bounds are not valid under QoD's statistics, so it can miss docs of the
/// exact top k (review C2).
#[cfg(feature = "measurement")]
#[allow(clippy::too_many_arguments)]
pub fn top_k_block_wand_measurement_only(
    segments: &[&Segment],
    live: &[&[u64]],
    allowed: &[&[u64]],
    population: Population,
    query: &str,
    fields: &[(&str, i64)],
    k1: f32,
    b: f32,
    k: usize,
    controls: &Controls,
) -> Result<Vec<Hit>> {
    let Some(p) = prepare(segments, live, allowed, population, query, fields, k1, b, Some(k), controls)? else {
        return Ok(Vec::new());
    };
    let (index, reader) = directory::union_index(segments, Some(&p.alive), controls)?;
    let searcher = reader.searcher();
    let order = reader_order(&searcher, segments.len())?;
    let schema = index.schema();
    let parsed = build_query(&schema, index.tokenizers(), p.ast, fields)?;
    let stats = statistics(&searcher, &schema, &order, &p.pop, segments, fields, controls)?;
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

/// The population's statistics for the call's fields, in reader order.
fn statistics<'a>(
    searcher: &'a Searcher,
    schema: &Schema,
    order: &[usize],
    pop: &'a [Vec<u64>],
    segments: &[&'a Segment],
    fields: &[(&str, i64)],
    controls: &'a Controls<'a>,
) -> Result<PopulationStats<'a>> {
    let call_fields: Vec<Field> = fields
        .iter()
        .map(|(_, id)| schema.get_field(&bundle::field_name(*id)).map_err(|_| Error::internal("a call field outside the union")))
        .collect::<Result<_>>()?;
    let mut counts: Vec<Vec<&[u32]>> = Vec::with_capacity(order.len());
    for i in order {
        let seg = segments[*i];
        let mut per_field = Vec::with_capacity(fields.len());
        for (_, id) in fields {
            let at = seg.field_position(*id).ok_or_else(|| Error::internal("a call field outside the segment"))?;
            per_field.push(seg.token_counts(at));
        }
        counts.push(per_field);
    }
    PopulationStats::new(searcher, &call_fields, order.iter().map(|i| pop[*i].as_slice()).collect(), &counts, controls)
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
