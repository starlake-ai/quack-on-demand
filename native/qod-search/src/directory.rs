//! The per-call Tantivy `Directory` of search spec 5.2: the opened segments' files served as stored
//! (footer included), a `meta.json` synthesized for the union, and an in-memory write overlay for
//! `.managed.json` and the per-call `.del` files that carry each segment's alive bitset
//! (live AND allowed). Ported from the part 1 probe crate (`union`, `with_alive`), which copied the
//! files into a RamDirectory; here nothing is copied: each file is a slice of its bundle's bytes.
use std::collections::HashMap;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use tantivy::directory::error::{DeleteError, OpenReadError, OpenWriteError};
use tantivy::directory::{
    Directory, FileHandle, FileSlice, ManagedDirectory, RamDirectory, TerminatingWrite, WatchCallback,
    WatchHandle, WritePtr,
};
use tantivy::index::SegmentId;
use tantivy::{Index, IndexReader, IndexSettings, ReloadPolicy};

use crate::bundle::{self, Segment};
use crate::controls::Controls;
use crate::{Error, Result};

#[derive(Clone, Debug)]
pub(crate) struct UnionDirectory {
    files: Arc<HashMap<PathBuf, FileSlice>>,
    overlay: RamDirectory,
}

impl Directory for UnionDirectory {
    fn get_file_handle(&self, path: &Path) -> std::result::Result<Arc<dyn FileHandle>, OpenReadError> {
        if self.overlay.exists(path)? {
            return self.overlay.get_file_handle(path);
        }
        match self.files.get(path) {
            Some(slice) => Ok(Arc::new(slice.clone())),
            None => Err(OpenReadError::FileDoesNotExist(path.to_path_buf())),
        }
    }

    fn delete(&self, path: &Path) -> std::result::Result<(), DeleteError> {
        if self.files.contains_key(path) {
            // Segment files are read only; nothing garbage collects in a per-call index.
            return Ok(());
        }
        self.overlay.delete(path)
    }

    fn exists(&self, path: &Path) -> std::result::Result<bool, OpenReadError> {
        Ok(self.files.contains_key(path) || self.overlay.exists(path)?)
    }

    fn open_write(&self, path: &Path) -> std::result::Result<WritePtr, OpenWriteError> {
        if self.files.contains_key(path) {
            return Err(OpenWriteError::FileAlreadyExists(path.to_path_buf()));
        }
        self.overlay.open_write(path)
    }

    fn atomic_read(&self, path: &Path) -> std::result::Result<Vec<u8>, OpenReadError> {
        if self.overlay.exists(path)? {
            return self.overlay.atomic_read(path);
        }
        match self.files.get(path) {
            Some(slice) => slice
                .read_bytes()
                .map(|b| b.as_slice().to_vec())
                .map_err(|e| OpenReadError::wrap_io_error(e, path.to_path_buf())),
            None => Err(OpenReadError::FileDoesNotExist(path.to_path_buf())),
        }
    }

    fn atomic_write(&self, path: &Path, data: &[u8]) -> io::Result<()> {
        if self.files.contains_key(path) {
            return Err(io::Error::new(io::ErrorKind::AlreadyExists, "segment files are read only"));
        }
        self.overlay.atomic_write(path, data)
    }

    fn sync_directory(&self) -> io::Result<()> {
        Ok(())
    }

    fn watch(&self, _: WatchCallback) -> tantivy::Result<WatchHandle> {
        // The union is opened under ReloadPolicy::Manual: nothing watches meta.json.
        Ok(WatchHandle::empty())
    }
}

/// A segment id for the i-th segment of a call: segment readers are mapped back to their input by
/// this id, never by reader position (spec 5.2).
pub(crate) fn segment_id_for(i: usize) -> Result<SegmentId> {
    SegmentId::from_uuid_string(&format!("51d0{:028x}", i as u128))
        .map_err(|_| Error::internal("cannot make a segment id"))
}

#[cfg(test)]
pub(crate) fn input_for_segment_id(id: SegmentId, n: usize) -> Option<usize> {
    (0..n).find(|i| segment_id_for(*i).map(|s| s == id).unwrap_or(false))
}

