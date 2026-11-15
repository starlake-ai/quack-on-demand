//! The segment bundle (spec 6.2): round trip, refusals of corrupt and mismatched bundles,
//! determinism, and the C ABI over it.
mod common;
use common::*;
use qod_search_core::bundle::{self, path_key, Doc, Source};
use std::ffi::{CStr, CString};

const PATH: &str = "/lake/main/pages/ducklake-0193.parquet";

fn docs(n: usize, rowid: impl Fn(usize) -> i64, snapshot: impl Fn(usize) -> i64) -> Vec<Doc> {
    (0..n)
        .map(|p| Doc { position: p as u64, rowid: rowid(p), snapshot: snapshot(p), text: page_text(p as u64) })
        .collect()
}

fn le_u64(b: &[u8], at: usize) -> u64 {
    u64::from_le_bytes(b[at..at + 8].try_into().unwrap())
}

/// The regions of a bundle as its footer lists them: (name, offset, len), then the footer body
/// range. Parsed here from the documented layout, independently of the library.
fn layout(b: &[u8]) -> (Vec<(String, usize, usize)>, std::ops::Range<usize>) {
    let t = b.len() - 28;
    let fo = le_u64(b, t) as usize;
    let f = &b[fo..t];
    let mut at = 8 + 4;
    let skip_str16 = |at: &mut usize| {
        let n = u16::from_le_bytes([f[*at], f[*at + 1]]) as usize;
        *at += 2 + n;
    };
    skip_str16(&mut at); // index_id
    skip_str16(&mut at); // path_key
    let n = u32::from_le_bytes(f[at..at + 4].try_into().unwrap()) as usize;
    at += 4 + n; // path
    at += 8 + 8 + 4 + 1; // sizes, num_docs, flags
    let nr = u32::from_le_bytes(f[at..at + 4].try_into().unwrap()) as usize;
    at += 4;
    let mut regions = Vec::new();
    for _ in 0..nr {
        at += 1;
        let ln = u16::from_le_bytes([f[at], f[at + 1]]) as usize;
        let name = String::from_utf8(f[at + 2..at + 2 + ln].to_vec()).unwrap();
        at += 2 + ln;
        let off = le_u64(f, at) as usize;
        let len = le_u64(f, at + 8) as usize;
        at += 8 + 8 + 4;
        regions.push((name, off, len));
    }
    assert_eq!(at, f.len());
    (regions, fo..t)
}

/// Rewrites the footer body's CRC after a test edited the footer.
fn reseal_footer(b: &mut [u8]) {
    let t = b.len() - 28;
    let fo = le_u64(b, t) as usize;
    let crc = crc32fast::hash(&b[fo..t]);
    b[t + 8..t + 12].copy_from_slice(&crc.to_le_bytes());
}

fn err_of(bytes: &[u8], src: &Source) -> String {
    match bundle::open(bytes, src) {
        Ok(_) => panic!("opened"),
        Err(e) => e.message(),
    }
}

#[test]
fn round_trip_affine_rowids_without_snapshots() {
    let src = source_for(PATH);
    let bytes = bundle::build(&src, docs(3000, |p| 7_000 + p as i64, |_| -1)).unwrap();
    let seg = bundle::open(&bytes, &src).unwrap();
    assert_eq!(seg.num_docs(), 3000);
    assert!(!seg.has_explicit_rowids());
    assert!(!seg.has_snapshot_column());
    for d in [0u32, 1, 1234, 2999] {
        assert_eq!(seg.rowid(d), Some(7_000 + d as i64));
        assert_eq!(seg.snapshot(d), -1);
    }
    assert_eq!(seg.rowid(3000), None);
    assert_eq!(seg.source(), &src);
    let exact: Vec<u32> = (0..3000).map(|p| tokens(&page_text(p)).len() as u32).collect();
    assert_eq!(seg.token_counts(), exact.as_slice());
}

