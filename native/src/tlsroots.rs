//! Trust anchors for outbound TLS.
//!
//! This exists because `courierust` refuses to guess, and it is right not to:
//! `ClientConfig::default()` carries `tls: None`, and an `https://` URL under
//! `tls: None` is rejected with *"https requires TLS settings"*. `TlsSettings::default()`
//! trusts nothing at all. So every caller that wants to reach a real tracker or
//! web seed has to say where the anchors come from — and on Android, the answer
//! the library gives does not exist: it reads the Unix bundle paths
//! (`/etc/ssl/certs/ca-certificates.crt`, …) which Android does not have. The
//! platform keeps its anchors in a directory of extensionless PEM files under
//! `/system/etc/security/cacerts`, and since Android 14 also under
//! `/apex/com.android.conscrypt/cacerts`.
//!
//! The order is deliberate: the platform loader first (it is the only thing
//! that knows about the Windows `ROOT` store or a distribution's bundle), then
//! the Android directories. Verification is never turned off. A client that
//! cannot be told what to trust fails loudly; it does not fall back to trusting
//! everything, which would hand every `https://` tracker and web seed to anyone
//! able to answer on the path.
//!
//! `TYPEBIT_CA_BUNDLE` overrides both: a container without `ca-certificates`
//! (an Unraid or fnOS image built from scratch, say) can point at a bundle and
//! have it win. An explicitly configured bundle that cannot be read is an
//! error, not a silent fallback — the operator asked for that file.

use std::path::{Path, PathBuf};
use std::sync::OnceLock;

use courierust::courierust_tls::RootStore;

/// Largest file considered as a candidate anchor. A certificate is a few
/// kilobytes; anything much larger is not one, and the cap is what keeps a scan
/// of a directory that turns out to contain something else cheap.
const MAX_ANCHOR_FILE: u64 = 64 * 1024;

/// Most candidates taken from one directory. Bounds the cost of a hostile or
/// accidental layout (a directory of a million files) at one startup scan.
const MAX_ANCHOR_FILES: usize = 512;

/// Where the anchors were read from, plus how many. Reported once at startup so
/// "HTTPS does not work" has an answer the user can act on.
#[derive(Debug, Clone)]
pub struct TrustAnchors {
    pub roots: RootStore,
    /// Human-readable origin, e.g. `系统信任库` or a path.
    pub source: String,
    pub count: usize,
}

/// The process-wide anchors.
///
/// Cached because the load reads a few hundred files, and both the HTTP clients
/// and the resolver ask for it. `OnceLock` rather than a lazy static so the
/// failure is a value the caller can log, not a panic.
pub fn anchors() -> Result<&'static TrustAnchors, &'static str> {
    static CACHE: OnceLock<Result<TrustAnchors, String>> = OnceLock::new();
    CACHE
        .get_or_init(load)
        .as_ref()
        .map_err(|why| why.as_str())
}

fn load() -> Result<TrustAnchors, String> {
    if let Some(path) = std::env::var_os("TYPEBIT_CA_BUNDLE") {
        let path = PathBuf::from(path);
        let mut roots = RootStore::new();
        let n = roots
            .add_pem_file(&path)
            .map_err(|e| format!("TYPEBIT_CA_BUNDLE={}: {e}", path.display()))?;
        if n == 0 {
            return Err(format!("TYPEBIT_CA_BUNDLE={}: 不含证书", path.display()));
        }
        return Ok(TrustAnchors {
            roots,
            source: path.display().to_string(),
            count: n,
        });
    }

    let mut roots = RootStore::new();
    if let Ok(n) = roots.load_system() {
        if n > 0 {
            return Ok(TrustAnchors {
                roots,
                source: "系统信任库".to_string(),
                count: n,
            });
        }
    }

    let (n, tried) = load_pem_dirs(&platform_dirs(), &mut roots);
    if n > 0 {
        return Ok(TrustAnchors {
            roots,
            source: format!("{} 个系统目录", platform_dirs().len()),
            count: n,
        });
    }
    Err(format!(
        "无可读的信任库（系统信任库不可用；目录 {}）",
        tried.join("; ")
    ))
}

/// The directories to scan when the platform loader fails.
///
/// Android only. Linux distributions are covered by the loader (it also walks
/// `/etc/ssl/certs` for the per-root symlinks), and Windows' store is not a
/// directory at all.
fn platform_dirs() -> Vec<PathBuf> {
    #[cfg(target_os = "android")]
    {
        [
            // Android 14 moved the system anchors into an APEX module; both
            // paths exist on a 14+ device and only the second on older ones.
            "/apex/com.android.conscrypt/cacerts",
            "/system/etc/security/cacerts",
            // User-installed CAs. Usually unreadable to an app (`0700 root`),
            // which costs one failed `read_dir` and covers the rooted or
            // enterprise-managed device where it is readable.
            "/data/misc/keychain/cacerts-added",
        ]
        .iter()
        .map(PathBuf::from)
        .filter(|p| p.is_dir())
        .collect()
    }
    #[cfg(not(target_os = "android"))]
    {
        Vec::new()
    }
}

