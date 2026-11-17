// qod_search_core C ABI, version 2 (search spec 5, 6.2, 7.5, 9). Every function that can fail returns
// 0 on success and non-zero on failure with *err set to a message the caller frees with
// qs_string_free. The library does no I/O: bundles arrive and leave as bytes.
//
// Hand-written (OWNER RULING 2026-11-16, D6: no cbindgen); it must match
// native/qod-search/src/lib.rs, which tests/header_parity.rs checks: every exported function, its
// parameter types and return type, every struct's fields in order, and every constant. Error messages
// start with a stable code: bundle_corrupt, bundle_mismatch, bundle_mac_mismatch, bundle_build,
// search_field_not_allowed, search_query_invalid, search_query_too_large, search_query_unsupported,
// search_invalid_argument, search_cancelled, search_memory_exceeded, search_internal. On success
// *err is set to NULL. `err` may be NULL when the caller does not want the message.
//
// Panics (OWNER RULING 2026-11-15, spec 9): the library builds with panic = "unwind" and every
// function catches a panic and returns its error code only ("<code>: panic"; bundle_corrupt from
// qs_segment_open, bundle_build from the builder, search_internal from a search, a check or the
// matches iterator). A panic inside these functions prints nothing: the library installs, once, a
// panic hook that is quiet for them and defers to the previous hook for any other panic of the
// process. A builder or a matches iterator that panicked is poisoned: every later call on it fails.
// NOT caught, and still fatal to the process: a stack overflow, an allocation failure, a panic while
// unwinding, and undefined behaviour. The query limits below (checked before any parse) bound the
// first; the memory budgets bound the allocations the library makes in proportion to the table, not
// Tantivy's own.
//
// Threads: a QsSegment is immutable once opened and may be searched from several threads at once;
// a QsBuilder and a QsMatches belong to one thread at a time.
//
// Cancellation: `cancel` is NULL or points to one byte, non-zero meaning cancelled (a
// std::atomic<bool> fits), which must stay valid for the call (for the builder: until
// qs_builder_finish; for a matches iterator: until qs_matches_free). It is checked at least every 64
// postings blocks, every 65,536 docs of a statistics pass, between segments, on every builder call
// and at the start of every qs_matches_next; a cancelled call fails with search_cancelled and
// returns nothing. `memory_budget` is in bytes (UINT64_MAX for none); past it a call fails with
// search_memory_exceeded.
//
// Fields: an index covers 1 to 32 text fields, each bound to its DuckLake field id. A bundle names
// its fields by field id only (one Tantivy field `f<field_id>` each), so a column rename keeps every
// bundle; a query names columns, and a call's `fields` maps each column, under the name the table
// gives it now, to its field id.
#pragma once
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

// The bundle format this library writes and the only one it reads: a bundle of any other version
// under a valid MAC is bundle_mismatch, and one of formats 1 and 2, which carry no MAC,
// bundle_mac_mismatch (both read as missing).
#define QS_BUNDLE_FORMAT_VERSION 3

typedef struct QsBuilder QsBuilder;
typedef struct QsSegment QsSegment;
typedef struct QsMatches QsMatches;

void qs_string_free(char *s);
void qs_bytes_free(uint8_t *bytes, size_t len);

// One indexed field: `column` the name as the table names it now (NUL-terminated UTF-8, not empty),
// `field_id` its DuckLake field id (0 or more). Column names compare as DuckDB compares identifiers,
// ignoring ASCII case only (`Title` is `title`, `É` is not `é`): two fields of one list whose
// columns are equal so are search_invalid_argument, for an index's fields and a call's alike.
typedef struct QsField {
	const char *column;
	int64_t field_id;
} QsField;

// One field's text of a doc: UTF-8, `len` bytes. text NULL and len 0: SQL NULL, no tokens (as an
// empty text). text NULL with len above 0 is search_invalid_argument.
typedef struct QsText {
	const char *text;
	size_t len;
} QsText;

