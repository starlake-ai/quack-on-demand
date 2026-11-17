//! qod_search_core: the search core of QoD Search (spec section 5), linked into qod-node.
//!
//! - `bundle`: the segment bundle of one data file for one index of 1 to 32 text fields (spec 6.2,
//!   format 3, without encryption, authenticated by a keyed MAC), built and opened from bytes;
//! - `directory`: the per-call Tantivy directory over the opened bundles (spec 5.2);
//! - `stats`: BM25 statistics over the table or the allowed population, per field (spec 7.5);
//! - `search`: the query grammar with its column to field remap, and the exact top k over the union
//!   (spec 5.2, invariant 7);
//! - `matches`: every matching alive doc of a search, streamed (spec 7.1 steps 6 and 7);
//! - `controls`: cancellation and the memory budget of a call (spec 9);
//! - `sha256`: SHA-256 for the path key and HMAC-SHA256 for the bundle MAC (spec 6.2, 13).
//!
//! The library does no I/O: every byte arrives and leaves through the caller (invariant 9). It
//! builds with `panic = "unwind"` and every `extern "C"` function catches a panic and returns an
//! error (OWNER RULING 2026-11-15, spec 9): Tantivy's own asserts, index panics and `unwrap`s are
//! reachable from crafted bundle bytes. A stack overflow, an allocation failure, a panic while
//! unwinding and undefined behaviour are not caught.
//!
//! The C ABI below is declared by the hand-written include/qod_search.h (OWNER RULING 2026-11-16,
//! D6); tests/header_parity.rs reads both files and fails on any difference, so every exported
//! function, `#[repr(C)]` struct and `QS_` constant is written here in the plain form that test
//! scans: one `#[no_mangle]` per function, `c_int` for C's `int`, no comment inside a struct body.
use std::ffi::{c_char, c_int, CStr, CString};
use std::fmt;

pub mod bundle;
pub mod controls;
pub mod directory;
pub mod matches;
pub mod sha256;
pub mod search;
pub mod stats;
#[cfg(feature = "test-hooks")]
pub mod test_alloc;

pub use bundle::{Doc, MacKey, Segment, Source};
pub use search::{Hit, Population};

/// An error with a stable code prefix: `bundle_corrupt`, `bundle_mismatch`, `bundle_mac_mismatch`,
/// `bundle_build`, `search_field_not_allowed`, `search_query_invalid`, `search_query_too_large`,
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

// ---- C ABI (include/qod_search.h) ----
//
// Every function catches a panic (`ffi`): a call that panicked returns its error value with a
// message naming the panic. A builder or a matches iterator that panicked is poisoned and refuses
// every later call.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::AtomicU8;

use controls::Controls;

/// The bundle format this library writes and reads (`QS_BUNDLE_FORMAT_VERSION`).
pub const QS_BUNDLE_FORMAT_VERSION: u32 = 3;
const _: () = assert!(QS_BUNDLE_FORMAT_VERSION == bundle::FORMAT_VERSION);

pub const QS_POPULATION_TABLE: c_int = 0;
pub const QS_POPULATION_ALLOWED: c_int = 1;
/// The only search path until Task 15 (deferred out of Phase 1, OWNER RULING 2026-11-16).
pub const QS_PATH_EXHAUSTIVE: c_int = 0;

