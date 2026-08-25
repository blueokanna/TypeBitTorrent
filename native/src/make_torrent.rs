//! Torrent creation (BEP-3 v1) from local files.
//!
//! Streams bytes piece-by-piece through the engine's incremental SHA-1 (no giant piece buffer),
//! handles pieces straddling file boundaries, and emits a canonical bencoded `.torrent`.
//! Runs on the caller's thread so the engine worker is never blocked by disk hashing.

use std::fs::File;
use std::io::Read;
use std::path::PathBuf;

use typebit::bencode::{bytes, dict, int, BVal};
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
pub struct FileSpec {
    /// Absolute path on disk.
    pub abs_path: PathBuf,
    /// Relative path components stored in the torrent (dirs then name).
    pub rel_path: Vec<String>,
}

/// Stream-read a file into `cb` in bounded chunks (1 MiB), returning the
/// byte count.
fn read_file_chunks(
    path: &PathBuf,
    mut cb: impl FnMut(&[u8]),
) -> Result<u64, String> {
    let mut file = File::open(path).map_err(|e| format!("{}: {e}", path.display()))?;
    let mut chunk = vec![0u8; 1024 * 1024];
    let mut total = 0u64;
    loop {
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

/// Create a v1 `.torrent` from local files; returns the bencoded bytes.
/// Pieces are hashed as one logical byte stream across all files (v1 pieces may straddle
/// file boundaries); memory stays bounded (1 MiB read chunk + SHA-1 state).
pub fn create_torrent_v1(
    files: &[FileSpec],
    piece_length: u32,
    name: &str,
    announce: Option<&str>,
    comment: Option<&str>,
) -> Result<Vec<u8>, String> {
    if !is_supported_piece_length(piece_length) {
        return Err(format!("unsupported piece length {piece_length}"));
    }
    if files.is_empty() {
        return Err("no input files".into());
    }
    // Validate every file and accumulate the total payload size.
    let mut sizes: Vec<u64> = Vec::with_capacity(files.len());
    let mut total = 0u64;
    for f in files {
        if f.rel_path.is_empty() {
            return Err("empty relative path".into());
        }
        if f.rel_path.iter().any(|c| c.is_empty() || c == "." || c == "..") {
            return Err(format!("invalid path component in {}", f.abs_path.display()));
        }
        let md = std::fs::metadata(&f.abs_path)
            .map_err(|e| format!("{}: {e}", f.abs_path.display()))?;
        if !md.is_file() {
            return Err(format!("not a file: {}", f.abs_path.display()));
        }
        total = total
            .checked_add(md.len())
            .ok_or("total size overflow")?;
        sizes.push(md.len());
    }
    if total == 0 {
        return Err("total size is zero".into());
    }

    // Stream-hash the logical byte stream, flushing one 20-byte hash per
    // completed piece.
    let mut pieces: Vec<u8> = Vec::new();
    let mut hasher = Sha1::new();
    let mut in_piece = 0u32;
    for f in files {
        let _ = read_file_chunks(&f.abs_path, |data| {
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
        })?;
    }
    if in_piece > 0 {
        pieces.extend_from_slice(&hasher.finalize());
    }

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
        let mut list = Vec::with_capacity(files.len());
        for (i, f) in files.iter().enumerate() {
            let path: Vec<BVal> = f
                .rel_path
                .iter()
                .map(|c| bytes(c.as_bytes().to_vec()))
                .collect();
            list.push(dict(vec![
                (b"length", int(sizes[i] as i64)),
                (b"path", typebit::bencode::list(path)),
            ]));
        }
        info.push((b"files", typebit::bencode::list(list)));
    }

    let mut root: Vec<(&[u8], BVal)> = vec![(b"info", dict(info))];
    if let Some(a) = announce {
        if !a.is_empty() {
            root.push((b"announce", bytes(a.as_bytes().to_vec())));
        }
    }
    if let Some(c) = comment {
        if !c.is_empty() {
            root.push((b"comment", bytes(c.as_bytes().to_vec())));
        }
    }
    root.push((b"created by", bytes(b"TypeBitTorrent".to_vec())));
    root.push((
        b"creation date",
        int(
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_secs() as i64)
                .unwrap_or(0),
        ),
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

    #[test]
    fn create_torrent_roundtrips_through_engine_parser() {
        let dir = std::env::temp_dir().join(format!("typebit_mk_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let f1 = dir.join("a.bin");
        let f2 = dir.join("b.bin");
        // 40 KiB + 30 KiB: forces a piece that straddles the file boundary
        // at 32 KiB piece length.
        std::fs::write(&f1, vec![0xABu8; 40 * 1024]).unwrap();
        std::fs::write(&f2, vec![0xCDu8; 30 * 1024]).unwrap();
        let files = vec![
            FileSpec {
                abs_path: f1,
                rel_path: vec!["a.bin".into()],
            },
            FileSpec {
                abs_path: f2,
                rel_path: vec!["b.bin".into()],
            },
        ];
        let bytes = create_torrent_v1(&files, 32 * 1024, "multi", Some("http://t/announce"), None)
            .expect("create");
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
}