// The source fields a bundle records and a reader checks (6.2): a mismatch means "missing".
typedef struct QsSource {
	const char *index_id;          // canonical lowercase hyphenated UUID, 36 characters
	const char *path_key;          // lowercase hex SHA-256 of `path`, 64 characters (owner ruling 2026-11-14)
	const char *path;              // the data file's absolute path as the scan opens it
	uint64_t file_size;
	uint64_t footer_size;
	int64_t table_id;              // the DuckLake table id the index pins (0 or more)
	const QsField *fields;         // the index's fields, in index order; field ids distinct, columns distinct
	size_t n_fields;               // 1 to 32
	// What the catalog says of the file, checked against the bundle's own columns (not recorded in
	// it). -1 means "the caller cannot supply it": unchecked.
	int64_t record_count;          // the file's row count = the bundle's doc count
	int64_t row_id_start;          // with has_internal_row_id 0: rowids must be row_id_start + position
	int32_t has_internal_row_id;   // 1, 0 or -1: the file carries _ducklake_internal_row_id
	int32_t has_internal_snapshot; // 1, 0 or -1: the bundle has a snapshot column exactly when 1
	// The index's bundle MAC key (spec 13): exactly 32 bytes, not all zeros, else
	// search_invalid_argument. The manager derives it from the node-key secret for the index
	// (HMAC-SHA256 over "qod-search-bundle-mac/v1|" + index_id), the same on every node; the library
	// never prints it.
	const uint8_t *mac_key;
	size_t mac_key_len;
} QsSource;

// Refuses (search_invalid_argument) a source whose index_id is not a canonical UUID, whose path_key
// is not the SHA-256 of path, whose fields are not 1 to 32 distinct entries, or whose mac_key is
// NULL, not 32 bytes or all zeros. The builder checks
// the catalog fields at qs_builder_finish (bundle_build on a mismatch).
QsBuilder *qs_builder_new(const QsSource *source, const uint8_t *cancel, uint64_t memory_budget,
                          char **err);
// Docs are added in position order, 0, 1, 2, ...: doc id = physical position (6.2). snapshot_id
// -1 when the file has no _ducklake_internal_snapshot_id; rowid as 6.2's rowid column says.
// `texts` holds one text per field of the source, in the source's field order (n_texts ==
// source->n_fields, else search_invalid_argument). Every doc of a file gives a snapshot id, or none
// does.
int qs_builder_add(QsBuilder *b, uint64_t position, int64_t rowid, int64_t snapshot_id,
                   const QsText *texts, size_t n_texts, char **err);
// What the builder holds now, in bytes (the writer's arena and its columns): the figure its memory_budget is
// checked against, so a caller can bound several builders jointly. 0 for NULL or a builder that panicked.
uint64_t qs_builder_memory(const QsBuilder *b);
// The most one qs_builder_add of `texts` may allocate beyond qs_builder_memory(b), from the texts' lengths alone:
// twice the texts, 128 bytes a possible token, the term table's next doubling, the next doubling of every per-doc
// column, and a fixed share. qs_builder_add refuses (search_memory_exceeded) before the writer reads a byte of the
// texts when memory plus this bound passes the builder's budget. 0 for NULL or a builder that panicked.
uint64_t qs_builder_add_bound(const QsBuilder *b, const QsText *texts, size_t n_texts);
// The most qs_builder_finish may allocate beyond qs_builder_memory(b) (twice it); the finish is refused before the
// writer is finalized when the two pass the budget. 0 for NULL or a builder that panicked.
uint64_t qs_builder_finish_bound(const QsBuilder *b);
// Replaces the builder's memory_budget (a caller bounding several builders jointly sets each one's to what is left
// before each call). 0 on success, non-zero for NULL or a builder that panicked.
int qs_builder_set_budget(QsBuilder *b, uint64_t memory_budget);
// Consumes the builder; *bytes is the whole bundle (header, packed Tantivy segment files with
// offsets and CRC32, rowid column, snapshot column, one exact token count column per field, footer
// naming the source, the table id and the field ids), then its 32-byte MAC: HMAC-SHA256(mac_key,
// label || 0x0A || every byte before the MAC), label "qod-search-bundle|v3|" + index_id + "|" +
// path_key. Freed with qs_bytes_free(*bytes, *len). A
// builder with no docs fails (bundle_build): an empty file needs no bundle.
int qs_builder_finish(QsBuilder *b, uint8_t **bytes, size_t *len, uint64_t *docs, char **err);