#[test]
fn round_trip_explicit_rowids_repeated_unsorted_with_snapshots() {
    // Q1: a rowid can repeat inside one file, and a merged file's rowids are not sorted.
    let rowid = |p: usize| [42i64, 7, 42, 1_000_000, 3, 7][p % 6] + (p / 6) as i64 * 10;
    let snapshot = |p: usize| 5 + (p % 4) as i64;
    let src = source_for(PATH);
    let bytes = bundle::build(&src, docs(500, rowid, snapshot)).unwrap();
    let seg = bundle::open(&bytes, &src).unwrap();
    assert_eq!(seg.num_docs(), 500);
    assert!(seg.has_explicit_rowids());
    assert!(seg.has_snapshot_column());
    for d in 0..500u32 {
        assert_eq!(seg.rowid(d), Some(rowid(d as usize)));
        assert_eq!(seg.snapshot(d), snapshot(d as usize));
    }
    assert_eq!(seg.snapshot(500), -1);
}

#[test]
fn builds_are_deterministic() {
    let src = source_for(PATH);
    let a = bundle::build(&src, docs(2000, |p| p as i64, |p| p as i64 % 3)).unwrap();
    let b = bundle::build(&src, docs(2000, |p| p as i64, |p| p as i64 % 3)).unwrap();
    assert_eq!(a, b);
}

#[test]
fn a_flipped_byte_in_any_region_is_corrupt() {
    let src = source_for(PATH);
    let bytes = bundle::build(&src, docs(800, |p| (p * 3) as i64, |p| p as i64)).unwrap();
    let (regions, footer) = layout(&bytes);
    let names: Vec<&str> = regions.iter().map(|r| r.0.as_str()).collect();
    println!("regions: {names:?}; bundle {} bytes", bytes.len());
    for want in ["header", "rowids", "snapshots", "token_counts", "idx", "term", "fieldnorm", "fast", "store"] {
        assert!(names.contains(&want), "no region {want}");
    }
    let mut spots: Vec<(String, usize)> = Vec::new();
    for (name, off, len) in &regions {
        for at in [*off, off + len / 2, off + len - 1] {
            spots.push((name.clone(), at));
        }
    }
    for at in [footer.start, footer.start + 20, footer.end - 1] {
        spots.push(("footer".into(), at));
    }
    let t = bytes.len() - 28;
    for (what, at) in [("trailer offset", t), ("trailer crc", t + 9), ("trailer total", t + 13), ("trailer magic", t + 27)] {
        spots.push((what.into(), at));
    }
    for (what, at) in spots {
        let mut b = bytes.clone();
        b[at] ^= 0x01;
        let msg = err_of(&b, &src);
        assert!(msg.starts_with("bundle_corrupt: "), "{what} at {at}: {msg}");
    }
}

#[test]
fn truncated_or_extended_bundles_are_corrupt() {
    let src = source_for(PATH);
    let bytes = bundle::build(&src, docs(300, |p| p as i64, |_| -1)).unwrap();
    for n in [0, 1, 27, 28, bytes.len() / 2, bytes.len() - 29, bytes.len() - 1] {
        let msg = err_of(&bytes[..n], &src);
        assert!(msg.starts_with("bundle_corrupt: "), "{n} bytes: {msg}");
    }
    let mut longer = bytes.clone();
    longer.extend_from_slice(&bytes[bytes.len() - 28..]);
    assert!(err_of(&longer, &src).starts_with("bundle_corrupt: "));
}

#[test]
fn another_source_is_a_mismatch() {
    let src = source_for(PATH);
    let bytes = bundle::build(&src, docs(300, |p| p as i64, |_| -1)).unwrap();
    let other_path = Source::new(&src.index_id, "/lake/main/pages/other.parquet", src.file_size, src.footer_size);
    let other_index = Source { index_id: "0b6c2a4e-6f1d-4c8e-9a3b-7d5e1f2c3a4c".into(), ..src.clone() };
    let other_size = Source { file_size: src.file_size + 1, ..src.clone() };
    let other_footer = Source { footer_size: src.footer_size - 1, ..src.clone() };
    for (what, s) in [("path", other_path), ("index_id", other_index), ("file_size", other_size), ("footer_size", other_footer)] {
        let msg = err_of(&bytes, &s);
        assert!(msg.starts_with("bundle_mismatch: "), "{what}: {msg}");
    }
    // The path key alone differs: the expected source is refused as malformed, since its key is not
    // the SHA-256 of its path.
    let bad_key = Source { path_key: path_key("/elsewhere"), ..src.clone() };
    assert!(err_of(&bytes, &bad_key).starts_with("search_invalid_argument: "));
    bundle::open(&bytes, &src).unwrap();
}