/// The builder behind `QsBuilder *`; `None` once poisoned by a panic.
pub struct QsBuilder(Option<bundle::Builder<'static>>);
/// The opened segment behind `QsSegment *`.
pub struct QsSegment(Segment);
/// The iterator behind `QsMatches *`; `None` once poisoned by a panic. It borrows the caller's
/// segments and cancel flag, which the header requires to outlive it.
pub struct QsMatches(Option<matches::Matches<'static>>);

#[repr(C)]
#[derive(Clone, Copy)]
pub struct QsField {
    pub column: *const c_char,
    pub field_id: i64,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct QsText {
    pub text: *const c_char,
    pub len: usize,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct QsSource {
    pub index_id: *const c_char,
    pub path_key: *const c_char,
    pub path: *const c_char,
    pub file_size: u64,
    pub footer_size: u64,
    pub table_id: i64,
    pub fields: *const QsField,
    pub n_fields: usize,
    pub record_count: i64,
    pub row_id_start: i64,
    pub has_internal_row_id: i32,
    pub has_internal_snapshot: i32,
    pub mac_key: *const u8,
    pub mac_key_len: usize,
}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct QsHit {
    pub rowid: i64,
    pub segment: u32,
    pub doc: u32,
    pub score: f32,
}

#[repr(C)]
pub struct QsSearchArgs {
    pub segments: *const *const QsSegment,
    pub n_segments: usize,
    pub live: *const *const u64,
    pub live_words: *const usize,
    pub allowed: *const *const u64,
    pub allowed_words: *const usize,
    pub population: c_int,
    pub query: *const c_char,
    pub fields: *const QsField,
    pub n_fields: usize,
    pub k1: f32,
    pub b: f32,
    pub k: u32,
    pub path: c_int,
    pub cancel: *const u8,
    pub memory_budget: u64,
}

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
unsafe fn fail(err: *mut *mut c_char, e: &Error) -> c_int {
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

/// The fields behind a `QsField` array: (column, field id), not yet checked as a list.
unsafe fn fields_from<'a>(p: *const QsField, n: usize) -> Result<Vec<(&'a str, i64)>> {
    if n > bundle::MAX_FIELDS {
        return Err(Error::invalid(&format!("more than {} fields", bundle::MAX_FIELDS)));
    }
    if n > 0 && p.is_null() {
        return Err(Error::invalid("fields is NULL"));
    }
    (0..n)
        .map(|i| {
            let f = &*p.add(i);
            c_str(f.column, "a field's column").map(|c| (c, f.field_id))
        })
        .collect()
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
    let fields = fields_from(s.fields, s.n_fields)?;
    if s.mac_key.is_null() {
        return Err(Error::invalid("mac_key is NULL"));
    }
    if s.mac_key_len != bundle::MAC_LEN {
        return Err(Error::invalid(&format!("a bundle MAC key is {} bytes, not {}", bundle::MAC_LEN, s.mac_key_len)));
    }
    // SAFETY: the caller's mac_key holds mac_key_len (32) bytes for the call (header contract).
    // MacKey::new refuses an all-zero key, for the builder and the open alike.
    let mac_key = MacKey::new(std::slice::from_raw_parts(s.mac_key, s.mac_key_len))?;
    Ok(Source {
        index_id: c_str(s.index_id, "index_id")?.to_string(),
        path_key: c_str(s.path_key, "path_key")?.to_string(),
        path: c_str(s.path, "path")?.to_string(),
        file_size: s.file_size,
        footer_size: s.footer_size,
        table_id: s.table_id,
        fields: fields.iter().map(|(c, id)| (c.to_string(), *id)).collect(),
        record_count: s.record_count,
        row_id_start: s.row_id_start,
        has_internal_row_id: flag(s.has_internal_row_id, "has_internal_row_id")?,
        has_internal_snapshot: flag(s.has_internal_snapshot, "has_internal_snapshot")?,
        mac_key,
    })
}

/// The cancel flag behind a caller's pointer (one byte, non-zero = cancelled), for the life of a
/// call, a builder or a matches iterator.
unsafe fn cancel_flag(p: *const u8) -> Option<&'static AtomicU8> {
    if p.is_null() {
        None
    } else {
        // SAFETY: the caller keeps the byte alive and aligned for the call, the builder's or the
        // iterator's life (header contract); AtomicU8 has u8's layout.
        Some(&*(p as *const AtomicU8))
    }
}

/// A `QsSearchArgs` read once: the query and its fields first, checked before any segment pointer
/// is read (rule 6), then the scalars, then the segments and their bitsets.
struct Args<'a> {
    segments: Vec<&'a Segment>,
    live: Vec<&'a [u64]>,
    allowed: Vec<&'a [u64]>,
    population: Population,
    query: &'a str,
    fields: Vec<(&'a str, i64)>,
    k1: f32,
    b: f32,
    k: u32,
    cancel: Option<&'static AtomicU8>,
    memory_budget: u64,
}

unsafe fn args_from<'a>(a: *const QsSearchArgs) -> Result<Args<'a>> {
    if a.is_null() {
        return Err(Error::invalid("the search arguments are NULL"));
    }
    let a = &*a;
    let query = c_str(a.query, "query")?;
    let fields = fields_from(a.fields, a.n_fields)?;
    search::check_query(query, &fields)?;
    let population = match a.population {
        QS_POPULATION_TABLE => Population::Table,
        QS_POPULATION_ALLOWED => Population::Allowed,
        _ => return Err(Error::invalid("unknown population")),
    };
    if a.path != QS_PATH_EXHAUSTIVE {
        return Err(Error::invalid("unknown search path (QS_PATH_EXHAUSTIVE is the only one)"));
    }
    if a.k1 != search::K1 || a.b != search::B {
        return Err(Error::invalid("k1 and b other than 1.2 and 0.75 are not supported through Phase 1"));
    }
    let n = a.n_segments;
    if n > 0 && (a.segments.is_null() || a.live.is_null() || a.live_words.is_null()) {
        return Err(Error::invalid("segments, live or live_words is NULL"));
    }
    if !a.allowed.is_null() && n > 0 && a.allowed_words.is_null() {
        return Err(Error::invalid("allowed_words is NULL"));
    }
    let mut segments: Vec<&Segment> = Vec::with_capacity(n);
    let mut live: Vec<&[u64]> = Vec::with_capacity(n);
    let mut allowed: Vec<&[u64]> = Vec::new();
    for i in 0..n {
        let s = *a.segments.add(i);
        if s.is_null() {
            return Err(Error::invalid("a NULL segment"));
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
        live.push(slice(*a.live.add(i), *a.live_words.add(i), "live")?);
        if !a.allowed.is_null() {
            allowed.push(slice(*a.allowed.add(i), *a.allowed_words.add(i), "allowed")?);
        }
        segments.push(seg);
    }
    Ok(Args {
        segments,
        live,
        allowed,
        population,
        query,
        fields,
        k1: a.k1,
        b: a.b,
        k: a.k,
        cancel: cancel_flag(a.cancel),
        memory_budget: a.memory_budget,
    })
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
/// `source` points to a QsSource whose strings are NUL-terminated and whose `fields` hold
/// `n_fields` entries; `cancel` is NULL or a byte that outlives the builder; `err` is NULL or
/// writable.
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
/// `b` comes from `qs_builder_new` and was not finished; `texts` holds `n_texts` entries, each text
/// holding `len` bytes (NULL only when `len` is 0).
#[no_mangle]
pub unsafe extern "C" fn qs_builder_add(
    b: *mut QsBuilder,
    position: u64,
    rowid: i64,
    snapshot_id: i64,
    texts: *const QsText,
    n_texts: usize,
    err: *mut *mut c_char,
) -> c_int {
    clear_err(err);
    if b.is_null() {
        return fail(err, &Error::invalid("builder is NULL"));
    }
    let slot = &mut (*b).0;
    let r = ffi(err, "bundle_build", None, || {
        let Some(builder) = slot.as_mut() else {
            return Some(fail(err, &Error::build("the builder is poisoned by an earlier panic")));
        };
        if n_texts > 0 && texts.is_null() {
            return Some(fail(err, &Error::invalid("texts is NULL")));
        }
        let mut values: Vec<Option<&str>> = Vec::with_capacity(n_texts);
        for i in 0..n_texts {
            let t = &*texts.add(i);
            if t.text.is_null() {
                if t.len != 0 {
                    return Some(fail(err, &Error::invalid("a text is NULL with a length")));
                }
                values.push(None);
                continue;
            }
            let bytes: &[u8] = if t.len == 0 { &[] } else { std::slice::from_raw_parts(t.text as *const u8, t.len) };
            match std::str::from_utf8(bytes) {
                Ok(s) => values.push(Some(s)),
                Err(_) => return Some(fail(err, &Error::build("text is not UTF-8"))),
            }
        }
        Some(match builder.add(position, rowid, snapshot_id, &values) {
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

/// What the builder holds now, in bytes: the figure its memory budget is checked against (the writer's arena
/// and its rowid, snapshot and token count columns). 0 for NULL or a poisoned builder.
///
/// # Safety
/// `b` is NULL or comes from `qs_builder_new` and was not finished.
#[no_mangle]
pub unsafe extern "C" fn qs_builder_memory(b: *const QsBuilder) -> u64 {
    ffi(std::ptr::null_mut(), "bundle_build", 0, || {
        if b.is_null() {
            return 0;
        }
        (*b).0.as_ref().map(|builder| builder.memory()).unwrap_or(0)
    })
}

/// The most one `qs_builder_add` of `texts` may allocate beyond `qs_builder_memory(b)` (the builder's own
/// bound before an add, from the texts' lengths alone); the add is refused, allocating nothing that grows with the
/// texts, when the two pass its budget. 0 for NULL or a poisoned builder.
///
/// # Safety
/// `b` is NULL or comes from `qs_builder_new` and was not finished; `texts` is NULL or holds `n_texts` entries.
#[no_mangle]
pub unsafe extern "C" fn qs_builder_add_bound(b: *const QsBuilder, texts: *const QsText, n_texts: usize) -> u64 {
    ffi(std::ptr::null_mut(), "bundle_build", 0, || {
        if b.is_null() {
            return 0;
        }
        let mut bytes = 0u64;
        if !texts.is_null() {
            for i in 0..n_texts {
                bytes = bytes.saturating_add((*texts.add(i)).len as u64);
            }
        }
        (*b).0.as_ref().map(|builder| builder.add_bound(bytes, n_texts)).unwrap_or(0)
    })
}

/// The most `qs_builder_finish` may allocate beyond `qs_builder_memory(b)`; the finish is refused before the
/// writer is finalized when the two pass its budget. 0 for NULL or a poisoned builder.
///
/// # Safety
/// `b` is NULL or comes from `qs_builder_new` and was not finished.
#[no_mangle]
pub unsafe extern "C" fn qs_builder_finish_bound(b: *const QsBuilder) -> u64 {
    ffi(std::ptr::null_mut(), "bundle_build", 0, || {
        if b.is_null() {
            return 0;
        }
        (*b).0.as_ref().map(|builder| builder.finish_bound()).unwrap_or(0)
    })
}

/// Replaces the builder's memory budget, so that a caller bounding several builders jointly can set each one's to
/// what is left before each call. 0 on success; non-zero for NULL or a poisoned builder.
///
/// # Safety
/// `b` is NULL or comes from `qs_builder_new` and was not finished.
#[no_mangle]
pub unsafe extern "C" fn qs_builder_set_budget(b: *mut QsBuilder, memory_budget: u64) -> c_int {
    ffi(std::ptr::null_mut(), "bundle_build", 1, || {
        if b.is_null() {
            return 1;
        }
        match (*b).0.as_mut() {
            Some(builder) => {
                builder.set_budget(memory_budget);
                0
            }
            None => 1,
        }
    })
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
) -> c_int {
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

/// As `qs_segment_open`, but the segment reads `bytes` in place: nothing of the bundle is copied (Task 12).
///
/// # Safety
/// As `qs_segment_open`; and `bytes` must stay valid and unchanged until `qs_segment_free` of the result (every
/// matches iterator over it freed first, as the header requires).
#[no_mangle]
pub unsafe extern "C" fn qs_segment_open_borrowed(
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
        match source_from(expected).and_then(|s| bundle::open_borrowed(data, &s)) {
            Ok(s) => Box::into_raw(Box::new(QsSegment(s))),
            Err(e) => {
                fail(err, &e);
                std::ptr::null_mut()
            }
        }
    })
}

/// What `s` holds on the heap besides the bundle's bytes (0 for NULL): see `Segment::heap_bytes`.
///
/// # Safety
/// `s` is NULL or a live segment.
#[no_mangle]
pub unsafe extern "C" fn qs_segment_heap_bytes(s: *const QsSegment) -> u64 {
    ffi(std::ptr::null_mut(), "search_internal", 0, || if s.is_null() { 0 } else { (*s).0.heap_bytes() })
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
    ffi(std::ptr::null_mut(), "search_internal", 0, || if s.is_null() { 0 } else { (*s).0.num_docs() })
}

/// # Safety
/// `s` is NULL or a live segment.
#[no_mangle]
pub unsafe extern "C" fn qs_segment_has_explicit_rowids(s: *const QsSegment) -> c_int {
    ffi(std::ptr::null_mut(), "search_internal", 0, || (!s.is_null() && (*s).0.has_explicit_rowids()) as c_int)
}

/// -1 for a doc past the segment's docs (or a NULL segment).
///
/// # Safety
/// `s` is NULL or a live segment.
#[no_mangle]
pub unsafe extern "C" fn qs_segment_rowid(s: *const QsSegment, doc: u32) -> i64 {
    ffi(std::ptr::null_mut(), "search_internal", -1, || if s.is_null() { -1 } else { (*s).0.rowid(doc).unwrap_or(-1) })
}

/// # Safety
/// `s` is NULL or a live segment.
#[no_mangle]
pub unsafe extern "C" fn qs_segment_snapshot(s: *const QsSegment, doc: u32) -> i64 {
    ffi(std::ptr::null_mut(), "search_internal", -1, || if s.is_null() { -1 } else { (*s).0.snapshot(doc) })
}

/// # Safety
/// `s` is NULL or a live segment.
#[no_mangle]
pub unsafe extern "C" fn qs_segment_has_snapshot_column(s: *const QsSegment) -> c_int {
    ffi(std::ptr::null_mut(), "search_internal", 0, || (!s.is_null() && (*s).0.has_snapshot_column()) as c_int)
}

/// # Safety
/// `query` is NUL-terminated; `fields` holds `n_fields` entries; `err` is NULL or writable.
#[no_mangle]
pub unsafe extern "C" fn qs_check_query(
    query: *const c_char,
    fields: *const QsField,
    n_fields: usize,
    err: *mut *mut c_char,
) -> c_int {
    clear_err(err);
    ffi(err, "search_internal", 1, || {
        let r = c_str(query, "query")
            .and_then(|q| fields_from(fields, n_fields).and_then(|f| search::check_query(q, &f)));
        match r {
            Ok(()) => 0,
            Err(e) => fail(err, &e),
        }
    })
}

/// Which collector a C search uses.
#[derive(Clone, Copy)]
enum Collector {
    Exact,
    #[cfg(feature = "measurement")]
    BlockWandMeasurementOnly,
}

unsafe fn search_c(
    collector: Collector,
    a: *const QsSearchArgs,
    out: *mut QsHit,
    out_cap: usize,
    n_out: *mut usize,
    err: *mut *mut c_char,
) -> c_int {
    clear_err(err);
    if n_out.is_null() {
        return fail(err, &Error::invalid("n_out is NULL"));
    }
    *n_out = 0;
    ffi(err, "search_internal", 1, || {
        let args = match args_from(a) {
            Ok(args) => args,
            Err(e) => return fail(err, &e),
        };
        let k = args.k;
        if k == 0 || k as usize > search::MAX_K {
            return fail(err, &Error::invalid(&format!("k is 1 to {}", search::MAX_K)));
        }
        if (k as usize) > out_cap || out.is_null() {
            return fail(err, &Error::invalid("out holds fewer than k hits"));
        }
        let controls = Controls::new(args.cancel, args.memory_budget);
        let r = match collector {
            Collector::Exact => search::top_k_with(
                &args.segments,
                &args.live,
                &args.allowed,
                args.population,
                args.query,
                &args.fields,
                args.k1,
                args.b,
                k as usize,
                &controls,
            ),
            #[cfg(feature = "measurement")]
            Collector::BlockWandMeasurementOnly => search::top_k_block_wand_measurement_only(
                &args.segments,
                &args.live,
                &args.allowed,
                args.population,
                args.query,
                &args.fields,
                args.k1,
                args.b,
                k as usize,
                &controls,
            ),
        };
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
/// `a` points to a QsSearchArgs whose pointers are valid for the call (header contract); `out`
/// holds `out_cap` hits.
#[no_mangle]
pub unsafe extern "C" fn qs_search(
    a: *const QsSearchArgs,
    out: *mut QsHit,
    out_cap: usize,
    n_out: *mut usize,
    err: *mut *mut c_char,
) -> c_int {
    search_c(Collector::Exact, a, out, out_cap, n_out, err)
}

/// MEASUREMENT ONLY (feature `measurement`): `qs_search`'s arguments, Tantivy's block-WAND top k
/// (can miss docs of the exact top k under QoD's statistics). Never used for results.
///
/// # Safety
/// As `qs_search`.
#[cfg(feature = "measurement")]
#[no_mangle]
pub unsafe extern "C" fn qs_search_block_wand_measurement_only(
    a: *const QsSearchArgs,
    out: *mut QsHit,
    out_cap: usize,
    n_out: *mut usize,
    err: *mut *mut c_char,
) -> c_int {
    search_c(Collector::BlockWandMeasurementOnly, a, out, out_cap, n_out, err)
}

/// # Safety
/// `a` points to a QsSearchArgs valid for the call; its segments and its cancel byte stay valid
/// until `qs_matches_free` (its bitsets are copied).
#[no_mangle]
pub unsafe extern "C" fn qs_matches_open(
    a: *const QsSearchArgs,
    with_score: c_int,
    err: *mut *mut c_char,
) -> *mut QsMatches {
    clear_err(err);
    ffi(err, "search_internal", std::ptr::null_mut(), || {
        let r = args_from(a).and_then(|args| {
            if with_score != 0 && with_score != 1 {
                return Err(Error::invalid("with_score is 0 or 1"));
            }
            let controls = Controls::new(args.cancel, args.memory_budget);
            // The segments are borrowed for 'static: the header has the caller keep them alive until
            // qs_matches_free.
            matches::Matches::open(
                &args.segments,
                &args.live,
                &args.allowed,
                args.population,
                args.query,
                &args.fields,
                args.k1,
                args.b,
                controls,
                with_score == 1,
            )
        });
        match r {
            Ok(m) => Box::into_raw(Box::new(QsMatches(Some(m)))),
            Err(e) => {
                fail(err, &e);
                std::ptr::null_mut()
            }
        }
    })
}

/// # Safety
/// `m` comes from `qs_matches_open` and was not freed; `out` holds `out_cap` hits.
#[no_mangle]
pub unsafe extern "C" fn qs_matches_next(
    m: *mut QsMatches,
    out: *mut QsHit,
    out_cap: usize,
    n_out: *mut usize,
    err: *mut *mut c_char,
) -> c_int {
    clear_err(err);
    if n_out.is_null() {
        return fail(err, &Error::invalid("n_out is NULL"));
    }
    *n_out = 0;
    if m.is_null() {
        return fail(err, &Error::invalid("the iterator is NULL"));
    }
    if out_cap == 0 || out.is_null() {
        return fail(err, &Error::invalid("out holds no hit"));
    }
    let slot = &mut (*m).0;
    let r = ffi(err, "search_internal", None, || {
        let Some(it) = slot.as_mut() else {
            return Some(fail(err, &Error::internal("the iterator is poisoned by an earlier panic")));
        };
        // Hits go straight into the caller's buffer; on a failure *n_out stays 0.
        let r = it.fill_with(out_cap, &mut |i, h: Hit| {
            *out.add(i) = QsHit { rowid: h.rowid, segment: h.segment, doc: h.doc, score: h.score };
        });
        Some(match r {
            Ok(n) => {
                *n_out = n;
                0
            }
            Err(e) => fail(err, &e),
        })
    });
    match r {
        Some(rc) => rc,
        None => {
            // A panic: poison the iterator (its state is unknown).
            *slot = None;
            1
        }
    }
}

/// # Safety
/// `m` is NULL or comes from `qs_matches_open`, freed once.
#[no_mangle]
pub unsafe extern "C" fn qs_matches_free(m: *mut QsMatches) {
    ffi(std::ptr::null_mut(), "search_internal", (), || {
        if !m.is_null() {
            drop(Box::from_raw(m));
        }
    })
}

/// TEST ONLY (feature `test-hooks`): the bytes the library's code holds on the heap now, every thread's, from
/// the counting allocator the feature installs (`test_alloc`).
#[cfg(feature = "test-hooks")]
#[no_mangle]
pub extern "C" fn qs_test_allocated_bytes() -> u64 {
    ffi(std::ptr::null_mut(), "search_internal", 0, test_alloc::total)
}

/// TEST ONLY (feature `test-hooks`): arms a one-shot hold of the next `qs_search` (`controls::test_hold`): once
/// its statistics are computed and before it scores its first segment, it waits for its cancel flag (at most
/// 10 s), so a cancel set meanwhile lands inside the call and it answers `search_cancelled`.
#[cfg(feature = "test-hooks")]
#[no_mangle]
pub extern "C" fn qs_test_hold_next_search() {
    ffi(std::ptr::null_mut(), "search_internal", (), || {
        controls::test_hold::ARMED.store(true, std::sync::atomic::Ordering::SeqCst)
    })
}

/// TEST ONLY (feature `test-hooks`): the holds `qs_test_hold_next_search` armed that a search has reached, every
/// thread's.
#[cfg(feature = "test-hooks")]
#[no_mangle]
pub extern "C" fn qs_test_search_holds() -> u64 {
    ffi(std::ptr::null_mut(), "search_internal", 0, || {
        controls::test_hold::REACHED.load(std::sync::atomic::Ordering::SeqCst)
    })
}

/// TEST ONLY (feature `test-hooks`): the per-call segment unions this thread has built.
#[cfg(feature = "test-hooks")]
#[no_mangle]
pub extern "C" fn qs_test_union_builds() -> u64 {
    ffi(std::ptr::null_mut(), "search_internal", 0, || directory::UNION_BUILDS.with(|c| c.get()))
}

/// TEST ONLY (feature `test-hooks`): the opens on this thread that read a bundle's footer, that is
/// whose MAC verified.
#[cfg(feature = "test-hooks")]
#[no_mangle]
pub extern "C" fn qs_test_footer_reads() -> u64 {
    ffi(std::ptr::null_mut(), "search_internal", 0, || bundle::FOOTER_READS.with(|c| c.get()))
}

/// TEST ONLY (feature `test-hooks`): the tokens the TEXT fields' analyzer (Tantivy's default, as the builder
/// and the query parser use it) makes of the `len` bytes at `text` (UTF-8), in order, each followed by a
/// line feed (the analyzer splits on every non-alphanumeric character, so no token holds one); free with
/// `qs_string_free`. NULL with `*err` set on failure. A test's reference tokenizes with it and scores alone.
///
/// # Safety
/// `text` points to `len` readable bytes (or is NULL with `len` 0); `err` is NULL or writable.
#[cfg(feature = "test-hooks")]
#[no_mangle]
pub unsafe extern "C" fn qs_test_tokenize(text: *const c_char, len: usize, err: *mut *mut c_char) -> *mut c_char {
    ffi(err, "search_internal", std::ptr::null_mut(), || {
        clear_err(err);
        if len > 0 && text.is_null() {
            fail(err, &Error::invalid("text is NULL"));
            return std::ptr::null_mut();
        }
        let bytes: &[u8] = if len == 0 { &[] } else { std::slice::from_raw_parts(text as *const u8, len) };
        let Ok(text) = std::str::from_utf8(bytes) else {
            fail(err, &Error::invalid("text is not UTF-8"));
            return std::ptr::null_mut();
        };
        let Some(mut analyzer) = tantivy::tokenizer::TokenizerManager::default().get("default") else {
            fail(err, &Error::internal("no default tokenizer"));
            return std::ptr::null_mut();
        };
        let mut out = String::new();
        let mut stream = analyzer.token_stream(text);
        while stream.advance() {
            out.push_str(&stream.token().text);
            out.push('\n');
        }
        match CString::new(out) {
            Ok(s) => s.into_raw(),
            Err(_) => {
                fail(err, &Error::internal("a token holds a NUL byte"));
                std::ptr::null_mut()
            }
        }
    })
}
