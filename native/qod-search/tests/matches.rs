//! The matches iterator (spec 7.1 steps 6 and 7): every matching alive doc of a search's arguments,
//! segment by segment and doc by doc, with scores bit-identical to qs_search's or NAN, in bounded
//! memory, cancellable, under the call's budget.
mod common;
#[path = "common/counting_alloc.rs"]
mod counting_alloc;
use common::*;
use qod_search_core::{
    qs_matches_free, qs_matches_next, qs_matches_open, qs_segment_free, QsMatches, QsSegment, QS_POPULATION_ALLOWED,
    QS_POPULATION_TABLE,
};
use std::collections::BTreeMap;

#[cfg(not(feature = "test-hooks"))]
#[global_allocator]
static ALLOC: counting_alloc::Counting = counting_alloc::Counting;

const TITLE_BODY: &[(&str, i64)] = &[("title", 1), ("body", 2)];

fn open(a: &QsSearchArgs, with_score: i32) -> Result<*mut QsMatches, String> {
    let mut err = std::ptr::null_mut();
    let m = unsafe { qs_matches_open(a, with_score, &mut err) };
    if m.is_null() {
        Err(unsafe { take_err(err) })
    } else {
        assert!(err.is_null());
        Ok(m)
    }
}

/// One qs_matches_next call: the hits, or the error.
fn next(m: *mut QsMatches, out: &mut [QsHit]) -> Result<usize, String> {
    let mut n = 0usize;
    let mut err = std::ptr::null_mut();
    let rc = unsafe { qs_matches_next(m, out.as_mut_ptr(), out.len(), &mut n, &mut err) };
    if rc != 0 {
        assert_eq!(n, 0, "a failed call returned hits");
        Err(unsafe { take_err(err) })
    } else {
        assert!(n <= out.len());
        Ok(n)
    }
}

/// Every hit of an iterator, `cap` at a time, checking segment order then doc order.
fn drain(m: *mut QsMatches, cap: usize) -> Vec<QsHit> {
    let mut all = Vec::new();
    let mut out = vec![empty_hit(); cap];
    loop {
        let n = next(m, &mut out).unwrap();
        if n == 0 {
            break;
        }
        all.extend_from_slice(&out[..n]);
    }
    // Done stays done.
    assert_eq!(next(m, &mut out).unwrap(), 0);
    for w in all.windows(2) {
        assert!((w[0].segment, w[0].doc) < (w[1].segment, w[1].doc), "out of order: {:?} then {:?}", key(&w[0]), key(&w[1]));
    }
    all
}

fn key(h: &QsHit) -> (u32, u32, i64) {
    (h.segment, h.doc, h.rowid)
}

/// A seeded bitset of `n` bits, each set with probability `p`.
fn random_bits(n: usize, seed: u64, p: f64) -> Vec<u64> {
    bitset(n, |d| (hash2(seed, d as u64) % 1_000_000) as f64 / 1_000_000.0 < p)
}

