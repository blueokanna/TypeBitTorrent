//! Torrent creation (BEP-3 v1) from local files.
//!
//! Streams bytes piece-by-piece through the engine's incremental SHA-1 (no giant piece buffer),
//! handles pieces straddling file boundaries, and emits a canonical bencoded `.torrent`
//! (the `info` dict is BTreeMap-backed, so keys are always byte-sorted — the infohash is
//! therefore stable across re-creations of the same content).
//!
//! Runs on the caller's thread so the engine worker is never blocked by disk hashing, and
//! publishes progress + a cooperative cancel flag through [`BuildProgress`] so the UI can
//! show a real progress bar and abort a slow hash of hundreds of gigabytes.
//!
//! BEP-3 v1 only (no hybrid/v2): `announce-list` (BEP-12), `private` (BEP-27) and the
//! conventional `source` tag are emitted when the caller supplies them.

use std::fs::File;
use std::io::Read;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};

use typebit::bencode::{bytes, dict, int, list, BVal};
use typebit::crypto::Sha1;

/// Supported piece lengths (powers of two, 16 KiB .. 256 MiB); 128/256 MiB are first-class options.
pub const PIECE_LENGTHS: &[u32] = &[
    16 * 1024,
    32 * 1024,
    64 * 1024,
    128 * 1024,
    256 * 1024,
    512 * 1024,
    1024 * 1024,
    2 * 1024 * 1024,
    4 * 1024 * 1024,
    8 * 1024 * 1024,
    16 * 1024 * 1024,
    32 * 1024 * 1024,
    64 * 1024 * 1024,
    128 * 1024 * 1024,
    256 * 1024 * 1024,
];

/// Whether `n` is a supported piece length.
pub fn is_supported_piece_length(n: u32) -> bool {
    PIECE_LENGTHS.contains(&n)
}

/// One input file.
#[derive(Debug, Clone)]
pub struct FileSpec {
    /// Absolute path on disk.
    pub abs_path: PathBuf,
    /// Relative path components stored in the torrent (dirs then name).
    pub rel_path: Vec<String>,
}

/// Everything needed to build one `.torrent`. Built by the JNI layer from the
/// UI's options JSON.
#[derive(Debug, Clone, Default)]
pub struct TorrentBuild {
    pub files: Vec<FileSpec>,
    pub piece_length: u32,
    /// Torrent name: the file name for a single-file torrent, the directory
    /// name otherwise.
    pub name: String,
    pub comment: Option<String>,
    pub created_by: Option<String>,
    /// Cross-seed / tracker tag written into `info` (conventional field).
    pub source: Option<String>,
    /// BEP-27: `private: 1` — announces only to the listed trackers.
    pub is_private: bool,
    /// BEP-12 announce tiers; tier 0 is mirrored into the legacy `announce`
    /// key so old clients still find a tracker. Empty tiers are dropped.
    pub announce_list: Vec<Vec<String>>,
}

/// Live progress + cooperative cancellation for one build.
///
/// Atomics (not a mutex) because the hashing loop touches this on every
/// 1 MiB chunk and the UI reads it from the JNI thread. `const`-constructible
/// so the bridge can keep a process-wide instance in a `static`.
#[derive(Default)]
pub struct BuildProgress {
    done: AtomicU64,
    total: AtomicU64,
    running: AtomicBool,
    cancel: AtomicBool,
}

impl BuildProgress {
    pub const fn new() -> Self {
        BuildProgress {
            done: AtomicU64::new(0),
            total: AtomicU64::new(0),
            running: AtomicBool::new(false),
            cancel: AtomicBool::new(false),
        }
    }

    /// Marks a build as started; `total` is the payload size in bytes.
    pub fn begin(&self, total: u64) {
        self.done.store(0, Ordering::Relaxed);
        self.total.store(total, Ordering::Relaxed);
        self.cancel.store(false, Ordering::Relaxed);
        self.running.store(true, Ordering::Relaxed);
    }

    pub fn finish(&self) {
        self.running.store(false, Ordering::Relaxed);
    }

