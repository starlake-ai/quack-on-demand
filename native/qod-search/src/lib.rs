//! qod_search_core: the search core of QoD Search (spec section 5), linked into qod-node.
//!
//! - `bundle`: the segment bundle of one data file (spec 6.2, without encryption), built and opened
//!   from bytes;
//! - `directory`: the per-call Tantivy directory over the opened bundles (spec 5.2);
//! - `stats`: BM25 statistics over the table or the allowed population (spec 7.5);
//! - `search`: the exact top k over the union (spec 5.2, invariant 7);
//! - `controls`: cancellation and the memory budget of a call (spec 9).
//!
//! The library does no I/O: every byte arrives and leaves through the caller (invariant 9). It
//! builds with `panic = "unwind"` and every `extern "C"` function catches a panic and returns an
//! error (OWNER RULING 2026-11-15, spec 9): Tantivy's own asserts, index panics and `unwrap`s are
//! reachable from crafted bundle bytes. A stack overflow, an allocation failure, a panic while
//! unwinding and undefined behaviour are not caught.
use std::ffi::{c_char, CStr, CString};
use std::fmt;

pub mod bundle;
pub mod controls;
pub mod directory;
mod sha256;
pub mod search;
pub mod stats;

pub use bundle::{Doc, Segment, Source};
pub use search::{Hit, Population};

/// An error with a stable code prefix: `bundle_corrupt`, `bundle_mismatch`, `bundle_build`,
/// `search_field_not_allowed`, `search_query_invalid`, `search_query_too_large`,
/// `search_query_unsupported`, `search_invalid_argument`, `search_cancelled`,
/// `search_memory_exceeded`, `search_internal`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Error {
    code: &'static str,
    detail: String,
}

impl Error {
    pub(crate) fn new(code: &'static str, detail: &str) -> Error {
        Error { code, detail: detail.to_string() }
    }
    pub(crate) fn corrupt(detail: &str) -> Error {
        Error::new("bundle_corrupt", detail)
    }
    pub(crate) fn mismatch(detail: &str) -> Error {
        Error::new("bundle_mismatch", detail)
    }
    pub(crate) fn build(detail: &str) -> Error {
        Error::new("bundle_build", detail)
    }
    pub(crate) fn invalid(detail: &str) -> Error {
        Error::new("search_invalid_argument", detail)
    }
    pub(crate) fn internal(detail: &str) -> Error {
        Error::new("search_internal", detail)
    }
    pub fn code(&self) -> &'static str {
        self.code
    }
    /// `<code>: <detail>`.
    pub fn message(&self) -> String {
        format!("{}: {}", self.code, self.detail)
    }
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}: {}", self.code, self.detail)
    }
}

impl std::error::Error for Error {}

pub type Result<T> = std::result::Result<T, Error>;

// ---- Part 1 link probe, kept (search_link_test calls it) ----

use tantivy::collector::Count;
use tantivy::query::QueryParser;
use tantivy::schema::{Schema, TEXT};
use tantivy::{doc, Index, IndexWriter};

#[no_mangle]
pub extern "C" fn qod_search_probe(docs: u64) -> u64 {
    ffi(std::ptr::null_mut(), "search_internal", u64::MAX, || probe(docs))
}