#[test]
fn matches_equal_exhaustive() {
    let docs = multi_corpus(&[900, 700, 400]);
    let srcs: Vec<_> = (0..3).map(|i| source_with(&format!("/lake/mt/{i}.parquet"), TABLE_ID, TITLE_BODY)).collect();
    let segs: Vec<*mut QsSegment> = docs.iter().zip(&srcs).map(|(d, s)| c_open(&build_multi_bytes(s, d), s)).collect();
    let ptrs: Vec<*const QsSegment> = segs.iter().map(|s| *s as *const _).collect();
    let live: Vec<Vec<u64>> = docs.iter().enumerate().map(|(i, d)| random_bits(d.len(), 11 + i as u64, 0.9)).collect();
    let allowed: Vec<Vec<u64>> = docs.iter().enumerate().map(|(i, d)| random_bits(d.len(), 101 + i as u64, 0.6)).collect();
    // A union (Tantivy's own scorer), a conjunction of three (the library's clause tree), a mixed
    // shape, a phrase and a field prefix.
    for q in ["w1 OR w2", "w0 AND w1 AND w2", "+w0 w3 -w4", "\"w0 w1\"", "title:w1 body:w5"] {
        for (pop, alw) in [(QS_POPULATION_TABLE, &allowed), (QS_POPULATION_ALLOWED, &allowed), (QS_POPULATION_TABLE, &Vec::new())] {
            let a = c_args(&ptrs, &live, alw, pop, q, TITLE_BODY, 10_000, std::ptr::null(), u64::MAX);
            let exhaustive = c_search(&a.args).unwrap();
            assert!(!exhaustive.is_empty(), "`{q}` matches nothing");
            assert!(exhaustive.len() < 10_000, "`{q}`: the exhaustive search was cut at k");
            let want: BTreeMap<(u32, u32), (i64, u32)> =
                exhaustive.iter().map(|h| ((h.segment, h.doc), (h.rowid, h.score.to_bits()))).collect();
            for with_score in [0, 1] {
                let m = open(&a.args, with_score).unwrap();
                let got = drain(m, 7);
                unsafe { qs_matches_free(m) };
                let keys: Vec<(u32, u32)> = got.iter().map(|h| (h.segment, h.doc)).collect();
                assert_eq!(keys, want.keys().copied().collect::<Vec<_>>(), "`{q}` population {pop} with_score {with_score}");
                for h in &got {
                    let (rowid, bits) = want[&(h.segment, h.doc)];
                    assert_eq!(h.rowid, rowid);
                    if with_score == 1 {
                        assert_eq!(h.score.to_bits(), bits, "`{q}` ({}, {}): {} vs qs_search's {}", h.segment, h.doc, h.score, f32::from_bits(bits));
                    } else {
                        assert!(h.score.is_nan());
                    }
                }
            }
        }
    }
    for s in segs {
        unsafe { qs_segment_free(s) };
    }
}

#[test]
fn matches_streams_bounded() {
    // 200,000 matching docs in 4 segments of 50,000.
    let n = 50_000usize;
    let fields: &[(&str, i64)] = &[("body", 1)];
    let mut segs = Vec::new();
    for i in 0..4 {
        let src = source_with(&format!("/lake/mt/big{i}.parquet"), TABLE_ID, fields);
        let docs: Vec<MDoc> = (0..n).map(|p| MDoc { rowid: (i * n + p) as i64, texts: vec![Some(format!("w1 w{}", p % 13))] }).collect();
        segs.push(c_open(&build_multi_bytes(&src, &docs), &src));
    }
    let ptrs: Vec<*const QsSegment> = segs.iter().map(|s| *s as *const _).collect();
    let live: Vec<Vec<u64>> = (0..4).map(|_| bitset(n, |_| true)).collect();
    for with_score in [0, 1] {
        let a = c_args(&ptrs, &live, &[], QS_POPULATION_TABLE, "w1", fields, 0, std::ptr::null(), u64::MAX);
        let m = open(&a.args, with_score).unwrap();
        let mut out = vec![empty_hit(); 1024];
        let baseline = counting_alloc::reset_peak();
        let mut total = 0usize;
        loop {
            let k = next(m, &mut out).unwrap();
            if k == 0 {
                break;
            }
            total += k;
        }
        let peak = counting_alloc::peak() - baseline;
        let bound = (n / 8 + 64 * 1024) as i64;
        unsafe { qs_matches_free(m) };
        println!("with_score {with_score}: {total} hits, peak {peak} bytes above the open's, bound {bound}");
        assert_eq!(total, 4 * n);
        assert!(peak <= bound, "peak {peak} above {bound}");
    }
    for s in segs {
        unsafe { qs_segment_free(s) };
    }
}

