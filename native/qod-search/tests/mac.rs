//! The bundle MAC of format 3 (search spec 13, "Bundle authenticity"; Phase 1 Task 3): every bundle
//! ends with HMAC-SHA256(mac_key, label || 0x0A || body), label
//! `qod-search-bundle|v3|<index id>|<path key>`, and an open checks the length, then that MAC, in
//! constant time, before it reads any other byte of the bundle.
mod common;
use common::*;
use qod_search_core::bundle::{self, MacKey, Source};
use qod_search_core::sha256;
use qod_search_core::{qs_builder_new, qs_segment_free, qs_segment_open, QsSource};

const PATH: &str = "/lake/main/pages/ducklake-0193-mac.parquet";

/// A line `name=<value>` of the golden file the Scala spec writes (comments start with `#`).
fn golden(name: &str) -> String {
    let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../qod-node/tests/golden/search-mac-v1.txt");
    let text = std::fs::read_to_string(path).expect("the golden file");
    text.lines()
        .filter(|l| !l.starts_with('#'))
        .find_map(|l| l.strip_prefix(&format!("{name}=")).map(str::to_string))
        .unwrap_or_else(|| panic!("no {name} line in the golden file"))
}

fn unhex(s: &str) -> Vec<u8> {
    assert!(s.len() % 2 == 0);
    (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap()).collect()
}

fn code_of(bytes: &[u8], src: &Source) -> &'static str {
    match bundle::open(bytes, src) {
        Ok(_) => "ok",
        Err(e) => e.code(),
    }
}

/// The bundles of the 3-segment corpus, each with its source.
fn corpus_bundles() -> Vec<(Vec<u8>, Source)> {
    corpus()
        .iter()
        .enumerate()
        .map(|(i, docs)| {
            let src = source_for(&format!("/lake/main/pages/corpus-{i}.parquet"));
            (build_bytes(&src, docs), src)
        })
        .collect()
}

/// The position stream of a seeded generator (splitmix64), below `n`.
fn seeded(seed: u64, count: usize, n: usize) -> Vec<usize> {
    (0..count as u64).map(|i| (hash2(seed, i) % n as u64) as usize).collect()
}

#[test]
fn golden_vector() {
    let key = unhex(&golden("key"));
    let body = unhex(&golden("body"));
    let mac = unhex(&golden("mac"));
    assert_eq!((key.len(), body.len(), mac.len()), (32, 64, 32));
    let label = format!("qod-search-bundle|v3|{}|{}", golden("index_id"), golden("path_key"));
    assert_eq!(sha256::hmac(&key, &[label.as_bytes(), b"\n", &body]), mac.as_slice());
    // The library's own label for that source, and its open, agree with the golden file: the golden
    // body under its MAC passes the MAC (it then fails as a bundle, being none), and under any other
    // MAC it does not.
    assert_eq!(sha256::hex_digest(golden("path").as_bytes()), golden("path_key"));
    let src = Source::new(&golden("index_id"), &golden("path"), 1, 1, TABLE_ID, BODY, MacKey::new(&key).unwrap());
    assert_eq!(bundle::mac_label(&src).unwrap(), label);
    let mut sealed = body.clone();
    sealed.extend_from_slice(&mac);
    assert_eq!(code_of(&sealed, &src), "bundle_corrupt");
    let last = sealed.len() - 1;
    sealed[last] ^= 1;
    assert_eq!(code_of(&sealed, &src), "bundle_mac_mismatch");
}

