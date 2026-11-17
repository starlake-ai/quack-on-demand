//! The segment bundle (spec 6.2): round trip, refusals of corrupt and mismatched bundles,
//! determinism, and the C ABI over it.
mod common;
use common::*;
use qod_search_core::bundle::{self, path_key, Doc, Source};
use std::ffi::CString;

const PATH: &str = "/lake/main/pages/ducklake-0193.parquet";

fn docs(n: usize, rowid: impl Fn(usize) -> i64, snapshot: impl Fn(usize) -> i64) -> Vec<Doc> {
    (0..n)
        .map(|p| Doc::one(p as u64, rowid(p), snapshot(p), page_text(p as u64)))
        .collect()
}

fn le_u64(b: &[u8], at: usize) -> u64 {
    u64::from_le_bytes(b[at..at + 8].try_into().unwrap())
}

/// Where a sealed bundle's body trailer starts: before the trailer of 28 bytes and the MAC.
fn trailer_at(b: &[u8]) -> usize {
    b.len() - bundle::MAC_LEN - 28
}

/// The regions of a bundle as its footer lists them: (name, offset, len), then the footer body
/// range. Parsed here from the documented layout, independently of the library.
fn layout(b: &[u8]) -> (Vec<(String, usize, usize)>, std::ops::Range<usize>) {
    let t = trailer_at(b);
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
    at += 8 + 8 + 8; // sizes, table_id
    let nf = u32::from_le_bytes(f[at..at + 4].try_into().unwrap()) as usize;
    at += 4 + 8 * nf; // field ids
    at += 4 + 1; // num_docs, flags
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

/// Rewrites the footer body's CRC after a test edited the footer (the MAC is left as it was: a test
/// that means to reach the parser calls `reseal` after).
fn reseal_footer(b: &mut [u8]) {
    let t = trailer_at(b);
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
    assert_eq!(seg.token_counts(0), exact.as_slice());
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

/// Under a valid MAC (a writer holding the index's key), the CRCs and the structure checks still
/// refuse a damaged body; tests/mac.rs shows the MAC refuses it first otherwise.
#[test]
fn a_flipped_byte_in_any_region_is_corrupt() {
    let src = source_for(PATH);
    let bytes = bundle::build(&src, docs(800, |p| (p * 3) as i64, |p| p as i64)).unwrap();
    let (regions, footer) = layout(&bytes);
    let names: Vec<&str> = regions.iter().map(|r| r.0.as_str()).collect();
    println!("regions: {names:?}; bundle {} bytes", bytes.len());
    for want in ["header", "rowids", "snapshots", "token_counts_f1", "idx", "term", "fieldnorm", "fast", "store"] {
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
    let t = trailer_at(&bytes);
    for (what, at) in [("trailer offset", t), ("trailer crc", t + 9), ("trailer total", t + 13), ("trailer magic", t + 27)] {
        spots.push((what.into(), at));
    }
    for (what, at) in spots {
        let mut b = bytes.clone();
        b[at] ^= 0x01;
        assert!(err_of(&b, &src).starts_with("bundle_mac_mismatch: "), "{what} at {at}, not resealed");
        reseal(&mut b, &src);
        let msg = err_of(&b, &src);
        assert!(msg.starts_with("bundle_corrupt: "), "{what} at {at}: {msg}");
    }
}

/// A body truncated or extended under a valid MAC is corrupt (tests/mac.rs: without one, the MAC
/// refuses it first).
#[test]
fn truncated_or_extended_bundles_are_corrupt() {
    let src = source_for(PATH);
    let bytes = bundle::build(&src, docs(300, |p| p as i64, |_| -1)).unwrap();
    let body = unseal(&bytes);
    for n in [0, 1, 27, 28, 42, body.len() / 2, body.len() - 29, body.len() - 1] {
        let msg = err_of(&seal(&body[..n], &src), &src);
        assert!(msg.starts_with("bundle_corrupt: "), "{n} bytes: {msg}");
    }
    let mut longer = body.to_vec();
    longer.extend_from_slice(&body[body.len() - 28..]);
    assert!(err_of(&seal(&longer, &src), &src).starts_with("bundle_corrupt: "));
}

#[test]
fn another_source_is_a_mismatch() {
    let src = source_for(PATH);
    let bytes = bundle::build(&src, docs(300, |p| p as i64, |_| -1)).unwrap();
    let other_path = Source::new(&src.index_id, "/lake/main/pages/other.parquet", src.file_size, src.footer_size, TABLE_ID, BODY, test_mac_key(&src.index_id));
    let other_index = Source { index_id: "0b6c2a4e-6f1d-4c8e-9a3b-7d5e1f2c3a4c".into(), ..src.clone() };
    let other_size = Source { file_size: src.file_size + 1, ..src.clone() };
    let other_footer = Source { footer_size: src.footer_size - 1, ..src.clone() };
    // The MAC's label binds the index id and the path key: another one reads as forged.
    for (what, s) in [("path", other_path), ("index_id", other_index)] {
        let msg = err_of(&bytes, &s);
        assert!(msg.starts_with("bundle_mac_mismatch: "), "{what}: {msg}");
    }
    for (what, s) in [("file_size", other_size), ("footer_size", other_footer)] {
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
    reseal(&mut bytes, &src);
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
    bytes[footer.start + 8..footer.start + 12].copy_from_slice(&4u32.to_le_bytes());
    reseal_footer(&mut bytes);
    reseal(&mut bytes, &src);
    let msg = err_of(&bytes, &src);
    assert!(msg.starts_with("bundle_mismatch: format version 4, expected 3"), "{msg}");
}

#[test]
fn builder_refusals() {
    let src = source_for(PATH);
    let mut b = bundle::Builder::new(&src).unwrap();
    b.add(0, 1, -1, &[Some("a b")]).unwrap();
    assert_eq!(b.add(2, 2, -1, &[Some("c")]).unwrap_err().code(), "bundle_build"); // a skipped position
    assert_eq!(b.add(1, 2, 9, &[Some("c")]).unwrap_err().code(), "bundle_build"); // snapshots for some docs only
    assert_eq!(b.add(1, 2, -2, &[Some("c")]).unwrap_err().code(), "bundle_build");
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
    qs_builder_add, qs_builder_add_bound, qs_builder_finish, qs_builder_memory, qs_builder_new, qs_bytes_free, qs_check_query, qs_segment_free,
    qs_segment_has_explicit_rowids, qs_segment_has_snapshot_column, qs_segment_num_docs, qs_segment_open,
    qs_segment_rowid, qs_segment_snapshot, QS_POPULATION_ALLOWED,
};

#[test]
fn c_abi_round_trip_and_search() {
    let src = Source { record_count: 200, row_id_start: 100, has_internal_row_id: 0, has_internal_snapshot: 1, ..source_for(PATH) };
    let cs = c_source(&src);
    let qs = &cs.source;
    unsafe {
        let mut err = std::ptr::null_mut();
        let b = qs_builder_new(qs, std::ptr::null(), u64::MAX, &mut err);
        assert!(!b.is_null(), "{}", take_err(err));
        for p in 0..200u64 {
            let text = if p % 2 == 0 { format!("duck lake {p}") } else { "quack house".to_string() };
            let texts = [QsText { text: text.as_ptr() as *const _, len: text.len() }];
            let rc = qs_builder_add(b, p, 100 + p as i64, 3, texts.as_ptr(), 1, &mut err);
            assert_eq!(rc, 0, "{}", take_err(err));
        }
        let (mut bytes, mut len, mut docs) = (std::ptr::null_mut(), 0usize, 0u64);
        assert_eq!(qs_builder_finish(b, &mut bytes, &mut len, &mut docs, &mut err), 0, "{}", take_err(err));
        assert_eq!(docs, 200);
        let seg = qs_segment_open(bytes, len, qs, &mut err);
        assert!(!seg.is_null(), "{}", take_err(err));
        assert!(err.is_null());
        assert_eq!(qs_segment_num_docs(seg), 200);
        assert_eq!(qs_segment_has_explicit_rowids(seg), 0);
        assert_eq!(qs_segment_has_snapshot_column(seg), 1);
        assert_eq!(qs_segment_rowid(seg, 17), 117);
        assert_eq!(qs_segment_rowid(seg, 200), -1);
        assert_eq!(qs_segment_snapshot(seg, 17), 3);

        // A damaged copy is refused with a message: the MAC first.
        let mut copy = std::slice::from_raw_parts(bytes, len).to_vec();
        copy[10] ^= 1;
        let bad = qs_segment_open(copy.as_ptr(), copy.len(), qs, &mut err);
        assert!(bad.is_null());
        assert!(take_err(err).starts_with("bundle_mac_mismatch: "));
        // Under a valid MAC, the header's CRC.
        reseal(&mut copy, &src);
        let bad = qs_segment_open(copy.as_ptr(), copy.len(), qs, &mut err);
        assert!(bad.is_null());
        assert!(take_err(err).starts_with("bundle_corrupt: "));
        qs_bytes_free(bytes, len);

        // Search: allowed = even positions (the duck lake docs), population allowed.
        let live = vec![vec![u64::MAX; 4]];
        let allowed = vec![bitset(200, |p| p % 4 == 0)];
        let segs = [seg as *const _];
        let a = c_args(&segs, &live, &allowed, QS_POPULATION_ALLOWED, "lake", BODY, 10, std::ptr::null(), u64::MAX);
        let out = c_search(&a.args).unwrap();
        assert_eq!(out.len(), 10);
        let got: Vec<i64> = out.iter().map(|h| h.rowid).collect();
        assert_eq!(got, (0..10).map(|i| 100 + 4 * i).collect::<Vec<i64>>());
        assert!(out.iter().all(|h| h.segment == 0 && h.rowid == 100 + h.doc as i64));

        // Rule 6.
        let fields = c_fields(BODY);
        let t = CString::new("title:x").unwrap();
        assert_ne!(qs_check_query(t.as_ptr(), fields.fields.as_ptr(), 1, &mut err), 0);
        assert_eq!(take_err(err), "search_field_not_allowed: title");
        let ok = CString::new("body:x w1").unwrap();
        assert_eq!(qs_check_query(ok.as_ptr(), fields.fields.as_ptr(), 1, &mut err), 0);
        assert!(err.is_null());
        // The same refusal from qs_search.
        let a = c_args(&segs, &live, &[], 0, "title:x", BODY, 10, std::ptr::null(), u64::MAX);
        assert_eq!(c_search(&a.args).unwrap_err(), "search_field_not_allowed: title");
        // Lengths: a live bitset of the wrong word count, and an output buffer below k.
        let mut a = c_args(&segs, &live, &[], 0, "lake", BODY, 10, std::ptr::null(), u64::MAX);
        let short = [3usize];
        let words = [4usize];
        a.args.live_words = short.as_ptr();
        let msg = c_search(&a.args).unwrap_err();
        assert!(msg.starts_with("search_invalid_argument: live bitset 0 has 3 words"), "{msg}");
        a.args.live_words = words.as_ptr();
        let alw = [allowed[0].as_ptr()];
        a.args.allowed = alw.as_ptr();
        a.args.allowed_words = short.as_ptr();
        assert!(c_search(&a.args).unwrap_err().starts_with("search_invalid_argument: allowed bitset 0 has 3 words"));
        a.args.allowed = std::ptr::null();
        a.args.allowed_words = std::ptr::null();
        let mut out = vec![empty_hit(); 9];
        let mut n_out = 0usize;
        assert_ne!(qod_search_core::qs_search(&a.args, out.as_mut_ptr(), 9, &mut n_out, &mut err), 0);
        assert_eq!(take_err(err), "search_invalid_argument: out holds fewer than k hits");
        // Cancelled before it starts, and a budget too small for the bitsets.
        let flag = 1u8;
        let a = c_args(&segs, &live, &[], 0, "lake", BODY, 10, &flag, u64::MAX);
        assert!(c_search(&a.args).unwrap_err().starts_with("search_cancelled: "));
        let a = c_args(&segs, &live, &[], 0, "lake", BODY, 10, std::ptr::null(), 16);
        assert!(c_search(&a.args).unwrap_err().starts_with("search_memory_exceeded: "));
        // A NULL argument block.
        let mut n_out = 0usize;
        assert_ne!(qod_search_core::qs_search(std::ptr::null(), out.as_mut_ptr(), 9, &mut n_out, &mut err), 0);
        assert!(take_err(err).starts_with("search_invalid_argument: "));
        qs_segment_free(seg);
    }
}

#[test]
fn c_abi_builder_texts() {
    // Two fields; a NULL text, an empty text, and the wrong number of texts.
    let src = source_with(PATH, TABLE_ID, &[("title", 1), ("body", 2)]);
    let cs = c_source(&src);
    unsafe {
        let mut err = std::ptr::null_mut();
        let b = qs_builder_new(&cs.source, std::ptr::null(), u64::MAX, &mut err);
        assert!(!b.is_null(), "{}", take_err(err));
        let body = "duck lake";
        let texts = [QsText { text: std::ptr::null(), len: 0 }, QsText { text: body.as_ptr() as *const _, len: body.len() }];
        assert_eq!(qs_builder_add(b, 0, 0, -1, texts.as_ptr(), 2, &mut err), 0, "{}", take_err(err));
        let empty = [QsText { text: "".as_ptr() as *const _, len: 0 }, QsText { text: body.as_ptr() as *const _, len: body.len() }];
        assert_eq!(qs_builder_add(b, 1, 1, -1, empty.as_ptr(), 2, &mut err), 0, "{}", take_err(err));
        assert_ne!(qs_builder_add(b, 2, 2, -1, texts.as_ptr(), 1, &mut err), 0);
        assert!(take_err(err).starts_with("search_invalid_argument: "));
        let bad = [QsText { text: std::ptr::null(), len: 3 }, QsText { text: body.as_ptr() as *const _, len: body.len() }];
        assert_ne!(qs_builder_add(b, 2, 2, -1, bad.as_ptr(), 2, &mut err), 0);
        assert!(take_err(err).starts_with("search_invalid_argument: "));
        let (mut bytes, mut len, mut docs) = (std::ptr::null_mut(), 0usize, 0u64);
        assert_eq!(qs_builder_finish(b, &mut bytes, &mut len, &mut docs, &mut err), 0, "{}", take_err(err));
        assert_eq!(docs, 2);
        let copy = std::slice::from_raw_parts(bytes, len).to_vec();
        qs_bytes_free(bytes, len);
        let seg = bundle::open(&copy, &src).unwrap();
        assert_eq!(seg.token_counts(0), &[0, 0]);
        assert_eq!(seg.token_counts(1), &[2, 2]);
        // A source with no field, 33 fields, or a repeated field id is refused.
        for fields in [vec![], (0..33).map(|i| (format!("c{i}"), i)).collect::<Vec<_>>(), vec![("a".to_string(), 1), ("b".to_string(), 1)]] {
            let s = Source { fields, ..src.clone() };
            let c = c_source(&s);
            let b = qs_builder_new(&c.source, std::ptr::null(), u64::MAX, &mut err);
            assert!(b.is_null());
            assert!(take_err(err).starts_with("search_invalid_argument: "));
        }
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
    reseal(&mut bytes, &src);
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
    b.add(0, 0, -1, &[Some("a b c")]).unwrap();
    flag.store(1, Ordering::Relaxed);
    assert_eq!(b.add(1, 1, -1, &[Some("d")]).unwrap_err().code(), "search_cancelled");
    assert_eq!(b.finish().unwrap_err().code(), "search_cancelled");
    // A budget below the build's buffers.
    let mut b = bundle::Builder::with_controls(&src, Controls::new(None, 64 * 1024)).unwrap();
    let mut failed = None;
    for p in 0..10_000u64 {
        if let Err(e) = b.add(p, p as i64, -1, &[Some(page_text(p).as_str())]) {
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
        format!("qod-search-bundle|v3|0b6c2a4e-6f1d-4c8e-9a3b-7d5e1f2c3a4b|{}", path_key(PATH))
    );
    assert_eq!(path_key(PATH).len(), 64);
}

/// Task 11: qs_builder_memory reads what the builder holds (the writer's arena and its columns), the figure its own
/// budget checks: 0 for NULL, more than 0 once started, growing with the docs added, and an add that would take it
/// past the builder's budget is refused.
#[test]
fn c_abi_builder_memory() {
    let src = source_for(PATH);
    let cs = c_source(&src);
    unsafe {
        assert_eq!(qs_builder_memory(std::ptr::null()), 0);
        let mut err = std::ptr::null_mut();
        let b = qs_builder_new(&cs.source, std::ptr::null(), u64::MAX, &mut err);
        assert!(!b.is_null(), "{}", take_err(err));
        let start = qs_builder_memory(b);
        let mut last = start;
        for p in 0..5_000u64 {
            let text = format!("duck lake w{} w{} w{}", p, p * 7, p % 13);
            let texts = [QsText { text: text.as_ptr() as *const _, len: text.len() }];
            assert_eq!(qs_builder_add(b, p, p as i64, -1, texts.as_ptr(), 1, &mut err), 0, "{}", take_err(err));
            let now = qs_builder_memory(b);
            assert!(now >= last, "the builder's memory went down from {last} to {now}");
            last = now;
        }
        println!("builder memory: {start} bytes at the start, {last} after 5,000 docs");
        // At least the columns it keeps per doc: a rowid and an exact token count.
        assert!(last >= start + 5_000 * 12, "{last} after 5,000 docs, {start} at the start");
        let (mut bytes, mut len, mut docs) = (std::ptr::null_mut(), 0usize, 0u64);
        assert_eq!(qs_builder_finish(b, &mut bytes, &mut len, &mut docs, &mut err), 0, "{}", take_err(err));
        qs_bytes_free(bytes, len);
        // A builder whose budget is its start plus one add's bound takes that add and refuses the next.
        let text = "duck lake";
        let texts = [QsText { text: text.as_ptr() as *const _, len: text.len() }];
        let probe = qs_builder_new(&cs.source, std::ptr::null(), u64::MAX, &mut err);
        let budget = qs_builder_memory(probe) + qs_builder_add_bound(probe, texts.as_ptr(), 1);
        qs_builder_finish(probe, std::ptr::null_mut(), std::ptr::null_mut(), std::ptr::null_mut(), std::ptr::null_mut());
        let b = qs_builder_new(&cs.source, std::ptr::null(), budget, &mut err);
        assert!(!b.is_null(), "{}", take_err(err));
        assert_eq!(qs_builder_add(b, 0, 0, -1, texts.as_ptr(), 1, &mut err), 0, "{}", take_err(err));
        assert_ne!(qs_builder_add(b, 1, 1, -1, texts.as_ptr(), 1, &mut err), 0);
        assert!(take_err(err).starts_with("search_memory_exceeded: "));
        qs_builder_finish(b, std::ptr::null_mut(), std::ptr::null_mut(), std::ptr::null_mut(), std::ptr::null_mut());
    }
}

// ---- the borrowed open (Task 12) ----

/// A bundle of 3,000 docs with explicit (non-affine) rowids and a snapshot column, so every column a segment
/// keeps is there, and its source.
fn borrowed_fixture() -> (Source, Vec<u8>) {
    let src = source_for(PATH);
    let bytes = bundle::build(&src, docs(3_000, |p| (p as i64) * 3 + (p as i64 % 7), |p| 2 + (p as i64 % 5))).unwrap();
    (src, bytes)
}

/// The queries both opens are searched with: single terms, a phrase, a boolean.
const BORROWED_QUERIES: &[&str] = &["w1", "w3 w7", "\"w1 w2\"", "+w5 -w9", "w0 OR w11"];

/// Each query's hits (rowid, segment, doc, score bits) over `seg`, every doc live, the table population.
fn hits_of(seg: *mut qod_search_core::QsSegment) -> Vec<Vec<(i64, u32, u32, u32)>> {
    let n = unsafe { qs_segment_num_docs(seg) } as usize;
    let live = vec![bitset(n, |_| true)];
    BORROWED_QUERIES
        .iter()
        .map(|q| {
            let a = c_args(&[seg as *const _], &live, &[], 0, q, BODY, 100, std::ptr::null(), u64::MAX);
            c_search(&a.args).unwrap().iter().map(|h| (h.rowid, h.segment, h.doc, h.score.to_bits())).collect()
        })
        .collect()
}

/// Opens `bytes` in place through the C ABI.
fn c_open_borrowed(bytes: &[u8], src: &Source) -> *mut qod_search_core::QsSegment {
    let cs = c_source(src);
    let mut err = std::ptr::null_mut();
    let seg = unsafe { qod_search_core::qs_segment_open_borrowed(bytes.as_ptr(), bytes.len(), &cs.source, &mut err) };
    assert!(!seg.is_null(), "{}", unsafe { take_err(err) });
    seg
}

#[test]
fn borrowed_open_equals_copy() {
    let (src, bytes) = borrowed_fixture();
    let owned = c_open(&bytes, &src);
    let borrowed = c_open_borrowed(&bytes, &src);
    unsafe {
        assert_eq!(qs_segment_num_docs(owned), qs_segment_num_docs(borrowed));
        assert_eq!(qs_segment_has_explicit_rowids(borrowed), 1);
        assert_eq!(qs_segment_has_snapshot_column(borrowed), 1);
        for doc in [0u32, 1, 17, 2_999, 3_000] {
            assert_eq!(qs_segment_rowid(owned, doc), qs_segment_rowid(borrowed, doc));
            assert_eq!(qs_segment_snapshot(owned, doc), qs_segment_snapshot(borrowed, doc));
        }
    }
    let (a, b) = (hits_of(owned), hits_of(borrowed));
    assert!(a.iter().all(|h| !h.is_empty()), "every query matches some doc");
    assert_eq!(a, b, "the same hits and bit-identical scores");
    // Every refusal is the same: a forged MAC, a corrupt body, a mismatched source.
    let mut forged = bytes.clone();
    let last = forged.len() - 1;
    forged[last] ^= 1;
    let mut other = src.clone();
    other.file_size += 1;
    for (what, b, s) in [("forged", &forged, &src), ("mismatched", &bytes, &other)] {
        let cs = c_source(s);
        let (mut e1, mut e2) = (std::ptr::null_mut(), std::ptr::null_mut());
        unsafe {
            assert!(qs_segment_open(b.as_ptr(), b.len(), &cs.source, &mut e1).is_null(), "{what}");
            assert!(qod_search_core::qs_segment_open_borrowed(b.as_ptr(), b.len(), &cs.source, &mut e2).is_null(), "{what}");
            assert_eq!(take_err(e1), take_err(e2), "{what}");
        }
    }
    unsafe {
        qs_segment_free(owned);
        qs_segment_free(borrowed);
    }
}

#[test]
fn borrowed_bytes_unchanged() {
    let (src, bytes) = borrowed_fixture();
    let before = qod_search_core::sha256::digest(&bytes);
    let seg = c_open_borrowed(&bytes, &src);
    let _ = hits_of(seg);
    // A matches iterator over every doc, freed before the segment (the header's order).
    let n = unsafe { qs_segment_num_docs(seg) } as usize;
    let live = vec![bitset(n, |_| true)];
    let a = c_args(&[seg as *const _], &live, &[], 0, "w1", BODY, 1, std::ptr::null(), u64::MAX);
    unsafe {
        let mut err = std::ptr::null_mut();
        let m = qod_search_core::qs_matches_open(&a.args, 1, &mut err);
        assert!(!m.is_null(), "{}", take_err(err));
        let mut out = vec![empty_hit(); 64];
        let mut got = 0usize;
        loop {
            let mut n_out = 0usize;
            assert_eq!(qod_search_core::qs_matches_next(m, out.as_mut_ptr(), out.len(), &mut n_out, &mut err), 0);
            if n_out == 0 {
                break;
            }
            got += n_out;
        }
        assert!(got > 0);
        qod_search_core::qs_matches_free(m);
        qs_segment_free(seg);
    }
    assert_eq!(qod_search_core::sha256::digest(&bytes), before, "the borrowed bytes hash as before the open");
}

/// Step 1 of Task 12, in the library: what a borrowed open keeps on the heap is exactly what
/// qs_segment_heap_bytes reports (the counting allocator of the test-hooks feature, on this thread), and a
/// search leaves nothing more behind.
#[cfg(feature = "test-hooks")]
#[test]
fn heap_bytes_cover_a_borrowed_open() {
    let (src, bytes) = borrowed_fixture();
    // A first open warms whatever Tantivy initializes once.
    unsafe { qs_segment_free(c_open_borrowed(&bytes, &src)) };
    let cs = c_source(&src);
    let before = qod_search_core::test_alloc::held();
    let mut err = std::ptr::null_mut();
    let seg = unsafe { qod_search_core::qs_segment_open_borrowed(bytes.as_ptr(), bytes.len(), &cs.source, &mut err) };
    assert!(!seg.is_null());
    let opened = qod_search_core::test_alloc::held() - before;
    let reported = unsafe { qod_search_core::qs_segment_heap_bytes(seg) } as i64;
    let _ = hits_of(seg);
    let searched = qod_search_core::test_alloc::held() - before;
    println!("borrowed open of {} bundle bytes: {opened} heap bytes measured, {reported} reported, {searched} after a search", bytes.len());
    assert!(opened <= reported, "measured {opened} above the reported {reported}");
    assert!(reported - opened <= 256, "reported {reported} far above the measured {opened}");
    assert!(searched <= reported, "a search left {searched} bytes, above the reported {reported}");
    // A copying open holds the bundle beside it.
    let owned = c_open(&bytes, &src);
    assert_eq!(unsafe { qod_search_core::qs_segment_heap_bytes(owned) }, reported as u64);
    unsafe {
        qs_segment_free(seg);
        qs_segment_free(owned);
    }
}
