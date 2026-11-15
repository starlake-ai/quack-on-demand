// qod_search_core C ABI (search spec 5). Every function that can fail returns 0 on success and
// non-zero on failure with *err set to a message the caller frees with qs_string_free. The library
// does no I/O: bundles arrive and leave as bytes.
//
// Hand-written (cbindgen is left to Phase 1); it must match native/qod-search/src/lib.rs. Error
// messages start with a stable code: bundle_corrupt, bundle_mismatch, bundle_build,
// search_field_not_allowed, search_query_invalid, search_query_too_large, search_query_unsupported,
// search_invalid_argument, search_cancelled, search_memory_exceeded, search_internal. On success
// *err is set to NULL. `err` may be NULL when the caller does not want the message.
//
// Panics (OWNER RULING 2026-11-15, spec 9): the library builds with panic = "unwind" and every
// function catches a panic and returns its error code only ("<code>: panic"; bundle_corrupt from
// qs_segment_open, bundle_build from the builder, search_internal from a search). A panic inside
// these functions prints nothing: the library installs, once, a panic hook that is quiet for them
// and defers to the previous hook for any other panic of the process. A builder that panicked is
// poisoned: every later call on it fails. NOT caught, and still fatal to the process: a
// stack overflow, an allocation failure, a panic while unwinding, and undefined behaviour. The query
// limits below (checked before any parse) bound the first; the memory budgets bound the
// allocations the library makes in proportion to the table, not Tantivy's own.
//
// Threads: a QsSegment is immutable once opened and may be searched from several threads at once;
// a QsBuilder belongs to one thread at a time.
//
// Cancellation: `cancel` is NULL or points to one byte, non-zero meaning cancelled (a
// std::atomic<bool> fits), which must stay valid for the call (for the builder: until
// qs_builder_finish). It is checked at least every 64 postings blocks, every 65,536 docs of a
// statistics pass, between segments and on every builder call; a cancelled call fails with
// search_cancelled and returns nothing. `memory_budget` is in bytes (UINT64_MAX for none); past it a
// call fails with search_memory_exceeded.
#pragma once
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

uint64_t qod_search_probe(uint64_t docs); // part 1 link probe, kept

typedef struct QsBuilder QsBuilder;
typedef struct QsSegment QsSegment;

void qs_string_free(char *s);
void qs_bytes_free(uint8_t *bytes, size_t len);

// The source fields a bundle records and a reader checks (6.2): a mismatch means "missing".
typedef struct QsSource {
	const char *index_id;     // canonical lowercase hyphenated UUID, 36 characters
	const char *path_key;     // lowercase hex SHA-256 of `path`, 64 characters (owner ruling 2026-11-14)
	const char *path;         // the data file's absolute path as the scan opens it
	uint64_t file_size;
	uint64_t footer_size;
	// What the catalog says of the file, checked against the bundle's own columns (not recorded in
	// it). -1 means "the caller cannot supply it": unchecked. Task 6's view has no record_count, so
	// the node passes -1 there.
	int64_t record_count;          // the file's row count = the bundle's doc count
	int64_t row_id_start;          // with has_internal_row_id 0: rowids must be row_id_start + position
	int32_t has_internal_row_id;   // 1, 0 or -1: the file carries _ducklake_internal_row_id
	int32_t has_internal_snapshot; // 1, 0 or -1: the bundle has a snapshot column exactly when 1
} QsSource;

// Refuses (search_invalid_argument) a source whose index_id is not a canonical UUID or whose
// path_key is not the SHA-256 of path. The builder checks the catalog fields at qs_builder_finish
// (bundle_build on a mismatch).
QsBuilder *qs_builder_new(const QsSource *source, const uint8_t *cancel, uint64_t memory_budget,
                          char **err);
// Docs are added in position order, 0, 1, 2, ...: doc id = physical position (6.2). snapshot_id
// -1 when the file has no _ducklake_internal_snapshot_id; rowid as 6.2's rowid column says.
// `text` is UTF-8 and holds `text_len` bytes (NULL allowed when text_len is 0; a NULL value is the
// caller's to map, to an empty text for instance). Every doc of a file gives a snapshot id, or none
// does.
int qs_builder_add(QsBuilder *b, uint64_t position, int64_t rowid, int64_t snapshot_id,
                   const char *text, size_t text_len, char **err);
// Consumes the builder; *bytes is the whole bundle (header, packed Tantivy segment files with
// offsets and CRC32, rowid column, snapshot column, exact token counts, footer). Freed with
// qs_bytes_free(*bytes, *len). A builder with no docs fails (bundle_build): an empty file needs no
// bundle.
int qs_builder_finish(QsBuilder *b, uint8_t **bytes, size_t *len, uint64_t *docs, char **err);