#[test]
fn hmac_rfc_4231() {
    // RFC 4231 test cases 1, 2, 3, 6 and 7 (6 and 7 have a key longer than the 64-byte block).
    let cases: [(Vec<u8>, Vec<u8>, &str); 5] = [
        (vec![0x0b; 20], b"Hi There".to_vec(), "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"),
        (b"Jefe".to_vec(), b"what do ya want for nothing?".to_vec(), "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"),
        (vec![0xaa; 20], vec![0xdd; 50], "773ea91e36800e46854db8ebd09181a72959098b3ef8c122d9635514ced565fe"),
        (
            vec![0xaa; 131],
            b"Test Using Larger Than Block-Size Key - Hash Key First".to_vec(),
            "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
        ),
        (
            vec![0xaa; 131],
            b"This is a test using a larger than block-size key and a larger than block-size data. The key needs to be hashed before being used by the HMAC algorithm.".to_vec(),
            "9b09ffa71b942fcb27635fbcd5b0e944bfdc63644f0713938a7f51535c3a35e2",
        ),
    ];
    for (key, data, want) in cases {
        assert_eq!(sha256::hmac(&key, &[&data]), unhex(want).as_slice());
        // The message in several parts gives the same MAC.
        let (a, b) = data.split_at(data.len() / 3);
        assert_eq!(sha256::hmac(&key, &[a, b"", b]), unhex(want).as_slice());
    }
}

#[test]
fn finish_appends_the_mac_over_the_body() {
    let src = source_for(PATH);
    let bytes = build_bytes(&src, &corpus()[2]);
    let (body, mac) = bytes.split_at(bytes.len() - bundle::MAC_LEN);
    let label = bundle::mac_label(&src).unwrap();
    assert_eq!(label, format!("qod-search-bundle|v3|{}|{}", src.index_id, src.path_key));
    assert_eq!(sha256::hmac(test_mac_key(&src.index_id).bytes(), &[label.as_bytes(), b"\n", body]), mac);
    assert_eq!(bundle::FORMAT_VERSION, 3);
    assert_eq!(qod_search_core::QS_BUNDLE_FORMAT_VERSION, 3);
    bundle::open(&bytes, &src).unwrap();
}

#[test]
fn every_byte_flip_refused() {
    for (bytes, src) in corpus_bundles() {
        let n = bytes.len();
        let mut spots: Vec<usize> = (0..64).chain(n - 64..n).collect();
        spots.extend(seeded(n as u64, 1_000, n));
        // The 32 MAC bytes (within the last 64, named for clarity).
        spots.extend(n - bundle::MAC_LEN..n);
        for (i, at) in spots.into_iter().enumerate() {
            let mut b = bytes.clone();
            b[at] ^= 1u8 << (i % 8);
            let got = std::panic::catch_unwind(|| code_of(&b, &src)).expect("no panic");
            assert_eq!(got, "bundle_mac_mismatch", "bit {} of byte {at} of {n}", i % 8);
        }
        // Through the C ABI too.
        let mut b = bytes.clone();
        b[n / 2] ^= 0x80;
        let cs = c_source(&src);
        let mut err = std::ptr::null_mut();
        let seg = unsafe { qs_segment_open(b.as_ptr(), b.len(), &cs.source, &mut err) };
        assert!(seg.is_null());
        assert!(unsafe { take_err(err) }.starts_with("bundle_mac_mismatch: "));
    }
}

#[test]
fn other_index_key_refused() {
    let src = source_for(PATH);
    let bytes = build_bytes(&src, &corpus()[2]);
    let other_id = "0b6c2a4e-6f1d-4c8e-9a3b-7d5e1f2c3a4c";
    // Same index id, another index's key.
    let wrong_key = Source { mac_key: test_mac_key(other_id), ..src.clone() };
    assert_eq!(code_of(&bytes, &wrong_key), "bundle_mac_mismatch");
    // Another index id with its own key: the label binds the index id.
    let other_index = Source { index_id: other_id.into(), mac_key: test_mac_key(other_id), ..src.clone() };
    assert_eq!(code_of(&bytes, &other_index), "bundle_mac_mismatch");
    // Another index id under this index's key: the label still binds it.
    let relabelled = Source { index_id: other_id.into(), ..src.clone() };
    assert_eq!(code_of(&bytes, &relabelled), "bundle_mac_mismatch");
    // Another data file under the same key: the label binds the path key.
    let other_path = Source::new(&src.index_id, "/lake/main/pages/other.parquet", src.file_size, src.footer_size, TABLE_ID, BODY, test_mac_key(&src.index_id));
    assert_eq!(code_of(&bytes, &other_path), "bundle_mac_mismatch");
    // Any other 32-byte key.
    let other = Source { mac_key: MacKey::new(&[0x5au8; 32]).unwrap(), ..src.clone() };
    assert_eq!(code_of(&bytes, &other), "bundle_mac_mismatch");
    assert_eq!(code_of(&bytes, &src), "ok");
}