#[test]
fn matches_cancel_and_budget() {
    let n = 3_000usize;
    let fields: &[(&str, i64)] = &[("body", 1)];
    let mut segs = Vec::new();
    for i in 0..2 {
        let src = source_with(&format!("/lake/mt/c{i}.parquet"), TABLE_ID, fields);
        let docs: Vec<MDoc> = (0..n).map(|p| MDoc { rowid: (i * n + p) as i64, texts: vec![Some(page_text(p as u64))] }).collect();
        segs.push(c_open(&build_multi_bytes(&src, &docs), &src));
    }
    let ptrs: Vec<*const QsSegment> = segs.iter().map(|s| *s as *const _).collect();
    let live: Vec<Vec<u64>> = (0..2).map(|_| bitset(n, |_| true)).collect();
    // The cancel flag, set before the second call: that call fails and returns no hit, and so does
    // every later one.
    let flag = std::sync::atomic::AtomicU8::new(0);
    let a = c_args(&ptrs, &live, &[], QS_POPULATION_TABLE, "w0", fields, 0, flag.as_ptr() as *const u8, u64::MAX);
    let m = open(&a.args, 1).unwrap();
    let mut out = vec![empty_hit(); 16];
    assert_eq!(next(m, &mut out).unwrap(), 16);
    flag.store(1, std::sync::atomic::Ordering::Relaxed);
    assert!(next(m, &mut out).unwrap_err().starts_with("search_cancelled: "));
    flag.store(0, std::sync::atomic::Ordering::Relaxed);
    assert!(next(m, &mut out).unwrap_err().starts_with("search_cancelled: "), "a failed iterator went on");
    unsafe { qs_matches_free(m) };
    // A budget below one segment's bitset fails at the open.
    let a = c_args(&ptrs, &live, &[], QS_POPULATION_TABLE, "w0", fields, 0, std::ptr::null(), (n / 8) as u64 - 1);
    assert!(open(&a.args, 1).unwrap_err().starts_with("search_memory_exceeded: "));
    // out_cap 0 and NULL out are refused.
    let a = c_args(&ptrs, &live, &[], QS_POPULATION_TABLE, "w0", fields, 0, std::ptr::null(), u64::MAX);
    let m = open(&a.args, 0).unwrap();
    let mut n_out = 0usize;
    let mut err = std::ptr::null_mut();
    let rc = unsafe { qs_matches_next(m, out.as_mut_ptr(), 0, &mut n_out, &mut err) };
    assert_ne!(rc, 0);
    assert!(unsafe { take_err(err) }.starts_with("search_invalid_argument: "));
    unsafe { qs_matches_free(m) };
    unsafe { qs_matches_free(std::ptr::null_mut()) };
    for s in segs {
        unsafe { qs_segment_free(s) };
    }
}

#[test]
fn matches_no_score_is_nan() {
    let docs = multi_corpus(&[500]);
    let src = source_with("/lake/mt/nan.parquet", TABLE_ID, TITLE_BODY);
    let seg = c_open(&build_multi_bytes(&src, &docs[0]), &src);
    let ptrs = [seg as *const QsSegment];
    let live = vec![bitset(500, |_| true)];
    let a = c_args(&ptrs, &live, &[], QS_POPULATION_TABLE, "w1 OR title:w2", TITLE_BODY, 0, std::ptr::null(), u64::MAX);
    let m = open(&a.args, 0).unwrap();
    let hits = drain(m, 64);
    unsafe { qs_matches_free(m) };
    assert!(hits.len() > 100);
    assert!(hits.iter().all(|h| h.score.is_nan()));
    unsafe { qs_segment_free(seg) };
}

/// The smallest budget `qs_matches_open` accepts for `a` (opened, drained and freed at that budget), by
/// bisection between 0 and `high`, which must succeed.
fn least_budget(make: &dyn Fn(u64) -> CArgs, high: u64) -> u64 {
    let ok = |b: u64| -> bool {
        let a = make(b);
        match open(&a.args, 1) {
            Ok(m) => {
                let mut out = vec![empty_hit(); 1024];
                let mut fine = true;
                loop {
                    match next(m, &mut out) {
                        Ok(0) => break,
                        Ok(_) => {}
                        Err(e) => {
                            assert!(e.starts_with("search_memory_exceeded: "), "{e}");
                            fine = false;
                            break;
                        }
                    }
                }
                unsafe { qs_matches_free(m) };
                fine
            }
            Err(e) => {
                assert!(e.starts_with("search_memory_exceeded: "), "{e}");
                false
            }
        }
    };
    assert!(ok(high), "the high bound {high} is refused");
    let (mut lo, mut hi) = (0u64, high);
    while lo + 1 < hi {
        let mid = lo + (hi - lo) / 2;
        if ok(mid) {
            hi = mid;
        } else {
            lo = mid;
        }
    }
    hi
}