/// Words of a bitset over `n` docs.
pub(crate) fn words(n: u32) -> usize {
    (n as usize).div_ceil(64)
}

#[cfg(feature = "test-hooks")]
thread_local! {
    /// Per-call unions this thread has built (`qs_test_union_builds`, test hooks only).
    pub(crate) static UNION_BUILDS: std::cell::Cell<u64> = const { std::cell::Cell::new(0) };
}

/// The union of `segments` as one index, one text field `f<id>` per field id of the segments (every
/// segment must hold the same field ids, in the same order). With `alive` (bit d of word
/// d / 64 per segment; bits at or past the segment's doc count are ignored), every segment with a
/// dead doc gets a delete file in the overlay, written through Tantivy's managed directory (which
/// adds the footer every component is opened with, spec 5.2): only Tantivy's own collectors read
/// those. The exact collector filters by the bitsets itself and passes `None`.
pub(crate) fn union_index(
    segments: &[&Segment],
    alive: Option<&[Vec<u64>]>,
    controls: &Controls,
) -> Result<(Index, IndexReader)> {
    #[cfg(feature = "test-hooks")]
    UNION_BUILDS.with(|c| c.set(c.get() + 1));
    if alive.map(|a| a.len() != segments.len()).unwrap_or(false) {
        return Err(Error::internal("one alive bitset per segment"));
    }
    let field_ids = segments.first().map(|s| s.field_ids().to_vec()).ok_or_else(|| Error::internal("a union of no segment"))?;
    if segments.iter().any(|s| s.field_ids() != field_ids.as_slice()) {
        return Err(Error::internal("a union of segments of different field sets"));
    }
    let mut files = HashMap::new();
    let mut ids = Vec::with_capacity(segments.len());
    for (i, seg) in segments.iter().enumerate() {
        let id = segment_id_for(i)?;
        for (ext, bytes) in seg.tantivy_files() {
            files.insert(PathBuf::from(format!("{}.{ext}", id.uuid_string())), FileSlice::new(Arc::new(bytes)));
        }
        ids.push(id);
    }
    let dir = UnionDirectory { files: Arc::new(files), overlay: RamDirectory::create() };
    let (schema, _, _) = bundle::schema(&field_ids);
    let schema_json = serde_json::to_value(&schema).map_err(|e| Error::internal(&format!("schema: {e}")))?;
    let settings_json =
        serde_json::to_value(IndexSettings::default()).map_err(|e| Error::internal(&format!("settings: {e}")))?;
    let mut deleted = vec![0u32; segments.len()];
    if let Some(alive) = alive {
        let managed = ManagedDirectory::wrap(Box::new(dir.clone()))
            .map_err(|e| Error::internal(&format!("managed directory: {e}")))?;
        for (i, ((seg, id), words_in)) in segments.iter().zip(&ids).zip(alive).enumerate() {
            controls.check()?;
            let n = seg.num_docs();
            controls.charge(4 + 8 * words(n) as u64)?;
            let (bytes, dead) = alive_bitset_bytes(n, words_in);
            if dead > 0 {
                let path = PathBuf::from(format!("{}.1.del", id.uuid_string()));
                let mut w = managed.open_write(&path).map_err(|e| Error::internal(&format!("delete file: {e}")))?;
                io::Write::write_all(&mut w, &bytes).map_err(|e| Error::internal(&format!("delete file: {e}")))?;
                w.terminate().map_err(|e| Error::internal(&format!("delete file: {e}")))?;
            }
            deleted[i] = dead;
        }
    }
    let segments_json: Vec<serde_json::Value> = segments
        .iter()
        .zip(&ids)
        .zip(&deleted)
        .map(|((seg, id), dead)| {
            let deletes = if *dead > 0 {
                serde_json::json!({ "num_deleted_docs": dead, "opstamp": 1 })
            } else {
                serde_json::Value::Null
            };
            serde_json::json!({ "segment_id": id, "max_doc": seg.num_docs(), "deletes": deletes })
        })
        .collect();
    let meta = serde_json::json!({
        "index_settings": settings_json,
        "segments": segments_json,
        "schema": schema_json,
        "opstamp": 1,
    });
    let meta = serde_json::to_vec(&meta).map_err(|e| Error::internal(&format!("meta.json: {e}")))?;
    dir.atomic_write(Path::new("meta.json"), &meta).map_err(|e| Error::internal(&format!("meta.json: {e}")))?;
    let index = Index::open(dir).map_err(|e| Error::internal(&format!("cannot open the union: {e}")))?;
    let reader = index
        .reader_builder()
        .reload_policy(ReloadPolicy::Manual)
        .try_into()
        .map_err(|e: tantivy::TantivyError| Error::internal(&format!("cannot open the union: {e}")))?;
    Ok((index, reader))
}