#[test]
fn truncated_refused() {
    let src = source_for(PATH);
    let bytes = build_bytes(&src, &corpus()[2]);
    let n = bytes.len();
    for len in [0usize, 1, 31, 32, 33] {
        let got = std::panic::catch_unwind(|| code_of(&bytes[..len], &src)).expect("no panic");
        assert_eq!(got, "bundle_corrupt", "{len} bytes");
    }
    for len in [n - 1, n - bundle::MAC_LEN, n / 2, bundle::MIN_BUNDLE_LEN] {
        let got = std::panic::catch_unwind(|| code_of(&bytes[..len], &src)).expect("no panic");
        assert_eq!(got, "bundle_mac_mismatch", "{len} bytes");
    }
    assert_eq!(code_of(&bytes[..bundle::MIN_BUNDLE_LEN - 1], &src), "bundle_corrupt");
    // Extended by a byte, or by a second MAC: the MAC no longer sits over the body.
    let mut longer = bytes.clone();
    longer.push(0);
    assert_eq!(code_of(&longer, &src), "bundle_mac_mismatch");
    let mut twice = bytes.clone();
    twice.extend_from_slice(&bytes[n - bundle::MAC_LEN..]);
    assert_eq!(code_of(&twice, &src), "bundle_mac_mismatch");
    // A NULL pointer with a length, and a real pointer with length 0, through the C ABI.
    let cs = c_source(&src);
    let mut err = std::ptr::null_mut();
    let seg = unsafe { qs_segment_open(std::ptr::null(), 32, &cs.source, &mut err) };
    assert!(seg.is_null());
    assert!(unsafe { take_err(err) }.starts_with("search_invalid_argument: "));
    let seg = unsafe { qs_segment_open(bytes.as_ptr(), 0, &cs.source, &mut err) };
    assert!(seg.is_null());
    assert!(unsafe { take_err(err) }.starts_with("bundle_corrupt: "));
}

#[test]
fn key_length() {
    for len in [0usize, 31, 33, 64] {
        assert_eq!(MacKey::new(&vec![7u8; len]).unwrap_err().code(), "search_invalid_argument", "{len}");
    }
    let src = source_for(PATH);
    let bytes = build_bytes(&src, &corpus()[2]);
    let key = [7u8; 33];
    let cs = c_source(&src);
    for (ptr, len) in [(key.as_ptr(), 31usize), (key.as_ptr(), 33), (std::ptr::null(), 32), (key.as_ptr(), 0)] {
        let qs = QsSource { mac_key: ptr, mac_key_len: len, ..cs.source };
        let mut err = std::ptr::null_mut();
        let b = unsafe { qs_builder_new(&qs, std::ptr::null(), u64::MAX, &mut err) };
        assert!(b.is_null());
        assert!(unsafe { take_err(err) }.starts_with("search_invalid_argument: "), "builder, {len} bytes");
        let seg = unsafe { qs_segment_open(bytes.as_ptr(), bytes.len(), &qs, &mut err) };
        assert!(seg.is_null());
        assert!(unsafe { take_err(err) }.starts_with("search_invalid_argument: "), "open, {len} bytes");
    }
    // The 32-byte key opens it.
    let seg = unsafe { qs_segment_open(bytes.as_ptr(), bytes.len(), &cs.source, &mut std::ptr::null_mut()) };
    assert!(!seg.is_null());
    unsafe { qs_segment_free(seg) };
}