// Copies `bytes`. Fails (returns NULL, *err "bundle_mismatch: ..." or "bundle_corrupt: ...") when
// the footer, a checksum, the format or Tantivy version, a source field, or a catalog field that is
// not -1 differs from `expected`.
QsSegment *qs_segment_open(const uint8_t *bytes, size_t len, const QsSource *expected, char **err);
void qs_segment_free(QsSegment *s);
uint32_t qs_segment_num_docs(const QsSegment *s);
// 1 when the bundle stores one rowid per doc: the rowids given at build were not
// row_id_start + position. It is not the source's has_internal_row_id (a file whose internal row ids
// happen to be row_id_start + position reads 0, with the same mapping); that flag is checked at open.
int qs_segment_has_explicit_rowids(const QsSegment *s);
int64_t qs_segment_rowid(const QsSegment *s, uint32_t doc);    // -1 past the segment's docs
int64_t qs_segment_snapshot(const QsSegment *s, uint32_t doc); // -1 when none
int qs_segment_has_snapshot_column(const QsSegment *s); // 1 when built from _ducklake_internal_snapshot_id

enum { QS_POPULATION_TABLE = 0, QS_POPULATION_ALLOWED = 1 };

typedef struct QsHit { int64_t rowid; uint32_t segment; uint32_t doc; float score; } QsHit;

// Rule 6 and the limits of spec 9, checked before any parse: refuses a query longer than 4096 bytes,
// nesting parentheses deeper than 32 (every '(' counted, quoted or not) or holding more than 64
// terms (alphanumeric runs and '*', operators and field prefixes included) with
// "search_query_too_large: ...". "search_query_unsupported: ..." refuses the only shapes the search
// cannot score in clause order: a boost on a group whose clauses Tantivy would not sum in clause
// order (a group of three or more scored clauses holding a required one, or holding '*'), since
// Tantivy's BoostQuery exposes no inner query; and a disjunction-max query or a minimum number of
// optional clauses (the query parser builds neither today). Every other boolean shape, required,
// optional and excluded clauses at any arity, is scored with each matching clause's score summed in
// query clause order. A query naming a
// field outside `field` with "search_field_not_allowed: <field>"; a query the parser refuses with
// "search_query_invalid: ...". `field` is ASCII letters, digits and '_', not starting with a digit
// (search_invalid_argument otherwise).
int qs_check_query(const char *query, const char *field, char **err);

// The exact top k over the union of `segments` (5.2): every matching doc that is live[i] AND
// allowed[i] (bit d of word d / 64 of segment i) is scored, with statistics over the population
// (7.5): live for TABLE, live AND allowed for ALLOWED. A doc's score depends only on the doc and the
// population's statistics: each matching clause's score, summed in query clause order (groups
// Tantivy would sum in another order, such as an AND of three terms, are summed by the library).
// `*` matches every doc and scores as Tantivy's boolean scores it: in a group with a required clause
// that is not `*`, every `*` adds 0; when `*` is the only kind of required clause, the group scores
// 1.0 plus its optional clauses; in a group with no required clause, any optional `*` adds 1.0 once,
// after the other optional clauses; a `*` alone scores 1.0. Hits sorted by score descending,
// then rowid ascending; at most k (k <= 10000). live_words[i] (and allowed_words[i]) must equal
// ceil(qs_segment_num_docs(segments[i]) / 64), else search_invalid_argument; bits past the doc
// count are ignored. `allowed` may be NULL, meaning every doc is allowed (allowed_words is then
// ignored). out_cap must be at least k; *n_out receives the count. Hit.segment is the index into
// `segments`. k1 and b must be 1.2 and 0.75 (Tantivy 0.27 fixes them; other values are
// search_invalid_argument).
int qs_search(const QsSegment *const *segments, size_t n_segments,
              const uint64_t *const *live, const size_t *live_words,
              const uint64_t *const *allowed, const size_t *allowed_words, int population,
              const char *query, const char *field, float k1, float b, uint32_t k,
              const uint8_t *cancel, uint64_t memory_budget,
              QsHit *out, size_t out_cap, size_t *n_out, char **err);

// MEASUREMENT ONLY, never for results: qs_search's arguments through Tantivy's block-WAND top k.
// Its block bounds are chosen under each segment's own average length, so under QoD's population
// statistics it can miss docs of the exact top k. Task 13 times it as a lower bound of what a scorer
// with valid bounds could reach.
int qs_search_block_wand_measurement_only(
    const QsSegment *const *segments, size_t n_segments, const uint64_t *const *live,
    const size_t *live_words, const uint64_t *const *allowed, const size_t *allowed_words,
    int population, const char *query, const char *field, float k1, float b, uint32_t k,
    const uint8_t *cancel, uint64_t memory_budget, QsHit *out, size_t out_cap, size_t *n_out,
    char **err);

#ifdef __cplusplus
}
#endif
