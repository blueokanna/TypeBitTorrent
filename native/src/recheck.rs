//! Disk verification ("重新校验") — hash the bytes a torrent already has on
//! disk against its metainfo piece hashes.
//!
//! The engine crate has no recheck of its own: `add_torrent` starts every
//! session with an **empty** bitfield, and `Engine::load_state` only restores
//! the DHT routing table. Without this module a client that already holds the
//! data (files fetched by another client, restored from a backup, or the very
//! files a torrent was just built from) claims to have nothing: it re-downloads
//! everything and cannot upload a single byte — it is never a seed.
//!
//! The verification runs on the calling thread so it can report progress and
//! be cancelled, and only the resulting bitfield is handed to the engine
//! ([`crate::engine::Cmd::ApplyVerified`]), which keeps the engine thread free.

use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};

use typebit::bitfield::Bitfield;
use typebit::metainfo::Torrent;

/// Live progress of the (single) in-flight verification, polled by the JVM and
/// cancelled through [`RecheckProgress::request_cancel`].
#[derive(Default)]
pub struct RecheckProgress {
    done: AtomicU64,
    total: AtomicU64,
    running: AtomicBool,
    cancelled: AtomicBool,
}

impl RecheckProgress {
    pub const fn new() -> Self {
        Self {
            done: AtomicU64::new(0),
            total: AtomicU64::new(0),
            running: AtomicBool::new(false),
            cancelled: AtomicBool::new(false),
        }
    }

    fn begin(&self, total: u64) {
        self.done.store(0, Ordering::Relaxed);
        self.total.store(total, Ordering::Relaxed);
        self.cancelled.store(false, Ordering::Relaxed);
        self.running.store(true, Ordering::Relaxed);
    }

    /// Marks a pass as finished, including when it failed before starting
    /// (a polling UI must never spin on a dead progress record).
    pub fn finish(&self) {
        self.running.store(false, Ordering::Relaxed);
    }

    fn add(&self, bytes: u64) {
        self.done.fetch_add(bytes, Ordering::Relaxed);
    }

    /// Cancels the running pass (idempotent, safe to call when idle).
    pub fn request_cancel(&self) {
        self.cancelled.store(true, Ordering::Relaxed);
    }

    fn is_cancelled(&self) -> bool {
        self.cancelled.load(Ordering::Relaxed)
    }

    /// `(done_bytes, total_bytes, running, cancelled)`.
    pub fn snapshot(&self) -> (u64, u64, bool, bool) {
        (
            self.done.load(Ordering::Relaxed),
            self.total.load(Ordering::Relaxed),
            self.running.load(Ordering::Relaxed),
            self.cancelled.load(Ordering::Relaxed),
        )
    }
}

/// What one verification pass found.
pub struct RecheckOutcome {
    /// Piece bitfield (network bytes) ready for `TorrentState::have`.
    pub have: Vec<u8>,
    /// Pieces whose bytes on disk hashed correctly.
    pub verified: u32,
    /// Pieces in the torrent.
    pub pieces: u32,
    /// Bytes actually read (the torrent's total size, unless cancelled).
    pub bytes_checked: u64,
}

/// One file's byte range inside the torrent's flat address space.
struct Span {
    path: PathBuf,
    start: u64,
    len: u64,
}

/// Verifies the pieces of `info_raw` against the files under `save_dir`.
///
/// Path layout matches the engine's own (`save_dir` + the file's path
/// components, which include the torrent name), so the same bytes the engine
/// would have written are the bytes that get checked. Missing or short files
/// simply fail their pieces — a partial match is a valid result, which is what
/// makes this usable for a half-downloaded torrent as well.
pub fn verify_files(
    info_raw: &[u8],
    save_dir: &str,
    progress: &RecheckProgress,
) -> Result<RecheckOutcome, String> {
    let torrent = Torrent::from_info(info_raw).map_err(|e| format!("metainfo: {}", e.tag()))?;
    let pieces = torrent.piece_count();
    let piece_len = torrent.piece_length as u64;
    if pieces == 0 || piece_len == 0 {
        return Err("torrent declares no pieces".to_string());
    }

    let root = PathBuf::from(save_dir);
    let mut spans: Vec<Span> = Vec::with_capacity(torrent.files.len());
    let mut offset = 0u64;
    for f in &torrent.files {
        if f.length == 0 {
            continue;
        }
        spans.push(Span {
            path: root.join(f.display_path()),
            start: offset,
            len: f.length,
        });
        offset += f.length;
    }
    if spans.is_empty() {
        return Err("torrent declares no files".to_string());
    }

    progress.begin(torrent.total_size);
    let mut have = Bitfield::new(pieces);
    let mut verified = 0u32;
    let mut bytes_checked = 0u64;
    let mut buf: Vec<u8> = Vec::new();
    let mut open: Option<(usize, File)> = None;

    for index in 0..pieces {
        if progress.is_cancelled() {
            break;
        }
        let start = index as u64 * piece_len;
        if start >= torrent.total_size {
            break;
        }
        let len = piece_len.min(torrent.total_size - start) as usize;
        buf.clear();
        buf.resize(len, 0);
        if read_piece(&spans, &mut open, start, &mut buf) && torrent.verify_piece(index, &buf).is_ok()
        {
            have.set(index);
            verified += 1;
        }
        bytes_checked += len as u64;
        progress.add(len as u64);
    }
    progress.finish();

    Ok(RecheckOutcome {
        have: have.to_bytes(),
        verified,
        pieces,
        bytes_checked,
    })
}