#[test]
fn a_recorded_path_key_that_is_not_the_paths_sha256_is_a_mismatch() {
    let src = source_for(PATH);
    let mut bytes = bundle::build(&src, docs(300, |p| p as i64, |_| -1)).unwrap();
    let (_, footer) = layout(&bytes);
    // footer: magic 8, version 4, index_id (2 + 36), then path_key (2 + 64).
    let at = footer.start + 8 + 4 + 2 + 36 + 2;
    assert_eq!(&bytes[at..at + 64], src.path_key.as_bytes());
    bytes[at..at + 64].copy_from_slice(path_key("/somewhere/else.parquet").as_bytes());
    reseal_footer(&mut bytes);
    let msg = err_of(&bytes, &src);
    assert!(msg.starts_with("bundle_mismatch: "), "{msg}");
    assert!(msg.contains("SHA-256"), "{msg}");
    // A forged expected source naming that key with the same path is refused before any bundle read.
    let forged = Source { path_key: path_key("/somewhere/else.parquet"), ..src.clone() };
    assert!(err_of(&bytes, &forged).starts_with("search_invalid_argument: "));
}

#[test]
fn another_format_version_is_a_mismatch() {
    let src = source_for(PATH);
    let mut bytes = bundle::build(&src, docs(300, |p| p as i64, |_| -1)).unwrap();
    let (_, footer) = layout(&bytes);
    bytes[footer.start + 8..footer.start + 12].copy_from_slice(&2u32.to_le_bytes());
    reseal_footer(&mut bytes);
    let msg = err_of(&bytes, &src);
    assert!(msg.starts_with("bundle_mismatch: format version"), "{msg}");
}

#[test]
fn builder_refusals() {
    let src = source_for(PATH);
    let mut b = bundle::Builder::new(&src).unwrap();
    b.add(0, 1, -1, "a b").unwrap();
    assert_eq!(b.add(2, 2, -1, "c").unwrap_err().code(), "bundle_build"); // a skipped position
    assert_eq!(b.add(1, 2, 9, "c").unwrap_err().code(), "bundle_build"); // snapshots for some docs only
    assert_eq!(b.add(1, 2, -2, "c").unwrap_err().code(), "bundle_build");
    let empty = bundle::Builder::new(&src).unwrap();
    assert_eq!(empty.finish().unwrap_err().code(), "bundle_build");
    for bad in [
        Source { index_id: "not-a-uuid".into(), ..src.clone() },
        Source { index_id: src.index_id.to_uppercase(), ..src.clone() },
        Source { path_key: src.path_key.to_uppercase(), ..src.clone() },
        Source { path_key: path_key("/x"), ..src.clone() },
    ] {
        assert_eq!(bundle::Builder::new(&bad).err().unwrap().code(), "search_invalid_argument");
    }
}

#[test]
fn bundle_sizes_per_10000_docs() {
    let src = source_for(PATH);
    let n = 10_000usize;
    let affine = bundle::build(&src, docs(n, |p| p as i64, |_| -1)).unwrap();
    let explicit = bundle::build(&src, docs(n, |p| (n - p) as i64, |p| p as i64)).unwrap();
    let text: usize = (0..n as u64).map(|p| page_text(p).len()).sum();
    let (regions, _) = layout(&explicit);
    println!(
        "10,000 docs ({} bytes of text, {} tokens): bundle {} bytes affine rowids without snapshots, {} bytes explicit rowids with snapshots; regions {:?}",
        text,
        (0..n as u64).map(|p| tokens(&page_text(p)).len()).sum::<usize>(),
        affine.len(),
        explicit.len(),
        regions.iter().map(|r| (r.0.clone(), r.2)).collect::<Vec<_>>()
    );
}

// ---- the C ABI ----

use qod_search_core::{
    qs_builder_add, qs_builder_finish, qs_builder_new, qs_bytes_free, qs_check_query, qs_search, qs_segment_free,
    qs_segment_has_explicit_rowids, qs_segment_has_snapshot_column, qs_segment_num_docs, qs_segment_open,
    qs_segment_rowid, qs_segment_snapshot, qs_string_free, QsHit, QsSource, QS_POPULATION_ALLOWED,
};

unsafe fn take_err(e: *mut std::os::raw::c_char) -> String {
    if e.is_null() {
        return String::new();
    }
    let s = CStr::from_ptr(e).to_string_lossy().to_string();
    qs_string_free(e);
    s
}