    /// Asks the running build to stop; it returns an error at the next chunk.
    pub fn request_cancel(&self) {
        self.cancel.store(true, Ordering::Relaxed);
    }

    /// `(hashed_bytes, total_bytes, running, cancel_requested)`.
    pub fn snapshot(&self) -> (u64, u64, bool, bool) {
        (
            self.done.load(Ordering::Relaxed),
            self.total.load(Ordering::Relaxed),
            self.running.load(Ordering::Relaxed),
            self.cancel.load(Ordering::Relaxed),
        )
    }

    fn add(&self, n: u64) {
        self.done.fetch_add(n, Ordering::Relaxed);
    }

    fn cancelled(&self) -> bool {
        self.cancel.load(Ordering::Relaxed)
    }
}

/// Stream-read a file into `cb` in bounded chunks (1 MiB), returning the
/// byte count. Cancellation is checked once per chunk, so aborting a large
/// build is immediate and never leaves a partially-written output behind
/// (the bytes only exist in memory until the caller saves them).
fn read_file_chunks(
    path: &PathBuf,
    progress: &BuildProgress,
    mut cb: impl FnMut(&[u8]),
) -> Result<u64, String> {
    let mut file = File::open(path).map_err(|e| format!("{}: {e}", path.display()))?;
    let mut chunk = vec![0u8; 1024 * 1024];
    let mut total = 0u64;
    loop {
        if progress.cancelled() {
            return Err("cancelled".into());
        }
        let n = file
            .read(&mut chunk)
            .map_err(|e| format!("{}: {e}", path.display()))?;
        if n == 0 {
            break;
        }
        cb(&chunk[..n]);
        total += n as u64;
    }
    Ok(total)
}

