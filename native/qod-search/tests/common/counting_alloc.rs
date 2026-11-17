//! A counting global allocator for the test binaries that include it: the bytes the current thread
//! holds and their peak since the last reset. Counted per thread, so tests running in parallel do not
//! disturb each other's figures (memory freed on another thread lowers the freeing thread's count).
//! With the library's `test-hooks` feature the library installs the same counting allocator itself
//! (`qod_search_core::test_alloc`, for qod-node's test programs), and this module reads its figures.
#[cfg(feature = "test-hooks")]
pub use qod_search_core::test_alloc::{held, peak, reset_peak};

#[cfg(not(feature = "test-hooks"))]
pub use own::*;

#[cfg(not(feature = "test-hooks"))]
mod own {
    use std::alloc::{GlobalAlloc, Layout, System};
    use std::cell::Cell;

    pub struct Counting;

    thread_local! {
        static HELD: Cell<i64> = const { Cell::new(0) };
        static PEAK: Cell<i64> = const { Cell::new(0) };
    }

    fn grow(n: usize) {
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
}