#[test]
fn c_abi_round_trip_and_search() {
    let src = source_for(PATH);
    let index_id = CString::new(src.index_id.clone()).unwrap();
    let key = CString::new(src.path_key.clone()).unwrap();
    let path = CString::new(src.path.clone()).unwrap();
    let qs = QsSource {
        index_id: index_id.as_ptr(),
        path_key: key.as_ptr(),
        path: path.as_ptr(),
        file_size: src.file_size,
        footer_size: src.footer_size,
        record_count: 200,
        row_id_start: 100,
        has_internal_row_id: 0,
        has_internal_snapshot: 1,
    };
    unsafe {
        let mut err = std::ptr::null_mut();
        let b = qs_builder_new(&qs, std::ptr::null(), u64::MAX, &mut err);
        assert!(!b.is_null(), "{}", take_err(err));
        for p in 0..200u64 {
            let text = if p % 2 == 0 { format!("duck lake {p}") } else { "quack house".to_string() };
            let rc = qs_builder_add(b, p, 100 + p as i64, 3, text.as_ptr() as *const _, text.len(), &mut err);
            assert_eq!(rc, 0, "{}", take_err(err));
        }
        let (mut bytes, mut len, mut docs) = (std::ptr::null_mut(), 0usize, 0u64);
        assert_eq!(qs_builder_finish(b, &mut bytes, &mut len, &mut docs, &mut err), 0, "{}", take_err(err));
        assert_eq!(docs, 200);
        let seg = qs_segment_open(bytes, len, &qs, &mut err);
        assert!(!seg.is_null(), "{}", take_err(err));
        assert!(err.is_null());
        assert_eq!(qs_segment_num_docs(seg), 200);
        assert_eq!(qs_segment_has_explicit_rowids(seg), 0);
        assert_eq!(qs_segment_has_snapshot_column(seg), 1);
        assert_eq!(qs_segment_rowid(seg, 17), 117);
        assert_eq!(qs_segment_rowid(seg, 200), -1);
        assert_eq!(qs_segment_snapshot(seg, 17), 3);

        // A corrupt copy is refused with a message.
        let mut copy = std::slice::from_raw_parts(bytes, len).to_vec();
        copy[10] ^= 1;
        let bad = qs_segment_open(copy.as_ptr(), copy.len(), &qs, &mut err);
        assert!(bad.is_null());
        assert!(take_err(err).starts_with("bundle_corrupt: "));
        qs_bytes_free(bytes, len);

        // Search: allowed = even positions (the duck lake docs), population allowed.
        let live = vec![u64::MAX; 4];
        let allowed = bitset(200, |p| p % 4 == 0);
        let segs = [seg as *const _];
        let lives = [live.as_ptr()];
        let alloweds = [allowed.as_ptr()];
        let words = [4usize];
        let q = CString::new("lake").unwrap();
        let f = CString::new("body").unwrap();
        let mut out = vec![QsHit { rowid: 0, segment: 0, doc: 0, score: 0.0 }; 10];
        let mut n_out = 0usize;
        let rc = qs_search(
            segs.as_ptr(),
            1,
            lives.as_ptr(),
            words.as_ptr(),
            alloweds.as_ptr(),
            words.as_ptr(),
            QS_POPULATION_ALLOWED,
            q.as_ptr(),
            f.as_ptr(),
            1.2,
            0.75,
            10,
            std::ptr::null(),
            u64::MAX,
            out.as_mut_ptr(),
            out.len(),
            &mut n_out,
            &mut err,
        );
        assert_eq!(rc, 0, "{}", take_err(err));
        assert_eq!(n_out, 10);
        let got: Vec<i64> = out.iter().map(|h| h.rowid).collect();
        assert_eq!(got, (0..10).map(|i| 100 + 4 * i).collect::<Vec<i64>>());
        assert!(out.iter().all(|h| h.segment == 0 && h.rowid == 100 + h.doc as i64));

        // Rule 6.
        let t = CString::new("title:x").unwrap();
        assert_ne!(qs_check_query(t.as_ptr(), f.as_ptr(), &mut err), 0);
        assert_eq!(take_err(err), "search_field_not_allowed: title");
        let ok = CString::new("body:x w1").unwrap();
        assert_eq!(qs_check_query(ok.as_ptr(), f.as_ptr(), &mut err), 0);
        assert!(err.is_null());
        // The same refusal from qs_search.
        let rc = qs_search(
            segs.as_ptr(), 1, lives.as_ptr(), words.as_ptr(), std::ptr::null(), std::ptr::null(), 0, t.as_ptr(),
            f.as_ptr(), 1.2, 0.75, 10, std::ptr::null(), u64::MAX, out.as_mut_ptr(), out.len(), &mut n_out, &mut err,
        );
        assert_ne!(rc, 0);
        assert_eq!(take_err(err), "search_field_not_allowed: title");
        assert_eq!(n_out, 0);
        // Lengths: a live bitset of the wrong word count, and an output buffer below k.
        let short = [3usize];
        let rc = qs_search(
            segs.as_ptr(), 1, lives.as_ptr(), short.as_ptr(), std::ptr::null(), std::ptr::null(), 0, q.as_ptr(),
            f.as_ptr(), 1.2, 0.75, 10, std::ptr::null(), u64::MAX, out.as_mut_ptr(), out.len(), &mut n_out, &mut err,
        );
        assert_ne!(rc, 0);
        let msg = take_err(err);
        assert!(msg.starts_with("search_invalid_argument: live bitset 0 has 3 words"), "{msg}");
        let rc = qs_search(
            segs.as_ptr(), 1, lives.as_ptr(), words.as_ptr(), alloweds.as_ptr(), short.as_ptr(), 0, q.as_ptr(),
            f.as_ptr(), 1.2, 0.75, 10, std::ptr::null(), u64::MAX, out.as_mut_ptr(), out.len(), &mut n_out, &mut err,
        );
        assert_ne!(rc, 0);
        assert!(take_err(err).starts_with("search_invalid_argument: allowed bitset 0 has 3 words"));
        let rc = qs_search(
            segs.as_ptr(), 1, lives.as_ptr(), words.as_ptr(), std::ptr::null(), std::ptr::null(), 0, q.as_ptr(),
            f.as_ptr(), 1.2, 0.75, 10, std::ptr::null(), u64::MAX, out.as_mut_ptr(), 9, &mut n_out, &mut err,
        );
        assert_ne!(rc, 0);
        assert_eq!(take_err(err), "search_invalid_argument: out holds fewer than k hits");
        // Cancelled before it starts, and a budget too small for the bitsets.
        let flag = 1u8;
        let rc = qs_search(
            segs.as_ptr(), 1, lives.as_ptr(), words.as_ptr(), std::ptr::null(), std::ptr::null(), 0, q.as_ptr(),
            f.as_ptr(), 1.2, 0.75, 10, &flag, u64::MAX, out.as_mut_ptr(), out.len(), &mut n_out, &mut err,
        );
        assert_ne!(rc, 0);
        assert!(take_err(err).starts_with("search_cancelled: "));
        let rc = qs_search(
            segs.as_ptr(), 1, lives.as_ptr(), words.as_ptr(), std::ptr::null(), std::ptr::null(), 0, q.as_ptr(),
            f.as_ptr(), 1.2, 0.75, 10, std::ptr::null(), 16, out.as_mut_ptr(), out.len(), &mut n_out, &mut err,
        );
        assert_ne!(rc, 0);
        assert!(take_err(err).starts_with("search_memory_exceeded: "));
        qs_segment_free(seg);
    }
}

