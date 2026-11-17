//! Task 11 fix round 1: a builder's memory is bounded before Tantivy allocates. Each add is checked against an
//! upper bound computed from its texts' bytes alone (`Builder::add_bound`, `qs_builder_add_bound`), before any
//! byte of it is read into the writer; each finish against `Builder::finish_bound`; and the budget a caller
//! bounds several builders by is set per call (`qs_builder_set_budget`). Measured with a counting allocator: the
//! bytes a call really allocates at its peak never pass the bound, an add refused by the bound allocates nothing
//! that grows with its text, and the term table's doubling is inside the bound.
mod common;
#[path = "common/counting_alloc.rs"]
mod counting_alloc;
use common::*;
use qod_search_core::bundle::Builder;
use qod_search_core::controls::Controls;
use qod_search_core::{
    qs_builder_add, qs_builder_add_bound, qs_builder_finish, qs_builder_finish_bound, qs_builder_memory, qs_builder_new,
    qs_builder_set_budget, qs_bytes_free, QsText,
};

#[cfg(not(feature = "test-hooks"))]
#[global_allocator]
static ALLOC: counting_alloc::Counting = counting_alloc::Counting;

/// `n` distinct five-letter lowercase tokens (one word each, never repeated).
fn distinct_text(from: usize, n: usize) -> String {
    let mut s = String::with_capacity(n * 6);
    for i in from..from + n {
        let mut x = i;
        for _ in 0..5 {
            s.push((b'a' + (x % 26) as u8) as char);
            x /= 26;
        }
        s.push(' ');
    }
    s
}

/// Adds `text` as doc `p`, returning the peak bytes the add allocated above what the thread held before it.
fn add_peak(b: &mut Builder, p: u64, text: &str) -> i64 {
    let base = counting_alloc::reset_peak();
    b.add(p, p as i64, -1, &[Some(text)]).unwrap();
    counting_alloc::peak() - base
}

#[test]
fn add_bound_covers_every_add() {
    // Many one-token docs: every column of the writer and of the builder doubles along the way.
    let src = source_for("/bounds/small.parquet");
    let mut b = Builder::new(&src).unwrap();
    let mut worst = 0f64;
    for p in 0..300_000u64 {
        let text = format!("w{}", p % 997);
        let bound = b.add_bound(text.len() as u64, 1);
        let peak = add_peak(&mut b, p, &text);
        assert!(peak <= bound as i64, "doc {p}: the add allocated {peak} bytes at its peak, above its bound {bound}");
        worst = worst.max(peak as f64 / bound as f64);
    }
    println!("300,000 one-token docs: the worst add peak was {:.3} of its bound", worst);
    let fbound = b.finish_bound();
    let base = counting_alloc::reset_peak();
    let held = b.memory();
    let (bundle, _) = b.finish().unwrap();
    let fpeak = counting_alloc::peak() - base;
    println!("finish: builder {held}, bundle {} bytes, finish peak {fpeak}, bound {fbound}", bundle.len());
    assert!(fpeak <= fbound as i64, "the finish allocated {fpeak} bytes at its peak, above its bound {fbound}");
}

#[test]
fn add_bound_covers_the_term_table_doubling() {
    // Docs of 1,000 distinct tokens until the writer's term table (2^19 slots, 4 MiB) doubles at 2^18 terms.
    let src = source_for("/bounds/doubling.parquet");
    let mut b = Builder::new(&src).unwrap();
    let mut jumps = 0;
    for p in 0..400u64 {
        let text = distinct_text(p as usize * 1000, 1000);
        let bound = b.add_bound(text.len() as u64, 1);
        let before = b.memory();
        let peak = add_peak(&mut b, p, &text);
        let after = b.memory();
        if after - before >= 4 << 20 {
            jumps += 1;
            println!("doc {p}: the builder grew by {} bytes (the table doubled), peak {peak}, bound {bound}", after - before);
        }
        assert!(peak <= bound as i64, "doc {p}: the add allocated {peak} bytes at its peak, above its bound {bound}");
    }
    assert!(jumps >= 1, "400,000 distinct terms never doubled the term table");
    let fbound = b.finish_bound();
    let base = counting_alloc::reset_peak();
    let _ = b.finish().unwrap();
    let fpeak = counting_alloc::peak() - base;
    assert!(fpeak <= fbound as i64, "the finish allocated {fpeak} bytes at its peak, above its bound {fbound}");
}

