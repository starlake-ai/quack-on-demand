//! The segment bundle of search spec 6.2 (without encryption): one Tantivy segment of one DuckLake
//! data file, packed with its rowid, snapshot and exact token count columns and a footer that names
//! the source file. Built from bytes and opened from bytes: this module does no I/O.
//!
//! Layout, all little endian. Every byte before the trailer belongs to exactly one region or to the
//! footer body, and each is covered by a CRC32:
//!
//! ```text
//! region 0, header : MAGIC (8) | format_version u32 | tantivy_version (u16 length, bytes)
//! regions 1..      : the Tantivy segment files, in ascending extension order, each stored as
//!                    Tantivy wrote it (its own footer included), named by its extension only
//!                    (idx, pos, term, store, fast, fieldnorm), never by the build's random segment id
//! rowids           : u8 encoding (0 = affine: i64 row_id_start, rowid = start + position;
//!                    1 = explicit: one i64 per doc)
//! snapshots        : one i64 per doc, present only when the file has _ducklake_internal_snapshot_id
//! token counts     : one u32 per doc, the field's exact token count (spec 7.5, change 28)
//! footer body      : FOOTER_MAGIC (8) | format_version u32
//!                    | index_id (u16 len) | path_key (u16 len) | path (u32 len)
//!                    | file_size u64 | footer_size u64 | num_docs u32 | flags u8
//!                    | region count u32 | per region: kind u8, name (u16 len), offset u64,
//!                      len u64, crc32 u32
//! trailer (28)     : footer body offset u64 | footer body crc32 u32 | total length u64
//!                    | END_MAGIC (8)
//! ```
//!
//! A bundle that fails any structural or checksum test is `bundle_corrupt`; one whose format or
//! Tantivy version, or whose source fields, differ from what the reader expects is
//! `bundle_mismatch` (spec 6.2: treated as missing by the caller).
use std::collections::BTreeMap;
use std::ops::Range;

use tantivy::directory::{Directory, OwnedBytes, RamDirectory};
use tantivy::schema::{Field, Schema, FAST, TEXT};
use tantivy::tokenizer::TextAnalyzer;
use tantivy::{doc, Index, IndexSettings, SingleSegmentIndexWriter, TantivyDocument};

use crate::controls::Controls;
use crate::sha256;
use crate::{Error, Result};

pub const MAGIC: [u8; 8] = *b"QODSEG\0\x01";
pub const FOOTER_MAGIC: [u8; 8] = *b"QODSEGFB";
pub const END_MAGIC: [u8; 8] = *b"QODSEG\0\xFF";
pub const FORMAT_VERSION: u32 = 1;
pub(crate) const TRAILER_LEN: usize = 28;

/// The name of the internal fast field holding each doc's position (a build check, and never
/// queryable: rule 6 refuses any field but the call's own).
pub(crate) const POSITION_FIELD: &str = "__qod_position";
/// The text field's name inside a bundle. The per-call union names it after the call's column
/// (`search::top_k`'s `field`); Tantivy refers to fields by number in segment files, and only fast
/// fields by name.
pub(crate) const BUILD_TEXT_FIELD: &str = "text";

/// The SingleSegmentIndexWriter's budget only sizes its initial term hash table (capped by Tantivy);
/// the arena grows as needed. `MAX_WRITER_BYTES` is the guard on that growth: Tantivy's arena
/// addresses are 32-bit, so a build past it fails here instead of inside Tantivy.
const WRITER_BUDGET: usize = 256 * 1024 * 1024;
const MAX_WRITER_BYTES: usize = 3 * 1024 * 1024 * 1024;

const KIND_HEADER: u8 = 0;
const KIND_TANTIVY_FILE: u8 = 1;
const KIND_ROWIDS: u8 = 2;
const KIND_SNAPSHOTS: u8 = 3;
const KIND_TOKEN_COUNTS: u8 = 4;

const FLAG_EXPLICIT_ROWIDS: u8 = 1;
const FLAG_SNAPSHOT_COLUMN: u8 = 2;

/// The segment file extensions a bundle may carry (SegmentComponent's, without `del` and `temp`).
const TANTIVY_EXTENSIONS: [&str; 6] = ["fast", "fieldnorm", "idx", "pos", "store", "term"];

/// The lowercase hex SHA-256 of a data file's absolute path: the bundle key (OWNER RULING 2026-11-14).
pub fn path_key(path: &str) -> String {
    sha256::hex_digest(path.as_bytes())
}