/// Reads every certificate-looking file in `dirs` into `roots`.
///
/// Returns how many were added plus a description of what was tried, which is
/// what the failure message is built from. The filter cannot be an extension
/// check: Android names its anchors by subject hash with no extension at all,
/// so the rule is "small enough to be a certificate, and parses as PEM".
fn load_pem_dirs(dirs: &[PathBuf], roots: &mut RootStore) -> (usize, Vec<String>) {
    let mut added = 0usize;
    let mut tried = Vec::new();
    for dir in dirs {
        let candidates = pem_candidates(dir);
        if candidates.is_empty() {
            tried.push(format!("{} (无可读文件)", dir.display()));
            continue;
        }
        let mut here = 0usize;
        for path in &candidates {
            let Ok(pem) = std::fs::read_to_string(path) else {
                continue;
            };
            here += roots.add_pem(&pem).unwrap_or(0);
        }
        if here == 0 {
            tried.push(format!("{} (未解析出证书)", dir.display()));
        }
        added += here;
    }
    (added, tried)
}

/// The files in `dir` worth trying as certificates, in a stable order.
///
/// Skips directories, symlinks that do not resolve, anything over
/// [`MAX_ANCHOR_FILE`] and anything beyond [`MAX_ANCHOR_FILES`]. Sorted so two
/// runs build the same store, which is what makes a failure reproducible.
fn pem_candidates(dir: &Path) -> Vec<PathBuf> {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return Vec::new();
    };
    let mut out: Vec<PathBuf> = entries
        .flatten()
        .filter_map(|e| {
            let meta = e.metadata().ok()?;
            if !meta.is_file() || meta.len() == 0 || meta.len() > MAX_ANCHOR_FILE {
                return None;
            }
            Some(e.path())
        })
        .collect();
    out.sort();
    out.truncate(MAX_ANCHOR_FILES);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A scratch directory that cleans up after itself.
    struct TempDir(PathBuf);

    impl TempDir {
        fn new(tag: &str) -> Self {
            let base = std::env::temp_dir().join(format!(
                "typebit-tlsroots-{tag}-{}-{:?}",
                std::process::id(),
                std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_nanos())
                    .unwrap_or(0)
            ));
            std::fs::create_dir_all(&base).expect("temp dir");
            TempDir(base)
        }
    }

    impl Drop for TempDir {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn candidates_skip_directories_empty_and_oversized_files() {
        let dir = TempDir::new("candidates");
        std::fs::write(dir.0.join("good"), "-----BEGIN CERTIFICATE-----\n").unwrap();
        std::fs::write(dir.0.join("empty"), "").unwrap();
        std::fs::write(dir.0.join("huge"), vec![b'x'; MAX_ANCHOR_FILE as usize + 1]).unwrap();
        std::fs::create_dir(dir.0.join("subdir")).unwrap();

        let names: Vec<String> = pem_candidates(&dir.0)
            .iter()
            .map(|p| p.file_name().unwrap().to_string_lossy().into_owned())
            .collect();
        assert_eq!(names, vec!["good"]);
    }

    /// The scan is capped, and the cap keeps the *lowest* names: a directory
    /// with far too many entries must still produce a deterministic store.
    #[test]
    fn candidates_are_sorted_and_capped() {
        let dir = TempDir::new("cap");
        for i in 0..(MAX_ANCHOR_FILES + 8) {
            std::fs::write(dir.0.join(format!("{i:04}")), "x").unwrap();
        }
        let got = pem_candidates(&dir.0);
        assert_eq!(got.len(), MAX_ANCHOR_FILES);
        assert_eq!(got[0].file_name().unwrap(), "0000");
        assert_eq!(
            got[MAX_ANCHOR_FILES - 1].file_name().unwrap(),
            std::ffi::OsStr::new(&format!("{:04}", MAX_ANCHOR_FILES - 1))
        );
    }

    #[test]
    fn a_missing_directory_is_reported_not_fatal() {
        let dir = TempDir::new("missing");
        let gone = dir.0.join("gone");
        let mut roots = RootStore::new();
        let (added, tried) = load_pem_dirs(&[gone], &mut roots);
        assert_eq!(added, 0);
        assert_eq!(tried.len(), 1);
        assert!(tried[0].contains("无可读文件"), "{}", tried[0]);
    }

    /// The process-wide load either yields a store or a message naming the
    /// paths it tried — never a silently empty store, which would present as
    /// "every https:// request fails" with nothing to go on.
    #[test]
    fn the_process_store_or_a_reason() {
        match anchors() {
            Ok(a) => {
                assert!(a.count > 0);
                assert!(!a.roots.is_empty());
                assert!(!a.source.is_empty());
            }
            Err(why) => assert!(why.contains("信任库"), "{why}"),
        }
    }
}