#[test]
fn add_bound_covers_one_huge_cell() {
    for (label, n) in [("1 MB", 170_000usize), ("30 MB", 5_000_000)] {
        let src = source_for("/bounds/huge.parquet");
        let mut b = Builder::new(&src).unwrap();
        b.add(0, 0, -1, &[Some("warm up doc")]).unwrap();
        let text = distinct_text(0, n);
        let bound = b.add_bound(text.len() as u64, 1);
        let peak = add_peak(&mut b, 1, &text);
        println!("{label} of distinct tokens: peak {peak}, bound {bound}");
        assert!(peak <= bound as i64, "{label}: the add allocated {peak} bytes at its peak, above its bound {bound}");
        let fbound = b.finish_bound();
        let base = counting_alloc::reset_peak();
        let _ = b.finish().unwrap();
        let fpeak = counting_alloc::peak() - base;
        assert!(fpeak <= fbound as i64, "{label}: the finish allocated {fpeak} bytes, above its bound {fbound}");
    }
}

#[test]
fn an_add_past_the_budget_allocates_nothing_first() {
    // The security verification's case: one 30 MB cell of distinct tokens under a budget of what the builder holds,
    // twice the text and a margin: refused before the writer reads any of it.
    let src = source_for("/bounds/refused.parquet");
    let cs = c_source(&src);
    let text = distinct_text(0, 5_000_000);
    unsafe {
        let mut err = std::ptr::null_mut();
        let b = qs_builder_new(&cs.source, std::ptr::null(), u64::MAX, &mut err);
        assert!(!b.is_null(), "{}", take_err(err));
        let warm = "warm up doc";
        let t = [QsText { text: warm.as_ptr() as *const _, len: warm.len() }];
        assert_eq!(qs_builder_add(b, 0, 0, -1, t.as_ptr(), 1, &mut err), 0, "{}", take_err(err));
        let held = qs_builder_memory(b);
        let budget = held + 2 * text.len() as u64 + (1 << 20);
        assert_eq!(qs_builder_set_budget(b, budget), 0);
        let t = [QsText { text: text.as_ptr() as *const _, len: text.len() }];
        assert!(qs_builder_add_bound(b, t.as_ptr(), 1) > budget - held);
        let base = counting_alloc::reset_peak();
        let rc = qs_builder_add(b, 1, 1, -1, t.as_ptr(), 1, &mut err);
        let peak = counting_alloc::peak() - base;
        let msg = take_err(err);
        println!("30 MB cell under a budget of {budget}: rc {rc}, peak {peak}, {msg}");
        assert_ne!(rc, 0);
        assert!(msg.starts_with("search_memory_exceeded: "), "{msg}");
        assert!(peak < 64 * 1024, "the refused add allocated {peak} bytes");
        assert_eq!(qs_builder_memory(b), held, "a refused add changed the builder");
        // The same builder, its budget raised, takes the next doc; set_budget on NULL fails.
        assert_eq!(qs_builder_set_budget(b, u64::MAX), 0);
        assert_ne!(qs_builder_set_budget(std::ptr::null_mut(), 1), 0);
        let next = "duck";
        let t = [QsText { text: next.as_ptr() as *const _, len: next.len() }];
        assert_eq!(qs_builder_add(b, 1, 1, -1, t.as_ptr(), 1, &mut err), 0, "{}", take_err(err));
        // A finish past the budget is refused before the writer is finalized.
        let fb = qs_builder_finish_bound(b);
        assert_eq!(fb, 2 * qs_builder_memory(b));
        assert_eq!(qs_builder_set_budget(b, qs_builder_memory(b) + fb - 1), 0);
        let (mut bytes, mut len, mut docs) = (std::ptr::null_mut(), 0usize, 0u64);
        let base = counting_alloc::reset_peak();
        let rc = qs_builder_finish(b, &mut bytes, &mut len, &mut docs, &mut err);
        let peak = counting_alloc::peak() - base;
        let msg = take_err(err);
        assert_ne!(rc, 0);
        assert!(msg.starts_with("search_memory_exceeded: "), "{msg}");
        assert!(peak < 64 * 1024, "the refused finish allocated {peak} bytes");
        qs_bytes_free(bytes, len);
    }
    // Through the Rust API: a builder whose controls hold the budget.
    let mut b = Builder::with_controls(&src, Controls::new(None, u64::MAX)).unwrap();
    b.add(0, 0, -1, &[Some("warm up doc")]).unwrap();
    let held = b.memory();
    b.set_budget(held + 2 * text.len() as u64);
    assert!(b.add(1, 1, -1, &[Some(&text)]).unwrap_err().code() == "search_memory_exceeded");
}