/// The source fields a bundle records and a reader checks (spec 6.2).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Source {
    /// Canonical lowercase hyphenated UUID, 36 characters.
    pub index_id: String,
    /// Lowercase hex SHA-256 of `path`, 64 characters.
    pub path_key: String,
    /// The data file's absolute path as the scan opens it.
    pub path: String,
    pub file_size: u64,
    pub footer_size: u64,
    /// The catalog's row count of the file, -1 when the caller cannot supply it (unchecked).
    pub record_count: i64,
    /// The catalog's `row_id_start`, -1 when NULL or unknown (unchecked).
    pub row_id_start: i64,
    /// 1 when the file carries `_ducklake_internal_row_id`, 0 when not, -1 unknown (unchecked).
    pub has_internal_row_id: i8,
    /// 1 when the file carries `_ducklake_internal_snapshot_id`, 0 when not, -1 unknown.
    pub has_internal_snapshot: i8,
}

impl Source {
    /// A source whose `path_key` is computed from `path`.
    pub fn new(index_id: &str, path: &str, file_size: u64, footer_size: u64) -> Source {
        Source {
            index_id: index_id.to_string(),
            path_key: path_key(path),
            path: path.to_string(),
            file_size,
            footer_size,
            record_count: -1,
            row_id_start: -1,
            has_internal_row_id: -1,
            has_internal_snapshot: -1,
        }
    }

    /// Refuses a source that is not well formed: an index id that is not a canonical UUID, a path
    /// key that is not the SHA-256 of the path, an empty path.
    pub fn validate(&self) -> Result<()> {
        if !is_canonical_uuid(&self.index_id) {
            return Err(Error::invalid("index_id is not a canonical lowercase hyphenated UUID"));
        }
        if self.path.is_empty() || self.path.len() > u32::MAX as usize {
            return Err(Error::invalid("path is empty or too long"));
        }
        if !is_lower_hex(&self.path_key, 64) {
            return Err(Error::invalid("path_key is not 64 lowercase hex digits"));
        }
        if self.path_key != path_key(&self.path) {
            return Err(Error::invalid("path_key is not the SHA-256 of path"));
        }
        if self.record_count < -1 || self.row_id_start < -1 {
            return Err(Error::invalid("record_count and row_id_start are -1 (unchecked) or at least 0"));
        }
        if !(-1..=1).contains(&self.has_internal_row_id) || !(-1..=1).contains(&self.has_internal_snapshot) {
            return Err(Error::invalid("has_internal_row_id and has_internal_snapshot are -1, 0 or 1"));
        }
        Ok(())
    }

    /// Checks what the catalog says of the file (the fields not -1) against a bundle's doc count,
    /// snapshot column and rowids. `what` makes the error: `bundle_build` while building,
    /// `bundle_mismatch` at open (the bundle then reads as missing).
    fn check_contents(
        &self,
        num_docs: u64,
        has_snapshot_column: bool,
        affine_start: Option<i64>,
        what: fn(&str) -> Error,
    ) -> Result<()> {
        if self.record_count >= 0 && self.record_count as u64 != num_docs {
            return Err(what(&format!("{num_docs} docs, the catalog records {} rows", self.record_count)));
        }
        if self.has_internal_snapshot >= 0 && (self.has_internal_snapshot == 1) != has_snapshot_column {
            return Err(what("the snapshot column does not match the file's _ducklake_internal_snapshot_id"));
        }
        // A file without _ducklake_internal_row_id has rowids row_id_start + position (spec 6.2).
        if self.has_internal_row_id == 0 && self.row_id_start >= 0 && affine_start != Some(self.row_id_start) {
            return Err(what("the rowids are not row_id_start + position"));
        }
        Ok(())
    }
}

/// The domain-separation label of a sealed bundle's subkey (spec 6.2, change 16), for the encryption
/// task: `qod-search-bundle|v<format version>|<index id>|<path key>`, from a validated source.
pub fn seal_label(source: &Source) -> Result<String> {
    source.validate()?;
    Ok(format!("qod-search-bundle|v{FORMAT_VERSION}|{}|{}", source.index_id, source.path_key))
}

fn is_lower_hex(s: &str, len: usize) -> bool {
    s.len() == len && s.bytes().all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
}