// Checks, in this order and before reading any other byte of `bytes`: that len is at least the
// shortest bundle (else "bundle_corrupt: ..."), then the MAC of the last 32 bytes over the rest under
// expected->mac_key and the label of expected's index_id and path_key, compared in constant time (a
// difference is "bundle_mac_mismatch: ...", to be read as missing: a forged, truncated, other-index
// or pre-format-3 bundle). Only then are the footer and the regions parsed. Copies the bundle (see
// qs_segment_open_borrowed for an open that does not). Fails
// (returns NULL, *err "bundle_mismatch: ..." or "bundle_corrupt: ...") when the footer, a checksum,
// the format or Tantivy version, a source field, the table id, the ordered field id list (the columns
// of `expected` are not compared: a rename keeps the bundle), or a catalog field that is not -1
// differs from `expected`. A bundle of another format version under a valid MAC reads
// "bundle_mismatch: format version <n>, expected 3".
QsSegment *qs_segment_open(const uint8_t *bytes, size_t len, const QsSource *expected, char **err);
// As qs_segment_open, every check the same, but the segment reads `bytes` in place: nothing of the
// bundle is copied. `bytes` must stay valid and unchanged until qs_segment_free of the result (every
// matches iterator over it freed first).
QsSegment *qs_segment_open_borrowed(const uint8_t *bytes, size_t len, const QsSource *expected, char **err);
// What `s` holds on the heap besides the bundle's bytes (its copy, or the borrowed ones), as allocated:
// the token count columns (4 bytes a doc a field), the explicit rowid and snapshot columns (8 bytes a
// doc each, when present), the file table, the field ids, the source's strings and the segment itself.
// 0 for NULL.
uint64_t qs_segment_heap_bytes(const QsSegment *s);
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
// The search path. Exhaustive scores every matching alive doc (the exact top k); any other value is
// search_invalid_argument. Task 15 may add QS_PATH_ALIVE = 1, QS_PATH_FILTER = 2 and
// QS_PATH_AUTO = 3 (deferred out of Phase 1, OWNER RULING 2026-11-16).
enum { QS_PATH_EXHAUSTIVE = 0 };

// score is NAN for a hit of a matches iterator opened with with_score 0.
typedef struct QsHit {
	int64_t rowid;
	uint32_t segment;
	uint32_t doc;
	float score;
} QsHit;

// The arguments of a search, read once at the call (qs_search) or at the open (qs_matches_open).
typedef struct QsSearchArgs {
	const QsSegment *const *segments;   // n_segments live segments of one index
	size_t n_segments;
	const uint64_t *const *live;        // live[i]: live_words[i] words, bit d of word d / 64 = doc d
	const size_t *live_words;           // each ceil(qs_segment_num_docs(segments[i]) / 64)
	const uint64_t *const *allowed;     // NULL: every doc allowed (allowed_words then ignored)
	const size_t *allowed_words;
	int population;                     // QS_POPULATION_TABLE or QS_POPULATION_ALLOWED
	const char *query;                  // NUL-terminated UTF-8
	const QsField *fields;              // the call's `columns`, a subset of the index's fields
	size_t n_fields;                    // 1 to 32, columns distinct (ASCII case ignored), field ids distinct
	float k1;                           // 1.2 through Phase 1 (D10); other values: search_invalid_argument
	float b;                            // 0.75 through Phase 1 (D10); other values: search_invalid_argument
	uint32_t k;                         // qs_search only (qs_matches_open ignores it); 1 to 10000
	int path;                           // QS_PATH_EXHAUSTIVE
	const uint8_t *cancel;
	uint64_t memory_budget;
} QsSearchArgs;

// Rule 6 and the limits of spec 9, checked before any parse: refuses a query longer than 4096 bytes,
// nesting parentheses deeper than 32 (every '(' counted, quoted or not) or holding more than 64
// terms (alphanumeric runs and '*', operators and field prefixes included) with
// "search_query_too_large: ...". The query is parsed by Tantivy's query grammar; a leaf naming a
// column outside `fields` fails "search_field_not_allowed: <column>", spelled as the query wrote it
// (a prefix matches its column ignoring ASCII case only, as DuckDB compares identifiers: `TITLE:w1`
// is `title:w1`, `É:w1` is not `é:w1`). A column prefix is the
// column's name as the grammar writes a field name: a special character of the grammar (space,
// colon, quote, parenthesis, ...) is escaped with a backslash (`my\ col:w1`); a quoted name is not a
// field prefix (`"my col":w1` is search_query_invalid), and a name starting with '-' cannot be
// written at all. Every column stays searchable through the default fields. A term with no field
// prefix expands to one clause per field of `fields`, in `fields` order, each term's clauses forming
// one group. "search_query_unsupported: ..." refuses the only shapes the search cannot score in
// clause order: a boost on a group whose clauses Tantivy would not sum in clause order (a group of
// three or more scored clauses holding a required one, or holding '*'), since Tantivy's BoostQuery
// exposes no inner query; and a disjunction-max query or a minimum number of optional clauses (the
// query parser builds neither today), and a field existence query (`column:*`, which needs a fast
// field, and no text field is one). Every other boolean shape, required, optional and excluded
// clauses at any arity, is scored with each matching clause's score summed in clause order, groups
// as the query parser builds them (never refolded). A query the parser refuses fails
// "search_query_invalid: ...". `fields` as QsSearchArgs.fields (search_invalid_argument otherwise).
int qs_check_query(const char *query, const QsField *fields, size_t n_fields, char **err);