/// The peak bytes this thread holds above its baseline while `a` is opened and drained (task 2 review 7).
fn matches_peak(a: &QsSearchArgs) -> i64 {
    let baseline = counting_alloc::reset_peak();
    let m = open(a, 1).unwrap();
    let mut out = vec![empty_hit(); 1024];
    while next(m, &mut out).unwrap() > 0 {}
    unsafe { qs_matches_free(m) };
    counting_alloc::peak() - baseline
}

/// Task 11 (Task 2 review 7): the matches iterator's (and the top k's) scorer state grows with the query, up to
/// 64 terms over 32 fields (2048 term scorers and their weights), and is charged to the call's memory budget: the
/// smallest budget a 64-term, 32-field query is accepted under exceeds a 1-term, 1-field query's on the same
/// segment by at least what it holds more, so a budget covers what the query needs, and a budget just below it
/// is refused at the open, before any scorer is made.
#[test]
fn matches_scorer_state_charged() {
    let n = 400usize;
    let names: Vec<String> = (0..32).map(|f| format!("c{f}")).collect();
    let fields32: Vec<(&str, i64)> = names.iter().enumerate().map(|(i, c)| (c.as_str(), i as i64 + 1)).collect();
    let words: Vec<String> = (0..64).map(|w| format!("w{w}")).collect();
    let text = words.join(" ");
    let src = source_with("/lake/mt/wide.parquet", TABLE_ID, &fields32);
    let docs: Vec<MDoc> = (0..n).map(|p| MDoc { rowid: p as i64, texts: vec![Some(text.clone()); 32] }).collect();
    let seg = c_open(&build_multi_bytes(&src, &docs), &src);
    let ptrs = [seg as *const QsSegment];
    let live = vec![bitset(n, |_| true)];
    let one: &[(&str, i64)] = &fields32[..1];
    let wide_query = words.join(" ");
    let narrow = |b: u64| c_args(&ptrs, &live, &[], QS_POPULATION_TABLE, "w0", one, 0, std::ptr::null(), b);
    let wide = |b: u64| c_args(&ptrs, &live, &[], QS_POPULATION_TABLE, &wide_query, &fields32, 0, std::ptr::null(), b);
    let peak_narrow = matches_peak(&narrow(u64::MAX).args);
    let peak_wide = matches_peak(&wide(u64::MAX).args);
    let least_narrow = least_budget(&narrow, 1 << 32);
    let least_wide = least_budget(&wide, 1 << 32);
    println!(
        "scorer state: peak 1x1 {peak_narrow}, 64x32 {peak_wide} bytes; least budget 1x1 {least_narrow}, 64x32 {least_wide}"
    );
    assert!(peak_wide > peak_narrow, "the wide query holds no more than the narrow one");
    assert!(
        (least_wide - least_narrow) as i64 >= peak_wide - peak_narrow,
        "the wide query holds {} bytes more, its budget is only {} bytes more",
        peak_wide - peak_narrow,
        least_wide - least_narrow
    );
    // Phrases: 32 of two terms over 32 fields, each term read with positions.
    let phrases: Vec<String> = (0..32).map(|i| format!("\"w{} w{}\"", 2 * i, 2 * i + 1)).collect();
    let phrase_query = phrases.join(" ");
    let phrased = |b: u64| c_args(&ptrs, &live, &[], QS_POPULATION_TABLE, &phrase_query, &fields32, 0, std::ptr::null(), b);
    let peak_phrased = matches_peak(&phrased(u64::MAX).args);
    let least_phrased = least_budget(&phrased, 1 << 32);
    println!("phrases: peak {peak_phrased} bytes, least budget {least_phrased}");
    assert!(
        (least_phrased - least_narrow) as i64 >= peak_phrased - peak_narrow,
        "the phrase query holds {} bytes more, its budget is only {} bytes more",
        peak_phrased - peak_narrow,
        least_phrased - least_narrow
    );
    // A budget just below the wide query's is refused at the open.
    let a = wide(least_wide - 1);
    assert!(open(&a.args, 1).is_err_and(|e| e.starts_with("search_memory_exceeded: ")) || {
        // Or by the first call, never later: the scorer state is charged before it is made.
        let m = open(&a.args, 1).unwrap();
        let mut out = vec![empty_hit(); 1024];
        let refused = next(m, &mut out).is_err_and(|e| e.starts_with("search_memory_exceeded: "));
        unsafe { qs_matches_free(m) };
        refused
    });
    unsafe { qs_segment_free(seg) };
}