fn is_canonical_uuid(s: &str) -> bool {
    let b = s.as_bytes();
    b.len() == 36
        && b.iter().enumerate().all(|(i, c)| match i {
            8 | 13 | 18 | 23 => *c == b'-',
            _ => c.is_ascii_digit() || (b'a'..=b'f').contains(c),
        })
}

/// One row of a data file, in position order.
#[derive(Clone, Debug)]
pub struct Doc {
    pub position: u64,
    pub rowid: i64,
    /// The row's `_ducklake_internal_snapshot_id`, or -1 when the file has no such column.
    pub snapshot: i64,
    pub text: String,
}

/// The schema of a bundle (text field named `text_name`) and of a per-call union (named after the
/// call's column). Field 0 is the text, field 1 the position: the same numbers in both.
pub(crate) fn schema(text_name: &str) -> (Schema, Field, Field) {
    let mut builder = Schema::builder();
    let text = builder.add_text_field(text_name, TEXT);
    let position = builder.add_u64_field(POSITION_FIELD, FAST);
    (builder.build(), text, position)
}

/// Builds a bundle doc by doc (the C ABI's `qs_builder_*`).
pub struct Builder<'a> {
    controls: Controls<'a>,
    source: Source,
    writer: SingleSegmentIndexWriter<TantivyDocument>,
    text: Field,
    position: Field,
    analyzer: TextAnalyzer,
    rowids: Vec<i64>,
    snapshots: Vec<i64>,
    has_snapshot: Option<bool>,
    counts: Vec<u32>,
}

impl Builder<'static> {
    /// A builder with no cancel flag and no memory budget.
    pub fn new(source: &Source) -> Result<Builder<'static>> {
        Builder::with_controls(source, Controls::unlimited())
    }
}