/// Tantivy's serialized alive bitset (`BitSet::serialize`, the bytes `write_alive_bitset` writes: the
/// u32 bound then one u64 per 64 docs, little endian) built word by word from `words_in`, with the
/// bits past `n` cleared, and the number of dead docs. A missing word counts as all dead. A test pins
/// this against `write_alive_bitset`.
pub(crate) fn alive_bitset_bytes(n: u32, words_in: &[u64]) -> (Vec<u8>, u32) {
    let nw = words(n);
    let mut out = Vec::with_capacity(4 + 8 * nw);
    out.extend_from_slice(&n.to_le_bytes());
    let mut alive = 0u32;
    for w in 0..nw {
        let mut word = words_in.get(w).copied().unwrap_or(0);
        if w == nw - 1 && n % 64 != 0 {
            word &= (1u64 << (n % 64)) - 1;
        }
        alive += word.count_ones();
        out.extend_from_slice(&word.to_le_bytes());
    }
    (out, n - alive)
}

/// Opens `segment` alone, every doc alive, and checks what its Tantivy files say against its own
/// columns: per field, the doc count of the field norms and the token total. A bundle failing this is
/// corrupt.
pub(crate) fn check_opens(segment: &Segment) -> Result<()> {
    let n = segment.num_docs();
    let (_, reader) = union_index(&[segment], None, &Controls::unlimited())
        .map_err(|e| Error::corrupt(&format!("Tantivy cannot open the segment: {}", e.message())))?;
    let searcher = reader.searcher();
    let r = searcher.segment_readers().first().ok_or_else(|| Error::corrupt("no segment reader"))?;
    if r.max_doc() != n {
        return Err(Error::corrupt("the Tantivy segment holds another doc count"));
    }
    let (_, fields, _) = bundle::schema(segment.field_ids());
    for (i, field) in fields.iter().enumerate() {
        let norms = r.get_fieldnorms_reader(*field).map_err(|e| Error::corrupt(&format!("no field norms: {e}")))?;
        if norms.num_docs() != n {
            return Err(Error::corrupt("the Tantivy segment holds another doc count"));
        }
        let total = r
            .inverted_index(*field)
            .map_err(|e| Error::corrupt(&format!("no inverted index: {e}")))?
            .total_num_tokens();
        let exact: u64 = segment.token_counts(i).iter().map(|c| *c as u64).sum();
        if total != exact {
            return Err(Error::corrupt("the token count column disagrees with the Tantivy segment"));
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tantivy::BitSet;

    #[test]
    fn alive_bitset_bytes_equal_tantivy_serialization() {
        for n in [1u32, 63, 64, 65, 130, 1000] {
            let words_in: Vec<u64> = (0..words(n) as u64).map(|w| w.wrapping_mul(0x9e37_79b9_7f4a_7c15) | 1).collect();
            let mut bitset = BitSet::with_max_value(n);
            for d in 0..n {
                if (words_in[(d / 64) as usize] >> (d % 64)) & 1 == 1 {
                    bitset.insert(d);
                }
            }
            let mut want = Vec::new();
            tantivy::fastfield::write_alive_bitset(&bitset, &mut want).unwrap();
            let (got, dead) = alive_bitset_bytes(n, &words_in);
            assert_eq!(got, want, "n = {n}");
            assert_eq!(dead as usize, n as usize - bitset.len(), "n = {n}");
        }
    }

    #[test]
    fn segment_ids_round_trip() {
        for i in [0usize, 1, 7, 1000] {
            assert_eq!(input_for_segment_id(segment_id_for(i).unwrap(), 1001), Some(i));
        }
    }
}