fn probe(docs: u64) -> u64 {
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

// ---- C ABI (include/qod_search.h) ----
//
// Every function catches a panic (`ffi`): a call that panicked returns its error value with a
// message naming the panic. A builder that panicked is poisoned and refuses every later call.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::AtomicU8;

use controls::Controls;

/// The builder behind `QsBuilder *`; `None` once poisoned by a panic.
pub struct QsBuilder(Option<bundle::Builder<'static>>);
/// The opened segment behind `QsSegment *`.
pub struct QsSegment(Segment);

#[repr(C)]
pub struct QsSource {
    pub index_id: *const c_char,
    pub path_key: *const c_char,
    pub path: *const c_char,
    pub file_size: u64,
    pub footer_size: u64,
    pub record_count: i64,
    pub row_id_start: i64,
    pub has_internal_row_id: i32,
    pub has_internal_snapshot: i32,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct QsHit {
    pub rowid: i64,
    pub segment: u32,
    pub doc: u32,
    pub score: f32,
}

pub const QS_POPULATION_TABLE: i32 = 0;
pub const QS_POPULATION_ALLOWED: i32 = 1;

thread_local! {
    /// True while this thread runs inside an `extern "C"` function of this library.
    static IN_FFI: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
}
static QUIET_HOOK: std::sync::Once = std::sync::Once::new();

/// Installs, once, a panic hook that says nothing for a panic raised inside this library's C ABI
/// (the caller gets the error code instead; nothing reaches the node's stderr) and defers to the
/// previous hook for any other panic of the process.
fn install_quiet_hook() {
    QUIET_HOOK.call_once(|| {
        let previous = std::panic::take_hook();
        std::panic::set_hook(Box::new(move |info| {
            if !IN_FFI.with(|f| f.get()) {
                previous(info);
            }
        }));
    });
}

/// Runs `f`, turning a panic into `<on_panic>: panic` written to `*err` and `fail_value` returned.
/// The panic's own message is not kept: it is neither returned nor printed.
fn ffi<T>(err: *mut *mut c_char, on_panic: &str, fail_value: T, f: impl FnOnce() -> T) -> T {
    install_quiet_hook();
    let was = IN_FFI.with(|q| q.replace(true));
    let r = catch_unwind(AssertUnwindSafe(f));
    IN_FFI.with(|q| q.set(was));
    match r {
        Ok(v) => v,
        Err(_) => {
            // SAFETY: `err` is the caller's out-parameter, NULL or writable (every function's contract).
            unsafe { set_err(err, &format!("{on_panic}: panic")) };
            fail_value
        }
    }
}

/// Writes `e` to `*err` (when `err` is not NULL) and returns 1.
unsafe fn fail(err: *mut *mut c_char, e: &Error) -> i32 {
    set_err(err, &e.message());
    1
}

unsafe fn set_err(err: *mut *mut c_char, msg: &str) {
    if err.is_null() {
        return;
    }
    // A message never holds a NUL byte; strip any so CString cannot fail.
    let clean: String = msg.chars().filter(|c| *c != '\0').collect();
    *err = match CString::new(clean) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    };
}

unsafe fn clear_err(err: *mut *mut c_char) {
    if !err.is_null() {
        *err = std::ptr::null_mut();
    }
}

unsafe fn c_str<'a>(p: *const c_char, what: &str) -> Result<&'a str> {
    if p.is_null() {
        return Err(Error::invalid(&format!("{what} is NULL")));
    }
    CStr::from_ptr(p).to_str().map_err(|_| Error::invalid(&format!("{what} is not UTF-8")))
}

unsafe fn source_from(s: *const QsSource) -> Result<Source> {
    if s.is_null() {
        return Err(Error::invalid("source is NULL"));
    }
    let s = &*s;
    let flag = |v: i32, what: &str| -> Result<i8> {
        if (-1..=1).contains(&v) {
            Ok(v as i8)
        } else {
            Err(Error::invalid(&format!("{what} is -1, 0 or 1")))
        }
    };
    Ok(Source {
        index_id: c_str(s.index_id, "index_id")?.to_string(),
        path_key: c_str(s.path_key, "path_key")?.to_string(),
        path: c_str(s.path, "path")?.to_string(),
        file_size: s.file_size,
        footer_size: s.footer_size,
        record_count: s.record_count,
        row_id_start: s.row_id_start,
        has_internal_row_id: flag(s.has_internal_row_id, "has_internal_row_id")?,
        has_internal_snapshot: flag(s.has_internal_snapshot, "has_internal_snapshot")?,
    })
}

/// The cancel flag behind a caller's pointer (one byte, non-zero = cancelled), for the life of a
/// call or a builder.
unsafe fn cancel_flag(p: *const u8) -> Option<&'static AtomicU8> {
    if p.is_null() {
        None
    } else {
        // SAFETY: the caller keeps the byte alive and aligned for the call or the builder's life
        // (header contract); AtomicU8 has u8's layout.
        Some(&*(p as *const AtomicU8))
    }
}

/// # Safety
/// `s` is NULL or a string this library returned through an `err` parameter, freed once.
#[no_mangle]
pub unsafe extern "C" fn qs_string_free(s: *mut c_char) {
    ffi(std::ptr::null_mut(), "search_internal", (), || {
        if !s.is_null() {
            drop(CString::from_raw(s));
        }
    })
}

/// # Safety
/// `bytes` and `len` are NULL / 0 or exactly what `qs_builder_finish` returned, freed once.
#[no_mangle]
pub unsafe extern "C" fn qs_bytes_free(bytes: *mut u8, len: usize) {
    ffi(std::ptr::null_mut(), "search_internal", (), || {
        if !bytes.is_null() {
            drop(Box::from_raw(std::ptr::slice_from_raw_parts_mut(bytes, len)));
        }
    })
}

/// # Safety
/// `source` points to a QsSource whose strings are NUL-terminated; `cancel` is NULL or a byte that
/// outlives the builder; `err` is NULL or writable.
#[no_mangle]
pub unsafe extern "C" fn qs_builder_new(
    source: *const QsSource,
    cancel: *const u8,
    memory_budget: u64,
    err: *mut *mut c_char,
) -> *mut QsBuilder {
    clear_err(err);
    ffi(err, "bundle_build", std::ptr::null_mut(), || {
        let controls = Controls::new(cancel_flag(cancel), memory_budget);
        match source_from(source).and_then(|s| bundle::Builder::with_controls(&s, controls)) {
            Ok(b) => Box::into_raw(Box::new(QsBuilder(Some(b)))),
            Err(e) => {
                fail(err, &e);
                std::ptr::null_mut()
            }
        }
    })
}

/// # Safety
/// `b` comes from `qs_builder_new` and was not finished; `text` holds `text_len` bytes (NULL only
/// when `text_len` is 0).
#[no_mangle]
pub unsafe extern "C" fn qs_builder_add(
    b: *mut QsBuilder,
    position: u64,
    rowid: i64,
    snapshot_id: i64,
    text: *const c_char,
    text_len: usize,
    err: *mut *mut c_char,
) -> i32 {
    clear_err(err);
    if b.is_null() {
        return fail(err, &Error::invalid("builder is NULL"));
    }
    let slot = &mut (*b).0;
    let r = ffi(err, "bundle_build", None, || {
        let Some(builder) = slot.as_mut() else {
            return Some(fail(err, &Error::build("the builder is poisoned by an earlier panic")));
        };
        let bytes: &[u8] = if text_len == 0 {
            &[]
        } else if text.is_null() {
            return Some(fail(err, &Error::invalid("text is NULL")));
        } else {
            std::slice::from_raw_parts(text as *const u8, text_len)
        };
        let text = match std::str::from_utf8(bytes) {
            Ok(t) => t,
            Err(_) => return Some(fail(err, &Error::build("text is not UTF-8"))),
        };
        Some(match builder.add(position, rowid, snapshot_id, text) {
            Ok(()) => 0,
            Err(e) => fail(err, &e),
        })
    });
    match r {
        Some(rc) => rc,
        None => {
            // A panic: poison the builder (its state is unknown).
            *slot = None;
            1
        }
    }
}

/// Consumes the builder, whether it succeeds or not.
///
/// # Safety
/// `b` comes from `qs_builder_new` and is not used afterwards; the out pointers are writable.
#[no_mangle]
pub unsafe extern "C" fn qs_builder_finish(
    b: *mut QsBuilder,
    bytes: *mut *mut u8,
    len: *mut usize,
    docs: *mut u64,
    err: *mut *mut c_char,
) -> i32 {
    clear_err(err);
    ffi(err, "bundle_build", 1, || {
        if b.is_null() || bytes.is_null() || len.is_null() || docs.is_null() {
            if !b.is_null() {
                drop(Box::from_raw(b));
            }
            return fail(err, &Error::invalid("a NULL argument"));
        }
        *bytes = std::ptr::null_mut();
        *len = 0;
        *docs = 0;
        let Some(builder) = Box::from_raw(b).0 else {
            return fail(err, &Error::build("the builder is poisoned by an earlier panic"));
        };
        match builder.finish() {
            Ok((out, n)) => {
                let boxed = out.into_boxed_slice();
                *len = boxed.len();
                *bytes = Box::into_raw(boxed) as *mut u8;
                *docs = n;
                0
            }
            Err(e) => fail(err, &e),
        }
    })
}

/// # Safety
/// `bytes` holds `len` bytes; `expected` points to a QsSource whose strings are NUL-terminated.
#[no_mangle]
pub unsafe extern "C" fn qs_segment_open(
    bytes: *const u8,
    len: usize,
    expected: *const QsSource,
    err: *mut *mut c_char,
) -> *mut QsSegment {
    clear_err(err);
    ffi(err, "bundle_corrupt", std::ptr::null_mut(), || {
        if bytes.is_null() && len != 0 {
            fail(err, &Error::invalid("bytes is NULL"));
            return std::ptr::null_mut();
        }
        let data: &[u8] = if len == 0 { &[] } else { std::slice::from_raw_parts(bytes, len) };
        match source_from(expected).and_then(|s| bundle::open(data, &s)) {
            Ok(s) => Box::into_raw(Box::new(QsSegment(s))),
            Err(e) => {
                fail(err, &e);
                std::ptr::null_mut()
            }
        }
    })
}

/// # Safety
/// `s` is NULL or comes from `qs_segment_open`, freed once.
#[no_mangle]
pub unsafe extern "C" fn qs_segment_free(s: *mut QsSegment) {
    ffi(std::ptr::null_mut(), "search_internal", (), || {
        if !s.is_null() {
            drop(Box::from_raw(s));
        }
    })
}

/// # Safety
/// `s` is NULL or a live segment.
#[no_mangle]
pub unsafe extern "C" fn qs_segment_num_docs(s: *const QsSegment) -> u32 {
    if s.is_null() {
        0
    } else {
        (*s).0.num_docs()
    }
}

/// # Safety
/// `s` is NULL or a live segment.
#[no_mangle]
pub unsafe extern "C" fn qs_segment_has_explicit_rowids(s: *const QsSegment) -> i32 {
    (!s.is_null() && (*s).0.has_explicit_rowids()) as i32
}

/// -1 for a doc past the segment's docs (or a NULL segment).
///
/// # Safety
/// `s` is NULL or a live segment.
#[no_mangle]
pub unsafe extern "C" fn qs_segment_rowid(s: *const QsSegment, doc: u32) -> i64 {
    if s.is_null() {
        -1
    } else {
        (*s).0.rowid(doc).unwrap_or(-1)
    }
}

/// # Safety
/// `s` is NULL or a live segment.
#[no_mangle]
pub unsafe extern "C" fn qs_segment_snapshot(s: *const QsSegment, doc: u32) -> i64 {
    if s.is_null() {
        -1
    } else {
        (*s).0.snapshot(doc)
    }
}

/// # Safety
/// `s` is NULL or a live segment.
#[no_mangle]
pub unsafe extern "C" fn qs_segment_has_snapshot_column(s: *const QsSegment) -> i32 {
    (!s.is_null() && (*s).0.has_snapshot_column()) as i32
}

/// # Safety
/// `query` and `field` are NUL-terminated; `err` is NULL or writable.
#[no_mangle]
pub unsafe extern "C" fn qs_check_query(query: *const c_char, field: *const c_char, err: *mut *mut c_char) -> i32 {
    clear_err(err);
    ffi(err, "search_internal", 1, || {
        let r = c_str(query, "query").and_then(|q| c_str(field, "field").and_then(|f| search::check_query(q, f)));
        match r {
            Ok(()) => 0,
            Err(e) => fail(err, &e),
        }
    })
}

/// Which collector a C search uses.
#[derive(Clone, Copy)]
enum Path {
    Exact,
    BlockWandMeasurementOnly,
}

#[allow(clippy::too_many_arguments)]
unsafe fn search_c(
    path: Path,
    segments: *const *const QsSegment,
    n_segments: usize,
    live: *const *const u64,
    live_words: *const usize,
    allowed: *const *const u64,
    allowed_words: *const usize,
    population: i32,
    query: *const c_char,
    field: *const c_char,
    k1: f32,
    b: f32,
    k: u32,
    cancel: *const u8,
    memory_budget: u64,
    out: *mut QsHit,
    out_cap: usize,
    n_out: *mut usize,
    err: *mut *mut c_char,
) -> i32 {
    clear_err(err);
    if n_out.is_null() {
        return fail(err, &Error::invalid("n_out is NULL"));
    }
    *n_out = 0;
    ffi(err, "search_internal", 1, || {
        let population = match population {
            QS_POPULATION_TABLE => Population::Table,
            QS_POPULATION_ALLOWED => Population::Allowed,
            _ => return fail(err, &Error::invalid("unknown population")),
        };
        if n_segments > 0 && (segments.is_null() || live.is_null() || live_words.is_null()) {
            return fail(err, &Error::invalid("segments, live or live_words is NULL"));
        }
        if !allowed.is_null() && n_segments > 0 && allowed_words.is_null() {
            return fail(err, &Error::invalid("allowed_words is NULL"));
        }
        if (k as usize) > out_cap || (k > 0 && out.is_null()) {
            return fail(err, &Error::invalid("out holds fewer than k hits"));
        }
        let mut segs: Vec<&Segment> = Vec::with_capacity(n_segments);
        let mut lives: Vec<&[u64]> = Vec::with_capacity(n_segments);
        let mut alloweds: Vec<&[u64]> = Vec::new();
        for i in 0..n_segments {
            let s = *segments.add(i);
            if s.is_null() {
                return fail(err, &Error::invalid("a NULL segment"));
            }
            let seg = &(*s).0;
            let nw = directory::words(seg.num_docs());
            let slice = |p: *const u64, len: usize, what: &str| -> Result<&[u64]> {
                if len != nw {
                    return Err(Error::invalid(&format!(
                        "{what} bitset {i} has {len} words, segment {i} needs {nw} (ceil(num_docs / 64))"
                    )));
                }
                if p.is_null() {
                    return Err(Error::invalid(&format!("{what} bitset {i} is NULL")));
                }
                Ok(std::slice::from_raw_parts(p, len))
            };
            match slice(*live.add(i), *live_words.add(i), "live") {
                Ok(l) => lives.push(l),
                Err(e) => return fail(err, &e),
            }
            if !allowed.is_null() {
                match slice(*allowed.add(i), *allowed_words.add(i), "allowed") {
                    Ok(a) => alloweds.push(a),
                    Err(e) => return fail(err, &e),
                }
            }
            segs.push(seg);
        }
        let controls = Controls::new(cancel_flag(cancel), memory_budget);
        let r = c_str(query, "query").and_then(|q| {
            c_str(field, "field").and_then(|f| match path {
                Path::Exact => {
                    search::top_k_with(&segs, &lives, &alloweds, population, q, f, k1, b, k as usize, &controls)
                }
                Path::BlockWandMeasurementOnly => search::top_k_block_wand_measurement_only(
                    &segs, &lives, &alloweds, population, q, f, k1, b, k as usize, &controls,
                ),
            })
        });
        match r {
            Ok(hits) => {
                let n = hits.len().min(k as usize).min(out_cap);
                for (i, h) in hits.iter().take(n).enumerate() {
                    *out.add(i) = QsHit { rowid: h.rowid, segment: h.segment, doc: h.doc, score: h.score };
                }
                *n_out = n;
                0
            }
            Err(e) => fail(err, &e),
        }
    })
}

/// # Safety
/// `segments` holds `n_segments` live segments; `live` and `live_words` hold `n_segments` entries,
/// `live[i]` pointing to `live_words[i]` words; `allowed` is NULL (every doc allowed) or the same
/// shape as `live` with `allowed_words`; `cancel` is NULL or a byte alive for the call; `out` holds
/// `out_cap` hits; `query` and `field` are NUL-terminated.
#[no_mangle]
pub unsafe extern "C" fn qs_search(
    segments: *const *const QsSegment,
    n_segments: usize,
    live: *const *const u64,
    live_words: *const usize,
    allowed: *const *const u64,
    allowed_words: *const usize,
    population: i32,
    query: *const c_char,
    field: *const c_char,
    k1: f32,
    b: f32,
    k: u32,
    cancel: *const u8,
    memory_budget: u64,
    out: *mut QsHit,
    out_cap: usize,
    n_out: *mut usize,
    err: *mut *mut c_char,
) -> i32 {
    search_c(
        Path::Exact,
        segments,
        n_segments,
        live,
        live_words,
        allowed,
        allowed_words,
        population,
        query,
        field,
        k1,
        b,
        k,
        cancel,
        memory_budget,
        out,
        out_cap,
        n_out,
        err,
    )
}

/// MEASUREMENT ONLY: `qs_search`'s arguments, Tantivy's block-WAND top k (can miss docs of the
/// exact top k under QoD's statistics). Never used for results.
///
/// # Safety
/// As `qs_search`.
#[no_mangle]
pub unsafe extern "C" fn qs_search_block_wand_measurement_only(
    segments: *const *const QsSegment,
    n_segments: usize,
    live: *const *const u64,
    live_words: *const usize,
    allowed: *const *const u64,
    allowed_words: *const usize,
    population: i32,
    query: *const c_char,
    field: *const c_char,
    k1: f32,
    b: f32,
    k: u32,
    cancel: *const u8,
    memory_budget: u64,
    out: *mut QsHit,
    out_cap: usize,
    n_out: *mut usize,
    err: *mut *mut c_char,
) -> i32 {
    search_c(
        Path::BlockWandMeasurementOnly,
        segments,
        n_segments,
        live,
        live_words,
        allowed,
        allowed_words,
        population,
        query,
        field,
        k1,
        b,
        k,
        cancel,
        memory_budget,
        out,
        out_cap,
        n_out,
        err,
    )
}