impl<'a> Builder<'a> {
    /// A builder whose calls check `controls` (spec 9: a build is interruptible and bounded).
    pub fn with_controls(source: &Source, controls: Controls<'a>) -> Result<Builder<'a>> {
        source.validate()?;
        controls.check()?;
        let (schema, text, position) = schema(BUILD_TEXT_FIELD);
        let writer = Index::builder()
            .schema(schema)
            .settings(IndexSettings::default())
            .single_segment_index_writer(RamDirectory::create(), WRITER_BUDGET)
            .map_err(|e| Error::build(&format!("cannot start the segment writer: {e}")))?;
        // The TEXT field's analyzer, as Tantivy's default TokenizerManager registers it ("default").
        let analyzer = tantivy::tokenizer::TokenizerManager::default()
            .get("default")
            .ok_or_else(|| Error::build("no default tokenizer"))?;
        Ok(Builder {
            controls,
            source: source.clone(),
            writer,
            text,
            position,
            analyzer,
            rowids: Vec::new(),
            snapshots: Vec::new(),
            has_snapshot: None,
            counts: Vec::new(),
        })
    }

    /// Adds the doc at `position`, which must be the number of docs added so far (doc id = physical
    /// position, spec 6.2).
    pub fn add(&mut self, position: u64, rowid: i64, snapshot: i64, text: &str) -> Result<()> {
        self.controls.check()?;
        // Bound one text before indexing it (it alone could pass the writer's guard).
        if text.len() > MAX_WRITER_BYTES {
            return Err(Error::build("a doc's text is too large for one segment"));
        }
        self.controls.fits(self.accounted_bytes().saturating_add(2 * text.len() as u64))?;
        let expected = self.rowids.len() as u64;
        if position != expected {
            return Err(Error::build(&format!(
                "docs must be added in position order: got position {position}, expected {expected}"
            )));
        }
        if expected >= u32::MAX as u64 {
            return Err(Error::build("more docs than a segment holds"));
        }
        if snapshot < -1 {
            return Err(Error::build("snapshot id below -1"));
        }
        let with_snapshot = snapshot != -1;
        match self.has_snapshot {
            None => self.has_snapshot = Some(with_snapshot),
            Some(h) if h != with_snapshot => {
                return Err(Error::build(
                    "snapshot ids given for some docs only (-1 means the file has no snapshot column)",
                ))
            }
            Some(_) => {}
        }
        let mut count = 0u64;
        {
            let mut stream = self.analyzer.token_stream(text);
            while stream.advance() {
                count += 1;
            }
        }
        let count = u32::try_from(count).map_err(|_| Error::build("a doc holds more than 2^32 tokens"))?;
        self.writer
            .add_document(doc!(self.text => text, self.position => position))
            .map_err(|e| Error::build(&format!("cannot add a doc: {e}")))?;
        if self.writer.mem_usage() > MAX_WRITER_BYTES {
            return Err(Error::build("the data file is too large for one segment"));
        }
        self.rowids.push(rowid);
        if with_snapshot {
            self.snapshots.push(snapshot);
        }
        self.counts.push(count);
        self.controls.fits(self.accounted_bytes())?;
        Ok(())
    }

    /// What the build holds so far: the writer's arena and the three columns.
    fn accounted_bytes(&self) -> u64 {
        (self.writer.mem_usage() as u64)
            .saturating_add(8 * self.rowids.len() as u64)
            .saturating_add(8 * self.snapshots.len() as u64)
            .saturating_add(4 * self.counts.len() as u64)
    }

    /// The whole bundle and its doc count.
    pub fn finish(self) -> Result<(Vec<u8>, u64)> {
        self.controls.check()?;
        let held = self.accounted_bytes();
        let Builder { controls, source, writer, text, position: _, rowids, snapshots, has_snapshot, counts, .. } = self;
        let n = rowids.len();
        if n == 0 {
            return Err(Error::build("no docs: a data file with no rows needs no bundle"));
        }
        let rowid_region = encode_rowids(&rowids);
        let explicit = rowid_region.first() == Some(&1);
        let affine_start = if explicit { None } else { rowids.first().copied() };
        source.check_contents(n as u64, has_snapshot == Some(true), affine_start, Error::build)?;
        let index = writer.finalize().map_err(|e| Error::build(&format!("cannot finalize the segment: {e}")))?;
        controls.check()?;
        let files = segment_files(&index, n, text, &counts)?;
        // The bundle is about the size of the files plus the columns; with the build's buffers it
        // must fit the budget.
        let file_bytes: u64 = files.values().map(|f| f.len() as u64).sum();
        controls.fits(held.saturating_add(2 * file_bytes))?;
        controls.check()?;
        let snapshot_region = if has_snapshot == Some(true) {
            let mut out = Vec::with_capacity(8 * n);
            for s in &snapshots {
                out.extend_from_slice(&s.to_le_bytes());
            }
            Some(out)
        } else {
            None
        };
        let mut count_region = Vec::with_capacity(4 * n);
        for c in &counts {
            count_region.extend_from_slice(&c.to_le_bytes());
        }

        let mut out = Vec::new();
        let mut regions: Vec<(u8, String, u64, u64, u32)> = Vec::new();
        let mut push = |out: &mut Vec<u8>, kind: u8, name: &str, bytes: &[u8]| {
            let offset = out.len() as u64;
            out.extend_from_slice(bytes);
            regions.push((kind, name.to_string(), offset, bytes.len() as u64, crc32fast::hash(bytes)));
        };
        let mut header = Vec::new();
        header.extend_from_slice(&MAGIC);
        header.extend_from_slice(&FORMAT_VERSION.to_le_bytes());
        put_str16(&mut header, tantivy::version_string())?;
        push(&mut out, KIND_HEADER, "header", &header);
        for (ext, bytes) in &files {
            push(&mut out, KIND_TANTIVY_FILE, ext, bytes);
        }
        push(&mut out, KIND_ROWIDS, "rowids", &rowid_region);
        if let Some(s) = &snapshot_region {
            push(&mut out, KIND_SNAPSHOTS, "snapshots", s);
        }
        push(&mut out, KIND_TOKEN_COUNTS, "token_counts", &count_region);

        let footer_offset = out.len() as u64;
        let mut footer = Vec::new();
        footer.extend_from_slice(&FOOTER_MAGIC);
        footer.extend_from_slice(&FORMAT_VERSION.to_le_bytes());
        put_str16(&mut footer, &source.index_id)?;
        put_str16(&mut footer, &source.path_key)?;
        put_str32(&mut footer, &source.path)?;
        footer.extend_from_slice(&source.file_size.to_le_bytes());
        footer.extend_from_slice(&source.footer_size.to_le_bytes());
        footer.extend_from_slice(&(n as u32).to_le_bytes());
        let mut flags = 0u8;
        if explicit {
            flags |= FLAG_EXPLICIT_ROWIDS;
        }
        if snapshot_region.is_some() {
            flags |= FLAG_SNAPSHOT_COLUMN;
        }
        footer.push(flags);
        footer.extend_from_slice(&(regions.len() as u32).to_le_bytes());
        for (kind, name, offset, len, crc) in &regions {
            footer.push(*kind);
            put_str16(&mut footer, name)?;
            footer.extend_from_slice(&offset.to_le_bytes());
            footer.extend_from_slice(&len.to_le_bytes());
            footer.extend_from_slice(&crc.to_le_bytes());
        }
        out.extend_from_slice(&footer);
        let total = (out.len() + TRAILER_LEN) as u64;
        out.extend_from_slice(&footer_offset.to_le_bytes());
        out.extend_from_slice(&crc32fast::hash(&footer).to_le_bytes());
        out.extend_from_slice(&total.to_le_bytes());
        out.extend_from_slice(&END_MAGIC);
        Ok((out, n as u64))
    }
}

/// Builds a bundle from `docs`, which must come in position order 0, 1, 2, ...
pub fn build(source: &Source, docs: impl IntoIterator<Item = Doc>) -> Result<Vec<u8>> {
    let mut builder = Builder::new(source)?;
    for d in docs {
        builder.add(d.position, d.rowid, d.snapshot, &d.text)?;
    }
    builder.finish().map(|(bytes, _)| bytes)
}

/// The finalized single segment's files by extension, after the build checks: one segment of `n`
/// docs, doc d's position fast field equal to d (Tantivy's doc id is the position), and Tantivy's own
/// token total equal to the sum of the exact counts.
fn segment_files(
    index: &Index,
    n: usize,
    text: Field,
    counts: &[u32],
) -> Result<BTreeMap<String, Vec<u8>>> {
    let metas = index.load_metas().map_err(|e| Error::build(&format!("cannot read the segment meta: {e}")))?;
    if metas.segments.len() != 1 || metas.segments[0].max_doc() as usize != n {
        return Err(Error::build("the writer did not produce one segment holding every doc"));
    }
    let reader = index
        .reader_builder()
        .reload_policy(tantivy::ReloadPolicy::Manual)
        .try_into()
        .map_err(|e: tantivy::TantivyError| Error::build(&format!("cannot open the segment: {e}")))?;
    let searcher = reader.searcher();
    let segment = searcher
        .segment_readers()
        .first()
        .ok_or_else(|| Error::build("no segment reader"))?;
    let column = segment
        .fast_fields()
        .u64(POSITION_FIELD)
        .map_err(|e| Error::build(&format!("no position column: {e}")))?;
    for d in 0..n as u32 {
        if column.first(d) != Some(d as u64) {
            return Err(Error::build(&format!("doc {d} does not hold position {d}")));
        }
    }
    let tantivy_tokens = segment
        .inverted_index(text)
        .map_err(|e| Error::build(&format!("no inverted index: {e}")))?
        .total_num_tokens();
    let exact: u64 = counts.iter().map(|c| *c as u64).sum();
    if tantivy_tokens != exact {
        return Err(Error::build(&format!(
            "exact token counts ({exact}) differ from Tantivy's total ({tantivy_tokens})"
        )));
    }
    let mut files = BTreeMap::new();
    let dir = index.directory();
    for path in metas.list_segment_files() {
        let exists = dir.exists(&path).map_err(|e| Error::build(&format!("cannot list segment files: {e}")))?;
        if !exists {
            continue;
        }
        let name = path.to_string_lossy().to_string();
        let ext = match name.split_once('.') {
            Some((_, ext)) => ext.to_string(),
            None => return Err(Error::build(&format!("unexpected segment file {name}"))),
        };
        if !TANTIVY_EXTENSIONS.contains(&ext.as_str()) {
            return Err(Error::build(&format!("unexpected segment file extension {ext}")));
        }
        // atomic_read returns the bytes as stored, Tantivy's footer included (spec 5.2).
        let bytes = dir.atomic_read(&path).map_err(|e| Error::build(&format!("cannot read {name}: {e}")))?;
        files.insert(ext, bytes);
    }
    Ok(files)
}

fn encode_rowids(rowids: &[i64]) -> Vec<u8> {
    let start = rowids.first().copied().unwrap_or(0);
    let affine = rowids
        .iter()
        .enumerate()
        .all(|(p, r)| i64::try_from(p).ok().and_then(|p| start.checked_add(p)) == Some(*r));
    if affine {
        let mut out = vec![0u8];
        out.extend_from_slice(&start.to_le_bytes());
        out
    } else {
        let mut out = Vec::with_capacity(1 + 8 * rowids.len());
        out.push(1u8);
        for r in rowids {
            out.extend_from_slice(&r.to_le_bytes());
        }
        out
    }
}

fn put_str16(out: &mut Vec<u8>, s: &str) -> Result<()> {
    let len = u16::try_from(s.len()).map_err(|_| Error::build("a footer string is too long"))?;
    out.extend_from_slice(&len.to_le_bytes());
    out.extend_from_slice(s.as_bytes());
    Ok(())
}

fn put_str32(out: &mut Vec<u8>, s: &str) -> Result<()> {
    let len = u32::try_from(s.len()).map_err(|_| Error::build("a footer string is too long"))?;
    out.extend_from_slice(&len.to_le_bytes());
    out.extend_from_slice(s.as_bytes());
    Ok(())
}

/// How a segment maps docs to rowids (spec 6.2's rowid column).
#[derive(Clone, Debug)]
enum Rowids {
    Affine(i64),
    Explicit(Vec<i64>),
}

/// An opened bundle. Owns a copy of the bundle's bytes; the Tantivy files are slices of it.
pub struct Segment {
    bytes: OwnedBytes,
    files: Vec<(String, Range<usize>)>,
    num_docs: u32,
    rowids: Rowids,
    explicit: bool,
    snapshots: Option<Vec<i64>>,
    counts: Vec<u32>,
    source: Source,
}

impl Segment {
    pub fn num_docs(&self) -> u32 {
        self.num_docs
    }
    /// 1 when the rowid column is explicit (the file's `_ducklake_internal_row_id`): the bundle
    /// records it explicitly when its rowids are not `row_id_start + position`.
    pub fn has_explicit_rowids(&self) -> bool {
        self.explicit
    }
    pub fn has_snapshot_column(&self) -> bool {
        self.snapshots.is_some()
    }
    /// The rowid of `doc`, None past the last doc.
    pub fn rowid(&self, doc: u32) -> Option<i64> {
        if doc >= self.num_docs {
            return None;
        }
        match &self.rowids {
            Rowids::Affine(start) => start.checked_add(doc as i64),
            Rowids::Explicit(v) => v.get(doc as usize).copied(),
        }
    }
    /// The snapshot id of `doc`, -1 when the bundle has no snapshot column or past the last doc.
    pub fn snapshot(&self, doc: u32) -> i64 {
        match &self.snapshots {
            Some(v) => v.get(doc as usize).copied().unwrap_or(-1),
            None => -1,
        }
    }
    pub fn source(&self) -> &Source {
        &self.source
    }
    /// Each doc's exact token count.
    pub fn token_counts(&self) -> &[u32] {
        &self.counts
    }
    /// The Tantivy files, by extension, as slices of the bundle (footer included).
    pub(crate) fn tantivy_files(&self) -> impl Iterator<Item = (&str, OwnedBytes)> + '_ {
        self.files.iter().map(move |(ext, r)| (ext.as_str(), self.bytes.slice(r.clone())))
    }
}