// The exact top k over the union of `a->segments` (5.2): every matching doc that is live[i] AND
// allowed[i] is scored, with statistics over the population (7.5): live for TABLE, live AND allowed
// for ALLOWED; per field: N, the exact token total and df of each term. A doc's score depends only on
// the doc and the population's statistics: each matching clause's score, summed in clause order
// (groups Tantivy would sum in another order, such as an AND of three terms, are summed by the
// library). `*` matches every doc and scores as Tantivy's boolean scores it: in a group with a
// required clause that is not `*`, every `*` adds 0; when `*` is the only kind of required clause,
// the group scores 1.0 plus its optional clauses; in a group with no required clause, any optional
// `*` adds 1.0 once, after the other optional clauses; a `*` alone scores 1.0. Hits sorted by score
// descending, then rowid ascending; at most k. live_words[i] (and allowed_words[i]) must equal
// ceil(qs_segment_num_docs(segments[i]) / 64), else search_invalid_argument; bits past the doc
// count are ignored. out_cap must be at least k; *n_out receives the count. Hit.segment is the index
// into `segments`. The query and the fields are checked (qs_check_query) before any segment is read.
int qs_search(const QsSearchArgs *a, QsHit *out, size_t out_cap, size_t *n_out, char **err);

// Every matching alive doc of qs_search's arguments (`k` ignored), segment by segment in `segments`
// order and doc order within one, never holding more than one segment's matches at once. The
// arguments are read at the open, which checks them, computes the statistics and copies the bitsets
// (live and allowed may be freed after it); the segments and `cancel` must stay valid until
// qs_matches_free. with_score 1: each hit's score is bit-identical to qs_search's for the same doc;
// with_score 0: score is NAN (SQL NULL). NULL on failure.
QsMatches *qs_matches_open(const QsSearchArgs *a, int with_score, char **err);
// Fills up to out_cap hits (out_cap at least 1, else search_invalid_argument); *n_out 0: done. A
// call the search fails (search_cancelled, a corrupt segment, a panic) leaves the iterator failed:
// every later call fails too, and *n_out is 0 on every failure. The matches are complete only once a
// call returns *n_out 0. Invariant 8 holds at the level of the caller's statement (lead ruling
// 2026-11-17, provisional): a caller that has already passed hits on (qod-node streams one chunk per
// call) fails its statement with this error, so no result built from the iterator is complete or
// reads as success; a caller that holds the hits discards them.
int qs_matches_next(QsMatches *m, QsHit *out, size_t out_cap, size_t *n_out, char **err);
void qs_matches_free(QsMatches *m);

#ifdef QOD_SEARCH_MEASUREMENT
// MEASUREMENT ONLY (Cargo feature `measurement`, never in a shipped binary), never for results:
// qs_search's arguments through Tantivy's block-WAND top k. Its block bounds are chosen under each
// segment's own average length, so under QoD's population statistics it can miss docs of the exact
// top k.
int qs_search_block_wand_measurement_only(const QsSearchArgs *a, QsHit *out, size_t out_cap,
                                          size_t *n_out, char **err);
#endif

#ifdef QOD_SEARCH_TEST_HOOKS
// TEST ONLY (Cargo feature `test-hooks`, set by qod-node's test targets through
// QOD_SEARCH_TEST_HOOKS): the number of per-call segment unions this thread has built, so a test can
// show that a refused call read no segment.
uint64_t qs_test_union_builds(void);
// TEST ONLY: the number of qs_segment_open calls on this thread that read a bundle's footer, which
// only an open whose MAC verified does, so a test can show that a forged bundle reached no parser.
uint64_t qs_test_footer_reads(void);
// TEST ONLY: the bytes the library's Rust code holds on the heap now, every thread's (the feature
// installs a counting global allocator), so a test can measure what a segment adds beyond its bundle.
uint64_t qs_test_allocated_bytes(void);
// TEST ONLY: arms a one-shot hold of the next qs_search: once its statistics are computed and before it
// scores its first segment, it waits for its cancel byte (at most 10 s), so a cancel set meanwhile lands
// inside the call, which answers search_cancelled. qs_test_search_holds counts the holds reached, every
// thread's, so a test can wait until the call is held before it cancels.
void qs_test_hold_next_search(void);
uint64_t qs_test_search_holds(void);
// TEST ONLY: the tokens the TEXT fields' analyzer (the builder's and the query parser's) makes of the
// len UTF-8 bytes at text, in order, each followed by a line feed (no token holds one); free with
// qs_string_free. NULL with *err set on failure. A test reference tokenizes with it and scores alone.
char *qs_test_tokenize(const char *text, size_t len, char **err);
#endif

#ifdef __cplusplus
}
#endif
