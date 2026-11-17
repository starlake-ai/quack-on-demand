//! The matches iterator (search spec 7.1 steps 6 and 7): every matching alive doc of a search's
//! arguments, segment by segment in the call's segment order and doc order within one, filled into
//! the caller's buffer a batch at a time.
//!
//! The open compiles the call exactly as the exact top k does (`search::compile`: the same checks,
//! statistics and weights), and each segment is read through the same `search::DocStream` the
//! collector reads, so a hit's score is bit-identical to the top k's for the same doc by
//! construction. Between two calls the iterator holds the open's alive bitsets and one segment's
//! stream (the scorers' own block buffers), never a list or bitset of the segment's matches.
use crate::bundle::Segment;
use crate::controls::Controls;
use crate::search::{self, bit, Compiled, DocStream, Hit, Population};
use crate::{Error, Result};

/// A matches iterator. The segments and the controls' cancel flag are borrowed for its life; the
/// bitsets are copied at the open.
pub struct Matches<'a> {
    segments: Vec<&'a Segment>,
    /// None when nothing can match (no segment, no alive doc, an empty population).
    compiled: Option<Compiled>,
    /// Input segment -> reader ord of the union.
    reader_of: Vec<usize>,
    controls: Controls<'a>,
    with_score: bool,
    /// The input segment the next hit comes from.
    seg: usize,
    stream: Option<DocStream>,
    /// The error that ended the iterator: every later call returns it.
    failed: Option<Error>,
}

impl<'a> Matches<'a> {
    /// Checks and compiles the call (`k` has no meaning here). Fails as the top k would.
    #[allow(clippy::too_many_arguments)]
    pub fn open(
        segments: &[&'a Segment],
        live: &[&[u64]],
        allowed: &[&[u64]],
        population: Population,
        query: &str,
        fields: &[(&str, i64)],
        k1: f32,
        b: f32,
        controls: Controls<'a>,
        with_score: bool,
    ) -> Result<Matches<'a>> {
        let compiled = search::compile(segments, live, allowed, population, query, fields, k1, b, None, &controls)?;
        let mut reader_of = vec![0usize; segments.len()];
        if let Some(c) = &compiled {
            for (ord, input) in c.order.iter().enumerate() {
                reader_of[*input] = ord;
            }
        }
        Ok(Matches {
            segments: segments.to_vec(),
            compiled,
            reader_of,
            controls,
            with_score,
            seg: 0,
            stream: None,
            failed: None,
        })
    }

    /// Fills `out` with the next hits, at most `out.len()` (at least 1); 0 means done. A failed call
    /// (cancelled, a corrupt segment) ends the iterator: this and every later call return the error.
    pub fn fill(&mut self, out: &mut [Hit]) -> Result<usize> {
        self.fill_with(out.len(), &mut |i, h| out[i] = h)
    }

    /// `fill` through a sink: `emit(i, hit)` for the i-th hit of this call, at most `cap` (at least 1).
    pub fn fill_with(&mut self, cap: usize, emit: &mut dyn FnMut(usize, Hit)) -> Result<usize> {
        if cap == 0 {
            return Err(Error::invalid("out holds no hit"));
        }
        if let Some(e) = &self.failed {
            return Err(e.clone());
        }
        match self.fill_inner(cap, emit) {
            Ok(n) => Ok(n),
            Err(e) => {
                self.stream = None;
                self.failed = Some(e.clone());
                Err(e)
            }
        }
    }

    fn fill_inner(&mut self, cap: usize, emit: &mut dyn FnMut(usize, Hit)) -> Result<usize> {
        let Matches { segments, compiled, reader_of, controls, with_score, seg, stream, .. } = self;
        controls.check()?;
        let Some(c) = compiled.as_ref() else { return Ok(0) };
        let mut n = 0usize;
        while n < cap && *seg < segments.len() {
            if stream.is_none() {
                controls.check()?;
                let reader = c.searcher.segment_reader(reader_of[*seg] as u32);
                *stream = Some(DocStream::new(&c.exec, reader, controls)?);
            }
            let Some(s) = stream.as_mut() else { return Err(Error::internal("no stream")) };
            match s.next(controls)? {
                None => {
                    *stream = None;
                    *seg += 1;
                }
                Some(doc) => {
                    if !bit(&c.alive[*seg], doc) {
                        continue;
                    }
                    let score = if *with_score { s.score(doc, controls)? } else { f32::NAN };
                    let rowid = segments[*seg].rowid(doc).ok_or_else(|| Error::internal("a hit past the segment's docs"))?;
                    emit(n, Hit { rowid, segment: *seg as u32, doc, score });
                    n += 1;
                }
            }
        }
        Ok(n)
    }
}
