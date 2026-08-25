//! Torrent metadata mirror.
//!
//! `typebit 0.1.0` exposes no getter for a session's parsed metainfo, so the
//! bridge keeps its own registry built at add-time from the public
//! `metainfo::Torrent` API. `.torrent` bytes yield the full mirror; magnet
//! links start from the `dn` display name and gain the `metadata_ready` flag
//! when the engine reports `MetadataComplete` (the file table itself is not
//! reachable through the 0.1.0 engine surface — documented in the README).

use std::collections::HashMap;

use typebit::metainfo::Torrent;

use crate::json::JsonWriter;

/// A single file entry (paths are byte segments joined lossily).
pub struct FileMeta {
    pub path: Vec<String>,
    pub length: u64,
}

/// The mirrored, JSON-serializable description of one torrent.
pub struct TorrentMeta {
    pub hash: String,
    pub name: String,
    pub kind: String,
    pub size: u64,
    pub piece_length: u32,
    pub piece_count: u32,
    pub private: bool,
    pub comment: Option<String>,
    pub created_by: Option<String>,
    pub creation_date: Option<i64>,
    pub announce_list: Vec<Vec<String>>,
    pub web_seeds: Vec<String>,
    pub files: Vec<FileMeta>,
    /// Save directory the torrent was added with (needed to compute the
    /// final file paths when the staging `.part` files are promoted).
    pub save_dir: String,
    /// Per-file user renames: file index (into `files`) → new relative path.
    /// The engine keeps writing to the original staged path; the rename
    /// only affects the final promotion and the UI display.
    pub renames: Vec<(u32, String)>,
    /// True once the engine delivered the metadata (magnet flow).
    pub metadata_ready: bool,
}

impl TorrentMeta {
    /// Build the full mirror from a parsed metainfo object.
    pub fn from_torrent(t: &Torrent) -> Self {
        let kind = match t.kind {
            typebit::metainfo::TorrentKind::V1 => "v1",
            typebit::metainfo::TorrentKind::V2 => "v2",
            typebit::metainfo::TorrentKind::Hybrid => "hybrid",
        }
        .to_string();

        let mut announce_list: Vec<Vec<String>> = t
            .announce_list
            .iter()
            .map(|tier| {
                tier.iter()
                    .map(|u| String::from_utf8_lossy(u).into_owned())
                    .collect()
            })
            .collect();

        // A single top-level `announce` URL that is not already in the list
        // is exposed as its own first tier.
        if announce_list.is_empty() {
            if let Some(u) = t.announce.as_ref() {
                announce_list = vec![vec![String::from_utf8_lossy(u).into_owned()]];
            }
        }

        let files = t
            .files
            .iter()
            .map(|f| FileMeta {
                path: f
                    .path
                    .iter()
                    .map(|s| String::from_utf8_lossy(s).into_owned())
                    .collect(),
                length: f.length,
            })
            .collect();

        TorrentMeta {
            hash: t.info_hash.to_hex(),
            name: t.name.clone(),
            kind,
            size: t.total_size,
            piece_length: t.piece_length,
            piece_count: t.piece_count(),
            private: t.private,
            comment: t
                .comment
                .as_ref()
                .map(|b| String::from_utf8_lossy(b).into_owned()),
            created_by: t
                .created_by
                .as_ref()
                .map(|b| String::from_utf8_lossy(b).into_owned()),
            creation_date: t.creation_date,
            announce_list,
            web_seeds: t
                .web_seeds
                .iter()
                .map(|u| String::from_utf8_lossy(u).into_owned())
                .collect(),
            files,
            save_dir: String::new(),
            renames: Vec::new(),
            metadata_ready: true,
        }
    }

    /// Placeholder for magnet links before metadata arrives.
    pub fn from_magnet(hash: &str, name: &str) -> Self {
        TorrentMeta {
            hash: hash.to_string(),
            name: name.to_string(),
            kind: "magnet".to_string(),
            size: 0,
            piece_length: 0,
            piece_count: 0,
            private: false,
            comment: None,
            created_by: None,
            creation_date: None,
            announce_list: Vec::new(),
            web_seeds: Vec::new(),
            files: Vec::new(),
            save_dir: String::new(),
            renames: Vec::new(),
            metadata_ready: false,
        }
    }

    /// Serialize to a compact JSON object.
    pub fn to_json(&self) -> String {
        let mut w = JsonWriter::new();
        w.begin_object();
        w.kv_string("hash", &self.hash);
        w.kv_string("name", &self.name);
        w.kv_string("kind", &self.kind);
        w.kv_u64("size", self.size);
        w.kv_u64("piece_length", self.piece_length as u64);
        w.kv_u64("piece_count", self.piece_count as u64);
        w.kv_bool("private", self.private);
        w.kv_bool("metadata_ready", self.metadata_ready);
        w.comma();
        w.key("comment");
        match &self.comment {
            Some(c) => w.string(c),
            None => w.null(),
        }
        w.comma();
        w.key("created_by");
        match &self.created_by {
            Some(c) => w.string(c),
            None => w.null(),
        }
        w.comma();
        w.key("creation_date");
        match self.creation_date {
            Some(d) => w.i64(d),
            None => w.null(),
        }
        w.comma();
        w.key("announce_list");
        w.begin_array();
        for (i, tier) in self.announce_list.iter().enumerate() {
            if i > 0 {
                w.comma();
            }
            w.begin_array();
            for (j, url) in tier.iter().enumerate() {
                if j > 0 {
                    w.comma();
                }
                w.string(url);
            }
            w.end_array();
        }
        w.end_array();
        w.comma();
        w.key("web_seeds");
        w.begin_array();
        for (i, u) in self.web_seeds.iter().enumerate() {
            if i > 0 {
                w.comma();
            }
            w.string(u);
        }
        w.end_array();
        w.comma();
        w.key("files");
        w.begin_array();
        for (i, f) in self.files.iter().enumerate() {
            if i > 0 {
                w.comma();
            }
            w.begin_object();
            w.key("path");
            w.begin_array();
            for (j, seg) in f.path.iter().enumerate() {
                if j > 0 {
                    w.comma();
                }
                w.string(seg);
            }
            w.end_array();
            w.comma();
            w.kv_u64("length", f.length);
            w.comma();
            w.key("renamed");
            match self.renames.iter().find(|(idx, _)| *idx == i as u32) {
                Some((_, name)) => w.string(name),
                None => w.null(),
            }
            w.end_object();
        }
        w.end_array();
        w.end_object();
        w.into_string()
    }