/// Fills `buf` with the bytes at `start..start + buf.len()` of the torrent,
/// crossing file boundaries. `false` when a file is missing or too short.
fn read_piece(
    spans: &[Span],
    open: &mut Option<(usize, File)>,
    start: u64,
    buf: &mut [u8],
) -> bool {
    // Pieces are read in order, so the next span starts at or after this one.
    let mut idx = spans.partition_point(|s| s.start + s.len <= start);
    let mut filled = 0usize;
    while filled < buf.len() {
        let Some(span) = spans.get(idx) else {
            return false;
        };
        let within = start + filled as u64 - span.start;
        let take = span.len.saturating_sub(within).min((buf.len() - filled) as u64) as usize;
        if take == 0 {
            return false;
        }
        if open.as_ref().map(|(i, _)| *i) != Some(idx) {
            match File::open(&span.path) {
                Ok(f) => *open = Some((idx, f)),
                Err(_) => return false,
            }
        }
        let Some((_, file)) = open.as_mut() else {
            return false;
        };
        if file.seek(SeekFrom::Start(within)).is_err() {
            return false;
        }
        if file.read_exact(&mut buf[filled..filled + take]).is_err() {
            return false;
        }
        filled += take;
        idx += 1;
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::make_torrent::{create_torrent, BuildProgress, FileSpec, TorrentBuild};

    fn temp_dir(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("typebit-recheck-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    /// Builds a two-file torrent over pseudo-random bytes and returns
    /// `(info_raw, save_dir)` with the data written where the engine expects it.
    fn fixture(tag: &str, piece_len: u32) -> (Vec<u8>, PathBuf) {
        let root = temp_dir(tag);
        let src = root.join("src");
        std::fs::create_dir_all(&src).unwrap();
        let a = vec![0xA5u8; 300_000];
        let b = vec![0x5Au8; 150_000];
        std::fs::write(src.join("a.bin"), &a).unwrap();
        std::fs::write(src.join("b.bin"), &b).unwrap();
        let build = TorrentBuild {
            files: vec![
                FileSpec {
                    abs_path: src.join("a.bin"),
                    rel_path: vec!["data".into(), "a.bin".into()],
                },
                FileSpec {
                    abs_path: src.join("b.bin"),
                    rel_path: vec!["data".into(), "b.bin".into()],
                },
            ],
            piece_length: piece_len,
            name: "data".into(),
            comment: None,
            created_by: None,
            source: None,
            is_private: false,
            announce_list: vec![],
        };
        let bytes = create_torrent(&build, &BuildProgress::new()).unwrap();
        let torrent = Torrent::from_bytes(&bytes).unwrap();
        // Write the payload the way the engine lays it out: <save>/<name>/…
        let save = root.join("save");
        std::fs::create_dir_all(save.join("data")).unwrap();
        std::fs::write(save.join("data").join("a.bin"), &a).unwrap();
        std::fs::write(save.join("data").join("b.bin"), &b).unwrap();
        (torrent.info_raw, save)
    }

    #[test]
    fn existing_files_are_recognised_as_complete() {
        let (info, save) = fixture("complete", 65_536);
        let progress = RecheckProgress::new();
        let out = verify_files(&info, save.to_str().unwrap(), &progress).unwrap();
        assert_eq!(out.verified, out.pieces, "every piece must verify");
        assert!(out.pieces > 5, "fixture should span several pieces");
        let mut bf = Bitfield::new(out.pieces);
        bf.from_bytes(&out.have, out.pieces).unwrap();
        assert!(bf.all_set(), "bitfield must claim every piece");
        let (done, total, running, cancelled) = progress.snapshot();
        assert_eq!(done, total, "progress must reach 100%");
        assert!(!running && !cancelled);
    }

    #[test]
    fn a_corrupted_piece_is_not_claimed() {
        let (info, save) = fixture("corrupt", 65_536);
        let file = save.join("data").join("a.bin");
        let mut bytes = std::fs::read(&file).unwrap();
        bytes[100_000] ^= 0xFF;
        std::fs::write(&file, &bytes).unwrap();
        let out = verify_files(&info, save.to_str().unwrap(), &RecheckProgress::new()).unwrap();
        assert_eq!(out.verified, out.pieces - 1, "only the damaged piece fails");
    }

    #[test]
    fn missing_data_verifies_nothing() {
        let (info, save) = fixture("missing", 65_536);
        std::fs::remove_dir_all(save.join("data")).unwrap();
        let out = verify_files(&info, save.to_str().unwrap(), &RecheckProgress::new()).unwrap();
        assert_eq!(out.verified, 0);
        assert_eq!(out.pieces, out.pieces);
    }
    #[test]
    fn a_previous_cancellation_does_not_leak_into_the_next_pass() {
        // Cancelling between passes is a no-op by design: a fresh pass resets
        // the flag, otherwise a cancelled verification would poison every later
        // one (and the UI would keep showing "已取消").
        let (info, save) = fixture("cancel", 16_384);
        let progress = RecheckProgress::new();
        progress.request_cancel();
        let out = verify_files(&info, save.to_str().unwrap(), &progress).unwrap();
        assert_eq!(out.verified, out.pieces, "a new pass must run to completion");
        let (_, _, running, cancelled) = progress.snapshot();
        assert!(!running && !cancelled);
    }
}