#[test]
fn all_zero_key_refused() {
    // An all-zero key is what a caller that never filled the key in passes: refused, not used.
    assert_eq!(MacKey::new(&[0u8; 32]).unwrap_err().code(), "search_invalid_argument");
    // One non-zero byte anywhere makes it a key.
    for i in [0usize, 17, 31] {
        let mut k = [0u8; 32];
        k[i] = 1;
        MacKey::new(&k).unwrap();
    }
    let src = source_for(PATH);
    let bytes = build_bytes(&src, &corpus()[2]);
    let zeros = [0u8; 32];
    let cs = c_source(&src);
    let qs = QsSource { mac_key: zeros.as_ptr(), mac_key_len: zeros.len(), ..cs.source };
    let mut err = std::ptr::null_mut();
    let b = unsafe { qs_builder_new(&qs, std::ptr::null(), u64::MAX, &mut err) };
    assert!(b.is_null());
    assert!(unsafe { take_err(err) }.starts_with("search_invalid_argument: "), "builder");
    let seg = unsafe { qs_segment_open(bytes.as_ptr(), bytes.len(), &qs, &mut err) };
    assert!(seg.is_null());
    assert!(unsafe { take_err(err) }.starts_with("search_invalid_argument: "), "open");
}

#[test]
fn key_never_printed() {
    let key = test_mac_key(INDEX_ID);
    let shown = format!("{key:?} {:?}", source_for(PATH));
    let hex: String = key.bytes().iter().map(|b| format!("{b:02x}")).collect();
    assert!(!shown.contains(&hex), "{shown}");
    assert!(!shown.contains(&format!("{:?}", key.bytes())), "{shown}");
    assert!(shown.contains("MacKey(<redacted>)"), "{shown}");
}

#[test]
fn v2_refused() {
    let src = source_for(PATH);
    // A format 1 bundle (the Task 2 fixture) and a format 2 bundle carry no MAC: they read as
    // forged, never as corrupt, without a byte of them parsed.
    let v1 = std::fs::read(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/data/bundle-v1.bin")).unwrap();
    assert_eq!(code_of(&v1, &src), "bundle_mac_mismatch");
    let bytes = build_bytes(&src, &corpus()[2]);
    let mut v2 = unseal(&bytes).to_vec();
    set_footer_version(&mut v2, 2);
    assert_eq!(code_of(&v2, &src), "bundle_mac_mismatch");
    // A format 2 body under a valid MAC of this index's key: the MAC passes and the version check
    // after it refuses the format.
    let sealed = seal(&v2, &src);
    let e = bundle::open(&sealed, &src).err().expect("refused");
    assert_eq!(e.code(), "bundle_mismatch");
    assert!(e.message().contains("format version 2, expected 3"), "{}", e.message());
}

#[cfg(feature = "test-hooks")]
#[test]
fn hostile_body_never_parsed() {
    use qod_search_core::qs_test_footer_reads;
    let src = source_for(PATH);
    let bytes = build_bytes(&src, &corpus()[2]);
    // Positive control: a valid open reads the footer once.
    let before = qs_test_footer_reads();
    bundle::open(&bytes, &src).unwrap();
    assert_eq!(qs_test_footer_reads(), before + 1);
    let start = qs_test_footer_reads();
    for i in 0..1_000u64 {
        let len = 1 + (hash2(0x6d61_6331, i) % 4096) as usize;
        let mut body: Vec<u8> = (0..len as u64).map(|j| hash2(i, j) as u8).collect();
        // Some bodies start as a real bundle does, or are a real bundle's body.
        if i % 3 == 0 {
            let real = unseal(&bytes);
            let take = len.min(real.len());
            body[..take].copy_from_slice(&real[..take]);
        }
        let mac: Vec<u8> = (0..32u64).map(|j| hash2(i ^ 0xffff, j) as u8).collect();
        body.extend_from_slice(&mac);
        let got = code_of(&body, &src);
        assert!(got == "bundle_mac_mismatch" || got == "bundle_corrupt", "{i}: {got}");
        if body.len() >= bundle::MIN_BUNDLE_LEN {
            assert_eq!(got, "bundle_mac_mismatch", "{i}");
        }
    }
    // A real body under a wrong MAC.
    let mut forged = bytes.clone();
    let last = forged.len() - 1;
    forged[last] ^= 1;
    assert_eq!(code_of(&forged, &src), "bundle_mac_mismatch");
    assert_eq!(qs_test_footer_reads(), start, "a footer was read before its MAC verified");
}