    /// Effective display path of a file (rename or original).
    pub fn display_path(&self, index: usize) -> String {
        let Some(f) = self.files.get(index) else {
            return String::new();
        };
        self.renames
            .iter()
            .find(|(idx, _)| *idx == index as u32)
            .map(|(_, name)| name.clone())
            .unwrap_or_else(|| f.path.join("/"))
    }
}

/// Hash → mirrored metadata.
#[derive(Default)]
pub struct MetaRegistry {
    by_hash: HashMap<String, TorrentMeta>,
}

impl MetaRegistry {
    pub fn new() -> Self {
        MetaRegistry::default()
    }

    pub fn register(&mut self, t: &Torrent) {
        let meta = TorrentMeta::from_torrent(t);
        self.by_hash.insert(meta.hash.clone(), meta);
    }

    pub fn register_magnet(&mut self, hash: &str, name: &str) {
        self.by_hash
            .insert(hash.to_string(), TorrentMeta::from_magnet(hash, name));
    }

    /// Replace a magnet placeholder with the full metainfo once the engine
    /// reports the metadata arrived (file torrents register at add time).
    /// Preserves the save directory and any per-file renames from the
    /// placeholder so staged-path bookkeeping keeps working.
    pub fn register_ready(&mut self, t: &Torrent, hash: &str) {
        let mut m = TorrentMeta::from_torrent(t);
        if let Some(old) = self.by_hash.get(hash) {
            m.save_dir = old.save_dir.clone();
            m.renames = old.renames.clone();
        }
        self.by_hash.insert(hash.to_string(), m);
    }

    /// Record the save directory the torrent was added with, so the bridge
    /// can compute final file paths when promoting staged `.part` files.
    pub fn set_save_dir(&mut self, hash: &str, dir: &str) {
        if let Some(m) = self.by_hash.get_mut(hash) {
            m.save_dir = dir.to_string();
        }
    }

    /// Rename one file (by index) of a torrent. The new name may be a bare
    /// file name or a relative path; it must be non-empty and must not
    /// escape the save directory (`..` / absolute / drive roots are
    /// rejected). Returns the effective display path on success.
    pub fn rename_file(
        &mut self,
        hash: &str,
        index: u32,
        name: &str,
    ) -> Result<String, &'static str> {
        let m = self.by_hash.get_mut(hash).ok_or("no such torrent")?;
        if index as usize >= m.files.len() {
            return Err("file index out of range");
        }
        let name = name.trim();
        if name.is_empty() {
            return Err("name is empty");
        }
        // Path-safety: allow one flat segment or a relative subpath, but
        // never an absolute path or one that climbs above the save dir.
        let segs: Vec<&str> = name.split(['/', '\\']).filter(|s| !s.is_empty()).collect();
        if segs.iter().any(|s| *s == ".." || *s == ".") || segs.iter().any(|s| s.contains(':')) {
            return Err("invalid path");
        }
        let clean = segs.join("/");
        // Replace or append the rename entry (keyed by file index).
        if let Some(entry) = m.renames.iter_mut().find(|(idx, _)| *idx == index) {
            entry.1 = clean.clone();
        } else {
            m.renames.push((index, clean.clone()));
        }
        Ok(clean)
    }

    /// Effective display path of a file (rename or original), for the UI.
    pub fn file_display_path(&self, hash: &str, index: usize) -> Option<String> {
        let m = self.by_hash.get(hash)?;
        Some(m.display_path(index))
    }

    pub fn mark_metadata_ready(&mut self, hash: &str) {
        if let Some(m) = self.by_hash.get_mut(hash) {
            m.metadata_ready = true;
        }
    }

    pub fn remove(&mut self, hash: &str) {
        self.by_hash.remove(hash);
    }

    pub fn json_for(&self, hash: &str) -> Option<String> {
        self.by_hash.get(hash).map(TorrentMeta::to_json)
    }

    /// Structured access for the batched UI snapshot (no JSON round-trip).
    pub fn get(&self, hash: &str) -> Option<&TorrentMeta> {
        self.by_hash.get(hash)
    }

    /// All hashes as a JSON array string.
    pub fn hashes_json(&self) -> String {
        let mut w = JsonWriter::new();
        w.begin_array();
        for (i, h) in self.by_hash.keys().enumerate() {
            if i > 0 {
                w.comma();
            }
            w.string(h);
        }
        w.end_array();
        w.into_string()
    }

    pub fn len(&self) -> usize {
        self.by_hash.len()
    }

    pub fn is_empty(&self) -> bool {
        self.by_hash.is_empty()
    }
}