/// Task 11 fix round 1 (review M1): a range or a set leaf (`column:[a TO b]`, `column: IN [...]`) reads each
/// segment through a doc bitset Tantivy's weight allocates, and a set's weight builds an FST first (about 960 KB
/// for a moment, whatever its size); both are charged: what the call holds more than a one-term query is within
/// what its budget asks more. A regex leaf is refused by the parser.
#[test]
fn matches_doc_set_leaves_charged() {
    let n = 200_000usize;
    let fields: &[(&str, i64)] = &[("title", 1), ("body", 2)];
    let mut segs = Vec::new();
    for i in 0..2 {
        let src = source_with(&format!("/lake/mt/set{i}.parquet"), TABLE_ID, fields);
        let docs: Vec<MDoc> = (0..n)
            .map(|p| MDoc { rowid: (i * n + p) as i64, texts: vec![Some(format!("t{}", p % 5)), Some(format!("w{} w1", p % 9))] })
            .collect();
        segs.push(c_open(&build_multi_bytes(&src, &docs), &src));
    }
    let ptrs: Vec<*const QsSegment> = segs.iter().map(|s| *s as *const _).collect();
    let live: Vec<Vec<u64>> = (0..2).map(|_| bitset(n, |_| true)).collect();
    let narrow = |b: u64| c_args(&ptrs, &live, &[], QS_POPULATION_TABLE, "w1", fields, 0, std::ptr::null(), b);
    let regex = narrow(u64::MAX);
    let regex_query = std::ffi::CString::new("body:/w1.*/").unwrap();
    let mut regex_args = regex.args;
    regex_args.query = regex_query.as_ptr();
    assert!(open(&regex_args, 1).unwrap_err().starts_with("search_query_invalid: "), "a regex leaf is refused");
    let peak_narrow = matches_peak(&narrow(u64::MAX).args);
    let least_narrow = least_budget(&narrow, 1 << 32);
    for q in [
        "body:[w1 TO w3] OR title:[t1 TO t3]",
        "body: IN [w1 w2 w3] OR title: IN [t1 t2]",
        "body:[w2 TO w5] AND title: IN [t1 t2]",
        "body:[w1 TO w3] OR body:[w4 TO w6] OR body:[w7 TO w8] OR title:[t0 TO t4]",
        "body: IN [w1]",
        "body: IN [w1 w2 w3 w4 w5 w6 w7 w8]",
        "body: IN [w1] OR body: IN [w2] OR body: IN [w3] OR title: IN [t1]",
    ] {
        let wide = |b: u64| c_args(&ptrs, &live, &[], QS_POPULATION_TABLE, q, fields, 0, std::ptr::null(), b);
        let peak = matches_peak(&wide(u64::MAX).args);
        let least = least_budget(&wide, 1 << 32);
        println!("{q}: peak {peak} (one term {peak_narrow}), least budget {least} (one term {least_narrow})");
        assert!(
            (least - least_narrow) as i64 >= peak - peak_narrow,
            "{q}: holds {} bytes more, its budget is only {} bytes more",
            peak - peak_narrow,
            least - least_narrow
        );
    }
    for s in segs {
        unsafe { qs_segment_free(s) };
    }
}
