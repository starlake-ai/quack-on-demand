//! TEST ONLY (feature `test-hooks`): a counting global allocator for every program the feature's build
//! links (the library's integration tests and qod-node's test programs). Two figures: the bytes the
//! whole process's Rust code holds (`total`, read by `qs_test_allocated_bytes`), and per thread the
//! bytes it holds and their peak since the last reset (`held`, `reset_peak`, `peak`), so tests running
//! in parallel do not disturb each other's figures (memory freed on another thread lowers the freeing
//! thread's count). Never in a shipped binary: qod-node links the build without the feature.
use std::alloc::{GlobalAlloc, Layout, System};
use std::cell::Cell;
use std::sync::atomic::{AtomicI64, Ordering};

pub struct Counting;

#[global_allocator]
static ALLOC: Counting = Counting;

static TOTAL: AtomicI64 = AtomicI64::new(0);

thread_local! {
    static HELD: Cell<i64> = const { Cell::new(0) };
    static PEAK: Cell<i64> = const { Cell::new(0) };
}

fn grow(n: usize) {
    TOTAL.fetch_add(n as i64, Ordering::Relaxed);
    let _ = HELD.try_with(|h| {
        let v = h.get() + n as i64;
        h.set(v);
        let _ = PEAK.try_with(|p| {
            if v > p.get() {
                p.set(v)
            }
        });
    });
}

fn shrink(n: usize) {
    TOTAL.fetch_sub(n as i64, Ordering::Relaxed);
    let _ = HELD.try_with(|h| h.set(h.get() - n as i64));
}

unsafe impl GlobalAlloc for Counting {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        let p = System.alloc(layout);
        if !p.is_null() {
            grow(layout.size());
        }
        p
    }
    unsafe fn alloc_zeroed(&self, layout: Layout) -> *mut u8 {
        let p = System.alloc_zeroed(layout);
        if !p.is_null() {
            grow(layout.size());
        }
        p
    }
    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        System.dealloc(ptr, layout);
        shrink(layout.size());
    }
    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
        let p = System.realloc(ptr, layout, new_size);
        if !p.is_null() {
            shrink(layout.size());
            grow(new_size);
        }
        p
    }
}

/// The bytes every thread's Rust code holds now (0 when the count is below zero, which a free of memory
/// allocated before the count began could make it).
pub fn total() -> u64 {
    TOTAL.load(Ordering::Relaxed).max(0) as u64
}

/// The bytes this thread holds now.
pub fn held() -> i64 {
    HELD.with(|h| h.get())
}

/// Resets this thread's peak to what it holds now, and returns that baseline.
pub fn reset_peak() -> i64 {
    let now = held();
    PEAK.with(|p| p.set(now));
    now
}

/// This thread's peak since the last reset.
pub fn peak() -> i64 {
    PEAK.with(|p| p.get())
}
