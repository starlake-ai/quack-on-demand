//! Cancellation and the memory budget of a call (search spec 9, invariant 8). A call checks its
//! cancel flag at least every 64 postings blocks, every 65,536 docs of a statistics pass and between
//! segments, and charges what it allocates in proportion to the table (bitsets, the candidate heap,
//! df cache entries, a build's buffers) against its budget. Either failure ends the call with an
//! error; nothing partial is returned.
use std::sync::atomic::{AtomicU64, AtomicU8, Ordering};

use crate::{Error, Result};

/// Postings decoded between two cancel checks of a df walk (32 blocks of 128: within spec 9's 64).
pub const POSTINGS_PER_CHECK: usize = 4_096;
/// Docs scored between two cancel checks of the collector (64 blocks of 128).
pub const SCORED_DOCS_PER_CHECK: u32 = 8_192;
/// Docs of a statistics pass between two cancel checks (spec 9).
pub const STATS_DOCS_PER_CHECK: usize = 65_536;

/// A call's controls. `cancel` non-zero means cancelled (one byte, so a C++ caller can pass a
/// `std::atomic<bool>`); `memory_budget` is in bytes, `u64::MAX` for none.
pub struct Controls<'a> {
    cancel: Option<&'a AtomicU8>,
    memory_budget: u64,
    used: AtomicU64,
}

impl<'a> Controls<'a> {
    pub fn new(cancel: Option<&'a AtomicU8>, memory_budget: u64) -> Controls<'a> {
        Controls { cancel, memory_budget, used: AtomicU64::new(0) }
    }

    /// No cancel flag, no budget.
    pub fn unlimited() -> Controls<'static> {
        Controls::new(None, u64::MAX)
    }

    /// Replaces the budget (a caller bounding several builders jointly sets each one's to what is left).
    pub fn set_budget(&mut self, memory_budget: u64) {
        self.memory_budget = memory_budget;
    }

    pub fn is_cancelled(&self) -> bool {
        self.cancel.map(|c| c.load(Ordering::Relaxed) != 0).unwrap_or(false)
    }

    /// `search_cancelled` once the flag is set.
    pub fn check(&self) -> Result<()> {
        if self.is_cancelled() {
            Err(Error::new("search_cancelled", "the call was cancelled"))
        } else {
            Ok(())
        }
    }

    /// Charges `bytes` against the budget: `search_memory_exceeded` past it.
    pub fn charge(&self, bytes: u64) -> Result<()> {
        let before = self.used.fetch_add(bytes, Ordering::Relaxed);
        let now = before.saturating_add(bytes);
        if now > self.memory_budget {
            Err(Error::new(
                "search_memory_exceeded",
                &format!("the call needs more than its budget of {} bytes", self.memory_budget),
            ))
        } else {
            Ok(())
        }
    }

    /// Whether `bytes` in total would fit the budget, without charging.
    pub fn fits(&self, bytes: u64) -> Result<()> {
        if bytes > self.memory_budget {
            Err(Error::new(
                "search_memory_exceeded",
                &format!("the call needs more than its budget of {} bytes", self.memory_budget),
            ))
        } else {
            Ok(())
        }
    }

    pub fn used(&self) -> u64 {
        self.used.load(Ordering::Relaxed)
    }
}