#[test]
fn catalog_fields_are_checked_at_open_and_at_build() {
    // A file without _ducklake_internal_row_id: rowids row_id_start + position; with snapshots.
    let src = source_for(PATH);
    let bytes = bundle::build(&src, docs(300, |p| 5_000 + p as i64, |p| p as i64)).unwrap();
    let catalog = Source { record_count: 300, row_id_start: 5_000, has_internal_row_id: 0, has_internal_snapshot: 1, ..src.clone() };
    bundle::open(&bytes, &catalog).unwrap();
    for (what, s) in [
        ("record_count", Source { record_count: 301, ..catalog.clone() }),
        ("row_id_start", Source { row_id_start: 5_001, ..catalog.clone() }),
        ("has_internal_snapshot", Source { has_internal_snapshot: 0, ..catalog.clone() }),
    ] {
        let msg = err_of(&bytes, &s);
        assert!(msg.starts_with("bundle_mismatch: "), "{what}: {msg}");
    }
    // Unchecked fields (-1) and a file with internal row ids accept the same bundle.
    bundle::open(&bytes, &Source { has_internal_row_id: 1, row_id_start: 1, ..catalog.clone() }).unwrap();
    bundle::open(&bytes, &src).unwrap();
    // Explicit rowids on a file the catalog says has none are a mismatch.
    let explicit = bundle::build(&src, docs(300, |p| (300 - p) as i64, |p| p as i64)).unwrap();
    assert!(err_of(&explicit, &catalog).starts_with("bundle_mismatch: "));
    // The builder refuses the same disagreements at finish.
    for s in [Source { record_count: 299, ..catalog.clone() }, Source { has_internal_snapshot: 0, ..catalog.clone() }] {
        let e = bundle::build(&s, docs(300, |p| 5_000 + p as i64, |p| p as i64)).unwrap_err();
        assert_eq!(e.code(), "bundle_build", "{e}");
    }
    let bad = Source { record_count: -2, ..src.clone() };
    assert_eq!(bundle::Builder::new(&bad).err().unwrap().code(), "search_invalid_argument");
}