struct Cursor<'a> {
    bytes: &'a [u8],
    at: usize,
}

impl<'a> Cursor<'a> {
    fn take(&mut self, n: usize) -> Result<&'a [u8]> {
        let end = self.at.checked_add(n).ok_or_else(|| Error::corrupt("footer field out of bounds"))?;
        let s = self.bytes.get(self.at..end).ok_or_else(|| Error::corrupt("footer field out of bounds"))?;
        self.at = end;
        Ok(s)
    }
    fn u8(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }
    fn u16(&mut self) -> Result<u16> {
        let b = self.take(2)?;
        Ok(u16::from_le_bytes([b[0], b[1]]))
    }
    fn u32(&mut self) -> Result<u32> {
        let b = self.take(4)?;
        Ok(u32::from_le_bytes([b[0], b[1], b[2], b[3]]))
    }
    fn u64(&mut self) -> Result<u64> {
        let b = self.take(8)?;
        let mut a = [0u8; 8];
        a.copy_from_slice(b);
        Ok(u64::from_le_bytes(a))
    }
    fn str16(&mut self) -> Result<String> {
        let n = self.u16()? as usize;
        let b = self.take(n)?;
        String::from_utf8(b.to_vec()).map_err(|_| Error::corrupt("footer string is not UTF-8"))
    }
    fn str32(&mut self) -> Result<String> {
        let n = self.u32()? as usize;
        let b = self.take(n)?;
        String::from_utf8(b.to_vec()).map_err(|_| Error::corrupt("footer string is not UTF-8"))
    }
    fn done(&self) -> bool {
        self.at == self.bytes.len()
    }
}

