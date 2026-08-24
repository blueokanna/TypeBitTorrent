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
            w.end_object();
        }
        w.end_array();
        w.end_object();
        w.into_string()
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