#[test]
fn a_negative_snapshot_id_is_corrupt() {
    let src = source_for(PATH);
    let mut bytes = bundle::build(&src, docs(100, |p| p as i64, |p| p as i64)).unwrap();
    let (regions, _) = layout(&bytes);
    let (_, off, len) = regions.iter().find(|r| r.0 == "snapshots").unwrap().clone();
    bytes[off..off + 8].copy_from_slice(&(-7i64).to_le_bytes());
    reseal_region(&mut bytes, "snapshots", off, len);
    let msg = err_of(&bytes, &src);
    assert!(msg.starts_with("bundle_corrupt: a snapshot id below 0"), "{msg}");
}

/// Recomputes the CRC32 of the region named `name` in the footer's table, then the footer's CRC.
fn reseal_region(b: &mut [u8], name: &str, off: usize, len: usize) {
    let crc = crc32fast::hash(&b[off..off + len]);
    let (_, footer) = layout(b);
    // Find the region's table entry: kind u8, name (u16 len), offset u64, len u64, crc u32.
    let mut entry = Vec::new();
    entry.extend_from_slice(&(name.len() as u16).to_le_bytes());
    entry.extend_from_slice(name.as_bytes());
    entry.extend_from_slice(&(off as u64).to_le_bytes());
    entry.extend_from_slice(&(len as u64).to_le_bytes());
    let f = &b[footer.clone()];
    let at = f.windows(entry.len()).position(|w| w == entry.as_slice()).expect("region entry") + footer.start + entry.len();
    b[at..at + 4].copy_from_slice(&crc.to_le_bytes());
    reseal_footer(b);
}

#[test]
fn builder_cancel_and_budget() {
    use qod_search_core::controls::Controls;
    use std::sync::atomic::{AtomicU8, Ordering};
    let src = source_for(PATH);
    let flag = AtomicU8::new(0);
    let mut b = bundle::Builder::with_controls(&src, Controls::new(Some(&flag), u64::MAX)).unwrap();
    b.add(0, 0, -1, "a b c").unwrap();
    flag.store(1, Ordering::Relaxed);
    assert_eq!(b.add(1, 1, -1, "d").unwrap_err().code(), "search_cancelled");
    assert_eq!(b.finish().unwrap_err().code(), "search_cancelled");
    // A budget below the build's buffers.
    let mut b = bundle::Builder::with_controls(&src, Controls::new(None, 64 * 1024)).unwrap();
    let mut failed = None;
    for p in 0..10_000u64 {
        if let Err(e) = b.add(p, p as i64, -1, &page_text(p)) {
            failed = Some(e);
            break;
        }
    }
    assert_eq!(failed.expect("the budget was never reached").code(), "search_memory_exceeded");
}

#[test]
fn seal_label_uses_the_footer_forms() {
    let src = source_for(PATH);
    assert_eq!(
        bundle::seal_label(&src).unwrap(),
        format!("qod-search-bundle|v1|0b6c2a4e-6f1d-4c8e-9a3b-7d5e1f2c3a4b|{}", path_key(PATH))
    );
    assert_eq!(path_key(PATH).len(), 64);
}