fn u64_at(bytes: &[u8], at: usize) -> u64 {
    let mut a = [0u8; 8];
    a.copy_from_slice(&bytes[at..at + 8]);
    u64::from_le_bytes(a)
}

/// Opens a bundle, checking its structure and checksums (`bundle_corrupt`), then its format and
/// Tantivy version and its source fields against `expected` (`bundle_mismatch`). Copies `bytes`.
pub fn open(bytes: &[u8], expected: &Source) -> Result<Segment> {
    expected.validate()?;
    let len = bytes.len();
    if len < TRAILER_LEN || bytes[len - 8..] != END_MAGIC {
        return Err(Error::corrupt("no bundle trailer"));
    }
    let t = len - TRAILER_LEN;
    let footer_offset = u64_at(bytes, t);
    let footer_crc = u32::from_le_bytes([bytes[t + 8], bytes[t + 9], bytes[t + 10], bytes[t + 11]]);
    let total = u64_at(bytes, t + 12);
    if total != len as u64 {
        return Err(Error::corrupt("length differs from the recorded total (truncated or extended)"));
    }
    let footer_offset =
        usize::try_from(footer_offset).ok().filter(|o| *o <= t).ok_or_else(|| Error::corrupt("footer offset out of bounds"))?;
    let footer = &bytes[footer_offset..t];
    if crc32fast::hash(footer) != footer_crc {
        return Err(Error::corrupt("footer checksum mismatch"));
    }
    let mut c = Cursor { bytes: footer, at: 0 };
    if c.take(8)? != FOOTER_MAGIC {
        return Err(Error::corrupt("no footer magic"));
    }
    let footer_version = c.u32()?;
    let index_id = c.str16()?;
    let path_key_field = c.str16()?;
    let path = c.str32()?;
    let file_size = c.u64()?;
    let footer_size = c.u64()?;
    let num_docs = c.u32()?;
    let flags = c.u8()?;
    let n_regions = c.u32()? as usize;
    let mut regions = Vec::new();
    let mut next = 0usize;
    for _ in 0..n_regions {
        let kind = c.u8()?;
        let name = c.str16()?;
        let offset = usize::try_from(c.u64()?).map_err(|_| Error::corrupt("region offset out of bounds"))?;
        let rlen = usize::try_from(c.u64()?).map_err(|_| Error::corrupt("region length out of bounds"))?;
        let crc = c.u32()?;
        let end = offset.checked_add(rlen).ok_or_else(|| Error::corrupt("region out of bounds"))?;
        if offset != next || end > footer_offset {
            return Err(Error::corrupt("regions are not contiguous"));
        }
        if crc32fast::hash(&bytes[offset..end]) != crc {
            return Err(Error::corrupt(&format!("checksum mismatch in region {name}")));
        }
        next = end;
        regions.push((kind, name, offset..end));
    }
    if !c.done() {
        return Err(Error::corrupt("trailing bytes in the footer"));
    }
    if next != footer_offset {
        return Err(Error::corrupt("regions do not reach the footer"));
    }
    // Header.
    let (kind0, _, header_range) = regions.first().ok_or_else(|| Error::corrupt("no regions"))?;
    if *kind0 != KIND_HEADER {
        return Err(Error::corrupt("first region is not the header"));
    }
    let mut h = Cursor { bytes: &bytes[header_range.clone()], at: 0 };
    if h.take(8)? != MAGIC {
        return Err(Error::corrupt("no header magic"));
    }
    let header_version = h.u32()?;
    let tantivy_version = h.str16()?;
    if !h.done() {
        return Err(Error::corrupt("trailing bytes in the header"));
    }
    if header_version != FORMAT_VERSION || footer_version != FORMAT_VERSION {
        return Err(Error::mismatch(&format!(
            "format version {header_version} (footer {footer_version}), expected {FORMAT_VERSION}"
        )));
    }
    if tantivy_version != tantivy::version_string() {
        return Err(Error::mismatch(&format!(
            "built by {tantivy_version:?}, this library is {:?}",
            tantivy::version_string()
        )));
    }
    // Source fields (spec 6.2: any difference means missing).
    if path_key_field != path_key(&path) {
        return Err(Error::mismatch("the recorded path key is not the SHA-256 of the recorded path"));
    }
    for (what, got, want) in [
        ("index_id", &index_id, &expected.index_id),
        ("path_key", &path_key_field, &expected.path_key),
        ("path", &path, &expected.path),
    ] {
        if got != want {
            return Err(Error::mismatch(&format!("{what} differs")));
        }
    }
    if file_size != expected.file_size {
        return Err(Error::mismatch("file_size differs"));
    }
    if footer_size != expected.footer_size {
        return Err(Error::mismatch("footer_size differs"));
    }
    if flags & !(FLAG_EXPLICIT_ROWIDS | FLAG_SNAPSHOT_COLUMN) != 0 {
        return Err(Error::corrupt("unknown flags"));
    }
    if num_docs == 0 {
        return Err(Error::corrupt("no docs"));
    }
    let n = num_docs as usize;
    // Columns and files.
    let mut files = Vec::new();
    let mut rowids = None;
    let mut snapshots = None;
    let mut counts = None;
    for (kind, name, range) in regions.iter().skip(1) {
        let data = &bytes[range.clone()];
        match *kind {
            KIND_TANTIVY_FILE => {
                if !TANTIVY_EXTENSIONS.contains(&name.as_str()) || files.iter().any(|(e, _)| e == name) {
                    return Err(Error::corrupt(&format!("unexpected Tantivy file {name}")));
                }
                files.push((name.clone(), range.clone()));
            }
            KIND_ROWIDS if rowids.is_none() => {
                rowids = Some(match data.first() {
                    Some(0) if data.len() == 9 => Rowids::Affine(u64_at(data, 1) as i64),
                    Some(1) if data.len() == 1 + 8 * n => {
                        Rowids::Explicit((0..n).map(|d| u64_at(data, 1 + 8 * d) as i64).collect())
                    }
                    _ => return Err(Error::corrupt("rowid column does not match the doc count")),
                });
            }
            KIND_SNAPSHOTS if snapshots.is_none() => {
                if data.len() != 8 * n {
                    return Err(Error::corrupt("snapshot column does not match the doc count"));
                }
                let values: Vec<i64> = (0..n).map(|d| u64_at(data, 8 * d) as i64).collect();
                if values.iter().any(|v| *v < 0) {
                    return Err(Error::corrupt("a snapshot id below 0"));
                }
                snapshots = Some(values);
            }
            KIND_TOKEN_COUNTS if counts.is_none() => {
                if data.len() != 4 * n {
                    return Err(Error::corrupt("token count column does not match the doc count"));
                }
                counts = Some(
                    data.chunks_exact(4).map(|b| u32::from_le_bytes([b[0], b[1], b[2], b[3]])).collect::<Vec<u32>>(),
                );
            }
            _ => return Err(Error::corrupt(&format!("unexpected region {name}"))),
        }
    }
    let rowids = rowids.ok_or_else(|| Error::corrupt("no rowid column"))?;
    let counts = counts.ok_or_else(|| Error::corrupt("no token count column"))?;
    let explicit = matches!(rowids, Rowids::Explicit(_));
    if explicit != (flags & FLAG_EXPLICIT_ROWIDS != 0) {
        return Err(Error::corrupt("rowid flag does not match the rowid column"));
    }
    if snapshots.is_some() != (flags & FLAG_SNAPSHOT_COLUMN != 0) {
        return Err(Error::corrupt("snapshot flag does not match the snapshot column"));
    }
    if let Rowids::Affine(start) = rowids {
        if start.checked_add(n as i64 - 1).is_none() {
            return Err(Error::corrupt("affine rowids overflow"));
        }
    }
    let affine_start = match rowids {
        Rowids::Affine(start) => Some(start),
        Rowids::Explicit(_) => None,
    };
    expected.check_contents(n as u64, snapshots.is_some(), affine_start, Error::mismatch)?;
    for ext in ["fieldnorm", "idx", "term", "fast"] {
        if !files.iter().any(|(e, _)| e == ext) {
            return Err(Error::corrupt(&format!("no Tantivy {ext} file")));
        }
    }
    let segment = Segment {
        bytes: OwnedBytes::new(bytes.to_vec()),
        files,
        num_docs,
        rowids,
        explicit,
        snapshots,
        counts,
        source: Source {
            index_id,
            path_key: path_key_field,
            path,
            file_size,
            footer_size,
            record_count: expected.record_count,
            row_id_start: expected.row_id_start,
            has_internal_row_id: expected.has_internal_row_id,
            has_internal_snapshot: expected.has_internal_snapshot,
        },
    };
    crate::directory::check_opens(&segment)?;
    Ok(segment)
}