/// Create a v1 `.torrent` from [TorrentBuild]; returns the bencoded bytes.
///
/// Pieces are hashed as one logical byte stream across all files (v1 pieces
/// may straddle file boundaries); memory stays bounded (1 MiB read chunk +
/// SHA-1 state + one 20-byte hash per piece).
pub fn create_torrent(build: &TorrentBuild, progress: &BuildProgress) -> Result<Vec<u8>, String> {
    let piece_length = build.piece_length;
    if !is_supported_piece_length(piece_length) {
        return Err(format!("unsupported piece length {piece_length}"));
    }
    if build.files.is_empty() {
        return Err("no input files".into());
    }
    let name = build.name.trim();
    if name.is_empty() {
        return Err("empty torrent name".into());
    }

    // Deterministic order (byte-sorted relative paths): the same content
    // always yields the same infohash, whatever order the picker returned
    // the files in.
    let mut files: Vec<&FileSpec> = build.files.iter().collect();
    files.sort_by(|a, b| a.rel_path.cmp(&b.rel_path));

    // Validate every file and accumulate the total payload size.
    let mut sizes: Vec<u64> = Vec::with_capacity(files.len());
    let mut total = 0u64;
    for f in &files {
        if f.rel_path.is_empty() {
            return Err("empty relative path".into());
        }
        if f.rel_path
            .iter()
            .any(|c| c.is_empty() || c == "." || c == ".." || c.contains('\0'))
        {
            return Err(format!(
                "invalid path component in {}",
                f.abs_path.display()
            ));
        }
        let md =
            std::fs::metadata(&f.abs_path).map_err(|e| format!("{}: {e}", f.abs_path.display()))?;
        if !md.is_file() {
            return Err(format!("not a file: {}", f.abs_path.display()));
        }
        total = total.checked_add(md.len()).ok_or("total size overflow")?;
        sizes.push(md.len());
    }
    if total == 0 {
        return Err("total size is zero".into());
    }
    // A multi-file torrent needs distinct paths, otherwise the same file is
    // listed twice and clients write it twice.
    if files.len() > 1 {
        let mut seen = std::collections::HashSet::with_capacity(files.len());
        for f in &files {
            if !seen.insert(f.rel_path.as_slice()) {
                return Err(format!(
                    "duplicate path in file list: {}",
                    f.rel_path.join("/")
                ));
            }
        }
    }

    progress.begin(total);

    // Stream-hash the logical byte stream, flushing one 20-byte hash per
    // completed piece.
    let mut pieces: Vec<u8> = Vec::new();
    let mut hasher = Sha1::new();
    let mut in_piece = 0u32;
    for (i, f) in files.iter().enumerate() {
        // Read errors and cancellation propagate: a silently short torrent
        // would be worse than a visible failure.
        let n = read_file_chunks(&f.abs_path, progress, |data| {
            let mut off = 0usize;
            while off < data.len() {
                let take = core::cmp::min(
                    data.len() - off,
                    (piece_length as usize).saturating_sub(in_piece as usize),
                );
                hasher.update(&data[off..off + take]);
                off += take;
                in_piece += take as u32;
                if in_piece >= piece_length {
                    // `Sha1: Default` lets mem::take swap in a fresh hasher
                    // without moving the captured variable.
                    pieces.extend_from_slice(&core::mem::take(&mut hasher).finalize());
                    in_piece = 0;
                }
            }
            progress.add(data.len() as u64);
        })?;
        // The file changed under us (a writer is still appending to it):
        // refuse rather than emit a torrent whose piece table does not match
        // the bytes on disk — such a torrent can never complete.
        if n != sizes[i] {
            progress.finish();
            return Err(format!(
                "file changed while hashing ({n} != {}): {}",
                sizes[i],
                f.abs_path.display()
            ));
        }
    }
    if in_piece > 0 {
        pieces.extend_from_slice(&hasher.finalize());
    }
    progress.finish();

    // Build the `info` dictionary.
    let mut info: Vec<(&[u8], BVal)> = vec![
        (b"name", bytes(name.as_bytes().to_vec())),
        (b"piece length", int(piece_length as i64)),
        (b"pieces", bytes(pieces)),
    ];
    let single = files.len() == 1 && files[0].rel_path.len() == 1;
    if single {
        info.push((b"length", int(sizes[0] as i64)));
    } else {
        let mut entries = Vec::with_capacity(files.len());
        for (i, f) in files.iter().enumerate() {
            let path: Vec<BVal> = f
                .rel_path
                .iter()
                .map(|c| bytes(c.as_bytes().to_vec()))
                .collect();
            entries.push(dict(vec![
                (b"length", int(sizes[i] as i64)),
                (b"path", list(path)),
            ]));
        }
        info.push((b"files", list(entries)));
    }
    if let Some(source) = build
        .source
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
    {
        info.push((b"source", bytes(source.as_bytes().to_vec())));
    }
    if build.is_private {
        info.push((b"private", int(1)));
    }

    // Announce tiers (BEP-12). Blank URLs are dropped; the first URL of the
    // first non-empty tier is mirrored into the legacy `announce` key.
    let tiers: Vec<Vec<&str>> = build
        .announce_list
        .iter()
        .map(|tier| {
            tier.iter()
                .map(|s| s.trim())
                .filter(|s| {
                    s.starts_with("http://")
                        || s.starts_with("https://")
                        || s.starts_with("udp://")
                        || s.starts_with("ws://")
                        || s.starts_with("wss://")
                })
                .collect()
        })
        .filter(|tier: &Vec<&str>| !tier.is_empty())
        .collect();

    let mut root: Vec<(&[u8], BVal)> = vec![(b"info", dict(info))];
    if let Some(first) = tiers.first().and_then(|t| t.first()) {
        root.push((b"announce", bytes(first.as_bytes().to_vec())));
    }
    if !tiers.is_empty() {
        let encoded: Vec<BVal> = tiers
            .iter()
            .map(|tier| list(tier.iter().map(|u| bytes(u.as_bytes().to_vec())).collect()))
            .collect();
        root.push((b"announce-list", list(encoded)));
    }
    if let Some(c) = build
        .comment
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
    {
        root.push((b"comment", bytes(c.as_bytes().to_vec())));
    }
    root.push((
        b"created by",
        bytes(
            build
                .created_by
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .unwrap_or("TypeBitTorrent")
                .as_bytes()
                .to_vec(),
        ),
    ));
    root.push((
        b"creation date",
        int(std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0)),
    ));
    Ok(typebit::bencode::encode_to_vec(&dict(root)))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn supported_piece_lengths_include_large() {
        assert!(is_supported_piece_length(128 * 1024 * 1024));
        assert!(is_supported_piece_length(256 * 1024 * 1024));
        assert!(!is_supported_piece_length(100 * 1024 * 1024));
        assert!(!is_supported_piece_length(0));
    }

    /// Scratch directory unique to one test (tests run in parallel).
    fn scratch(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("typebit_mk_{tag}_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn create_torrent_roundtrips_through_engine_parser() {
        let dir = scratch("multi");
        let f1 = dir.join("a.bin");
        let f2 = dir.join("b.bin");
        // 40 KiB + 30 KiB: forces a piece that straddles the file boundary
        // at 32 KiB piece length.
        std::fs::write(&f1, vec![0xABu8; 40 * 1024]).unwrap();
        std::fs::write(&f2, vec![0xCDu8; 30 * 1024]).unwrap();
        let build = TorrentBuild {
            files: vec![
                FileSpec {
                    abs_path: f1,
                    rel_path: vec!["a.bin".into()],
                },
                FileSpec {
                    abs_path: f2,
                    rel_path: vec!["b.bin".into()],
                },
            ],
            piece_length: 32 * 1024,
            name: "multi".into(),
            announce_list: vec![vec!["http://t/announce".into()]],
            ..Default::default()
        };
        let progress = BuildProgress::default();
        let bytes = create_torrent(&build, &progress).expect("create");
        // The engine's own parser must accept what we produced.
        let t = typebit::metainfo::Torrent::from_bytes(&bytes).expect("parse");
        assert_eq!(t.name, "multi");
        assert_eq!(t.piece_length, 32 * 1024);
        assert_eq!(t.total_size, 70 * 1024);
        assert_eq!(t.files.len(), 2);
        assert_eq!(t.piece_count(), 3); // 70 KiB / 32 KiB → 3 pieces
                                        // Piece 1 spans the two files: hashing must match a concatenation.
        let mut joined = vec![0xABu8; 40 * 1024];
        joined.extend_from_slice(&vec![0xCDu8; 30 * 1024]);
        let expect = Sha1::digest(&joined[32 * 1024..64 * 1024]);
        assert_eq!(t.piece_hash(1).unwrap(), &expect[..]);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn announce_tiers_private_source_and_comment_are_encoded() {
        let dir = scratch("opts");
        let f = dir.join("only.bin");
        std::fs::write(&f, vec![1u8; 4096]).unwrap();
        let build = TorrentBuild {
            files: vec![FileSpec {
                abs_path: f,
                rel_path: vec!["only.bin".into()],
            }],
            piece_length: 16 * 1024,
            name: "opts".into(),
            comment: Some("  hello  ".into()),
            source: Some("TypeBit".into()),
            is_private: true,
            announce_list: vec![
                vec!["udp://a/announce".into(), "".into()],
                vec![],
                vec!["https://b/announce".into()],
            ],
            ..Default::default()
        };
        let progress = BuildProgress::default();
        let raw = create_torrent(&build, &progress).expect("create");
        let root = BVal::parse(&raw).expect("decode");
        // Legacy announce mirrors tier 0 / first URL.
        assert_eq!(
            root.get(b"announce").and_then(BVal::as_str),
            Some("udp://a/announce")
        );
        // Tiers survive, blank URLs and empty tiers are dropped.
        let tiers = root.get(b"announce-list").and_then(BVal::as_list).unwrap();
        assert_eq!(tiers.len(), 2);
        assert_eq!(tiers[1].as_list().unwrap().len(), 1);
        assert_eq!(root.get(b"comment").and_then(BVal::as_str), Some("hello"));
        // `private`/`source` live in the info dict (BEP-27 / cross-seed tag).
        let info = root.get(b"info").unwrap();
        assert_eq!(info.dict_get_int(b"private"), Some(1));
        assert_eq!(info.get(b"source").and_then(BVal::as_str), Some("TypeBit"));
        // Single file → `length`, never `files`.
        assert_eq!(info.dict_get_int(b"length"), Some(4096));
        assert!(info.get(b"files").is_none());
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn file_order_does_not_change_the_infohash() {
        let dir = scratch("order");
        let a = dir.join("a.bin");
        let b = dir.join("b.bin");
        std::fs::write(&a, vec![7u8; 1024]).unwrap();
        std::fs::write(&b, vec![9u8; 2048]).unwrap();
        let spec = |abs: &PathBuf, rel: &str| FileSpec {
            abs_path: abs.clone(),
            rel_path: vec!["pkg".into(), rel.into()],
        };
        let make = |order: bool| {
            let files = if order {
                vec![spec(&a, "a.bin"), spec(&b, "b.bin")]
            } else {
                vec![spec(&b, "b.bin"), spec(&a, "a.bin")]
            };
            let build = TorrentBuild {
                files,
                piece_length: 16 * 1024,
                name: "pkg".into(),
                // `creation date` is wall-clock, so compare info hashes only.
                ..Default::default()
            };
            let progress = BuildProgress::default();
            let raw = create_torrent(&build, &progress).unwrap();
            typebit::metainfo::Torrent::from_bytes(&raw)
                .unwrap()
                .info_hash
        };
        assert_eq!(make(true), make(false));
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn duplicate_paths_and_empty_sets_are_rejected() {
        let dir = scratch("bad");
        let f = dir.join("x.bin");
        std::fs::write(&f, vec![3u8; 16]).unwrap();
        let spec = FileSpec {
            abs_path: f.clone(),
            rel_path: vec!["same.bin".into()],
        };
        let progress = BuildProgress::default();
        let dup = TorrentBuild {
            files: vec![spec.clone(), spec.clone()],
            piece_length: 16 * 1024,
            name: "dup".into(),
            ..Default::default()
        };
        assert!(create_torrent(&dup, &progress).is_err());
        let empty = TorrentBuild {
            files: Vec::new(),
            piece_length: 16 * 1024,
            name: "empty".into(),
            ..Default::default()
        };
        assert!(create_torrent(&empty, &progress).is_err());
        let bad_piece = TorrentBuild {
            files: vec![spec],
            piece_length: 100 * 1024,
            name: "bad".into(),
            ..Default::default()
        };
        assert!(create_torrent(&bad_piece, &progress).is_err());
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn progress_counts_and_cancellation_work() {
        let dir = scratch("cancel");
        let f = dir.join("big.bin");
        std::fs::write(&f, vec![5u8; 512 * 1024]).unwrap();
        let build = TorrentBuild {
            files: vec![FileSpec {
                abs_path: f,
                rel_path: vec!["big.bin".into()],
            }],
            piece_length: 16 * 1024,
            name: "big".into(),
            ..Default::default()
        };
        // A completed build reports the full byte count and not-running.
        let progress = BuildProgress::default();
        create_torrent(&build, &progress).expect("create");
        let (done, total, running, cancelled) = progress.snapshot();
        assert_eq!(
            (done, total, running, cancelled),
            (512 * 1024, 512 * 1024, false, false)
        );

        // The chunk reader honours a cancellation requested mid-build; this
        // is the exact primitive the hashing loop calls per 1 MiB.
        let cancelled_progress = BuildProgress::default();
        cancelled_progress.begin(512 * 1024);
        cancelled_progress.request_cancel();
        assert!(read_file_chunks(&build.files[0].abs_path, &cancelled_progress, |_| {}).is_err());

        // ...and beginning a NEW build clears a stale cancel flag, so a
        // cancellation from a previous attempt never poisons the next one.
        let reused = BuildProgress::default();
        reused.request_cancel();
        reused.begin(512 * 1024);
        assert!(!reused.snapshot().3);
        std::fs::remove_dir_all(&dir).ok();
    }
}
