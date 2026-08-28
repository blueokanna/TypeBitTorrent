//! JNI bridge — the Kotlin ↔ Rust boundary.
//!
//! Every `external` Kotlin function declared in `NativeBridge.kt` (package
//! `com.typebit.engine`, top-level → class `com.typebit.engine.NativeBridgeKt`)
//! is implemented here. The engine handle is passed around as an opaque
//! `jlong`. Blocking commands wait up to [`REPLY_TIMEOUT`] on a one-shot
//! channel; event batches are pulled with `nativeTakeEvents`.
//!
//! jni 0.22: native methods receive an [`EnvUnowned`] (FFI-safe) and upgrade
//! it to a scoped [`Env`] via [`EnvUnowned::with_env`]; the outcome is
//! resolved with a policy that logs and returns the type's default on any
//! JNI error or Rust panic — panics never unwind across the FFI boundary.

use std::sync::mpsc::channel;
use std::sync::Mutex;
use std::time::Duration;

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jdouble, jint, jlong, jstring};
use jni::{Env, EnvUnowned};

use crate::engine::{parse_session_config, Cmd, EngineHandle};

/// Serializes `nativeCreateEngine` against `nativeDestroyEngine` so a
/// recreated Activity/store tearing the engine down while another boot is
/// still creating it can never race the two.
static ENGINE_MTX: Mutex<()> = Mutex::new(());

/// The live engine handle's raw pointer (`Box::into_raw`), or `None` when no
/// engine is running. Makes create/destroy idempotent at the process level:
/// a second `nativeCreateEngine` reuses the live handle instead of throwing
/// (the old behaviour crashed the app with "an engine is already running in
/// this process" whenever an Activity recreate raced a slow teardown). The
/// Box behind the pointer is intentionally LEAKED on shutdown (see
/// `nativeDestroyEngine`) so a concurrent in-flight JNI call on the stale
/// pointer can never hit freed memory.
static ENGINE_PTR: Mutex<Option<usize>> = Mutex::new(None);

/// Upper bound for one blocking engine call (add/start/progress/…).
const REPLY_TIMEOUT: Duration = Duration::from_secs(30);

/// Runs `f` with the upgraded JNI `Env`, mapping any JNI error or Rust
/// panic to the type's default (null / 0). Native methods never unwind.
fn with_env<T: Default>(
    mut unowned: EnvUnowned<'_>,
    f: impl FnOnce(&mut Env<'_>) -> jni::errors::Result<T>,
) -> T {
    unowned
        .with_env(f)
        .resolve_with::<jni::errors::LogErrorAndDefault, _>(|| ())
}

/// Interpret the opaque handle. `0` is always invalid.
///
/// The handle is produced only by `nativeCreateEngine` via `Box::into_raw`
/// and destroyed exactly once by `nativeDestroyEngine`, so the returned
/// SHARED reference is valid for the duration of the call. A shared (not
/// exclusive) reference is required: multiple JVM threads (the serialized
/// engine executor and the dedicated Peers polling thread) call JNI
/// functions concurrently, and `EngineHandle` is `Send + Sync` (mpsc
/// sender + mutex-guarded queues), so concurrent `&self` use is sound.
fn handle_from(h: jlong) -> Option<&'static EngineHandle> {
    if h == 0 {
        return None;
    }
    Some(unsafe { &*(h as *const EngineHandle) })
}

fn jstr(env: &mut Env, s: &JString) -> String {
    s.try_to_string(env).unwrap_or_default()
}

/// Minimal JSON string escaping (quotes, backslash, control chars) — used
/// when embedding an error message into a JSON value.
fn json_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out
}

fn new_jstring(env: &mut Env, s: &str) -> jstring {
    env.new_string(s)
        .map(|j| j.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn throw(env: &mut Env, msg: &str) {
    let _ = env.throw_new(
        jni::strings::JNIString::new("java/lang/IllegalStateException"),
        jni::strings::JNIString::new(msg),
    );
}

/// Standard base64 (RFC 4648, with padding) for the metadata persistence
/// round-trip (`nativeTorrentInfoRaw` → Kotlin `TorrentRecord` → back).
fn base64_encode(data: &[u8]) -> String {
    const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(data.len().div_ceil(3) * 4);
    for chunk in data.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = *chunk.get(1).unwrap_or(&0) as u32;
        let b2 = *chunk.get(2).unwrap_or(&0) as u32;
        let n = (b0 << 16) | (b1 << 8) | b2;
        out.push(ALPHABET[(n >> 18) as usize & 63] as char);
        out.push(ALPHABET[(n >> 12) as usize & 63] as char);
        out.push(if chunk.len() > 1 {
            ALPHABET[(n >> 6) as usize & 63] as char
        } else {
            '='
        });
        out.push(if chunk.len() > 2 {
            ALPHABET[n as usize & 63] as char
        } else {
            '='
        });
    }
    out
}

// ---------------------------------------------------------------------------
// lifecycle
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeCreateEngine(
    unowned: EnvUnowned,
    _class: JClass,
    config_json: JString,
    save_dir: JString,
) -> jlong {
    crate::android_log::log("nativeCreateEngine called");
    // Serialize create against destroy (a recreated Activity can tear one
    // store down while another is still booting its engine).
    let _mtx = ENGINE_MTX.lock().unwrap_or_else(|e| e.into_inner());
    // Idempotent: reuse a live engine instead of spawning a second one — the
    // process-wide singleton guard would refuse it and crash the app.
    if let Some(p) = *ENGINE_PTR.lock().unwrap_or_else(|e| e.into_inner()) {
        crate::android_log::log("nativeCreateEngine: reusing live engine handle");
        return p as jlong;
    }
    with_env(unowned, |env| {
        let cfg = jstr(env, &config_json);
        let dir = jstr(env, &save_dir);
        let logs = crate::host::LogBuffer::default();
        match crate::engine::spawn_engine(&cfg, &dir, logs) {
            Ok(handle) => {
                let ptr = Box::into_raw(Box::new(handle)) as usize;
                *ENGINE_PTR.lock().unwrap_or_else(|e| e.into_inner()) = Some(ptr);
                Ok(ptr as jlong)
            }
            Err(e) => {
                throw(env, &format!("nativeCreateEngine: {e}"));
                Ok(0)
            }
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeDestroyEngine(
    _unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
) {
    crate::android_log::log("nativeDestroyEngine called");
    if handle == 0 {
        return;
    }
    let _mtx = ENGINE_MTX.lock().unwrap_or_else(|e| e.into_inner());
    let mut ptr = ENGINE_PTR.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(p) = *ptr {
        if p == handle as usize {
            // Safety: `p` is the live handle produced by nativeCreateEngine's
            // `Box::into_raw` and is still current (guarded by ENGINE_MTX).
            // `shutdown` joins the worker thread (releasing the ENGINE_LIVE
            // guard) so a later create can spawn afresh. The Box is
            // intentionally LEAKED: a concurrent in-flight JNI call on this
            // pointer (e.g. a cancelled boot still finishing a blocking
            // request) can never hit freed memory — one tiny allocation per
            // process, never reclaimed, is the price of eliminating the
            // use-after-free class entirely.
            let h = unsafe { &*(p as *const EngineHandle) };
            h.shutdown();
            *ptr = None;
            crate::android_log::log("nativeDestroyEngine: engine shut down");
        } else {
            crate::android_log::log("nativeDestroyEngine: stale handle ignored (idempotent)");
        }
    }
}

// ---------------------------------------------------------------------------
// torrents
// ---------------------------------------------------------------------------

/// Parse a `.torrent` blob into its metainfo JSON WITHOUT adding it to the
/// engine — powers the add-torrent dialog's "preview before download".
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeParseTorrent(
    unowned: EnvUnowned,
    _class: JClass,
    data: JByteArray,
) -> jstring {
    with_env(unowned, |env| {
        let bytes = env.convert_byte_array(&data).unwrap_or_default();
        let json = typebit::metainfo::Torrent::from_bytes(&bytes)
            .ok()
            .map(|t| crate::meta::TorrentMeta::from_torrent(&t).to_json());
        Ok(match json {
            Some(s) => new_jstring(env, &s),
            None => std::ptr::null_mut(),
        })
    })
}

/// Parse a JSON array of files for `nativeMakeTorrent`: `[{"abs":"C:/x/a.bin","rel":["dir","a.bin"]}, …]`.
fn parse_make_files(json: &str) -> Result<Vec<crate::make_torrent::FileSpec>, String> {
    use nextjson::Value;
    let v: Value =
        nextjson::nextdecode(json.as_bytes()).map_err(|e| format!("bad files json: {e}"))?;
    let arr = v.as_array().ok_or("files must be a JSON array")?;
    let mut out = Vec::with_capacity(arr.len());
    for e in arr {
        let abs = e
            .get("abs")
            .and_then(Value::as_str)
            .ok_or("file entry missing abs")?
            .to_string();
        let mut rel = Vec::new();
        if let Some(r) = e.get("rel").and_then(Value::as_array) {
            for c in r {
                rel.push(
                    c.as_str()
                        .ok_or("file entry has a non-string rel component")?
                        .to_string(),
                );
            }
        }
        if rel.is_empty() {
            return Err("file entry missing rel".into());
        }
        out.push(crate::make_torrent::FileSpec {
            abs_path: std::path::PathBuf::from(abs),
            rel_path: rel,
        });
    }
    Ok(out)
}

fn opt_str(s: &str) -> Option<&str> {
    let t = s.trim();
    if t.is_empty() {
        None
    } else {
        Some(t)
    }
}

/// Create a v1 `.torrent` from local files (blocking, caller's thread). `files_json` is the array
/// parsed by [`parse_make_files`]; returns raw `.torrent` bytes, or null (with a Java exception).
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeMakeTorrent(
    unowned: EnvUnowned,
    _class: JClass,
    files_json: JString,
    piece_length: jint,
    name: JString,
    announce: JString,
    comment: JString,
) -> jbyteArray {
    with_env(unowned, |env| {
        let files_json = jstr(env, &files_json);
        let name = jstr(env, &name);
        let announce = jstr(env, &announce);
        let comment = jstr(env, &comment);
        let specs = match parse_make_files(&files_json) {
            Ok(s) => s,
            Err(e) => {
                throw(env, &format!("nativeMakeTorrent: {e}"));
                return Ok(std::ptr::null_mut());
            }
        };
        match crate::make_torrent::create_torrent_v1(
            &specs,
            piece_length as u32,
            if name.trim().is_empty() {
                "torrent"
            } else {
                name.trim()
            },
            opt_str(&announce),
            opt_str(&comment),
        ) {
            Ok(bytes) => Ok(env.byte_array_from_slice(&bytes)?.into_raw()),
            Err(e) => {
                throw(env, &format!("nativeMakeTorrent: {e}"));
                Ok(std::ptr::null_mut())
            }
        }
    })
}

/// Adds a `.torrent`; `priorities_json` is a JSON array of per-file priority
/// bytes (`[0,1,2,…]`, 0=Skip 1=Normal 2=High) aligned with the file table.
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeAddTorrent(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
    data: JByteArray,
    save_dir: JString,
    priorities_json: JString,
) -> jstring {
    with_env(unowned, |env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(std::ptr::null_mut()),
        };
        let bytes = env.convert_byte_array(&data).unwrap_or_default();
        let dir = jstr(env, &save_dir);
        let prio_json = jstr(env, &priorities_json);
        let file_priorities = parse_priority_json(&prio_json);
        let (tx, rx) = channel();
        match h.request(
            Cmd::AddTorrent {
                data: bytes,
                save_dir: dir,
                file_priorities,
                tx,
            },
            rx,
            REPLY_TIMEOUT,
        ) {
            Some(Ok(hex)) => Ok(new_jstring(env, &hex)),
            _ => Ok(std::ptr::null_mut()),
        }
    })
}

/// Parse a JSON array of priority bytes; unknown/invalid values degrade to
/// an empty list (the engine then keeps every file at Normal).
fn parse_priority_json(json: &str) -> Vec<u8> {
    let Ok(v) = nextjson::nextdecode::<nextjson::Value>(json.as_bytes()) else {
        return Vec::new();
    };
    let Some(arr) = v.as_array() else {
        return Vec::new();
    };
    arr.iter()
        .map(|e| match e.as_u64() {
            Some(0) => 0,
            Some(2) => 2,
            _ => 1,
        })
        .collect()
}

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeAddMagnet(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
    uri: JString,
    save_dir: JString,
) -> jstring {
    with_env(unowned, |env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(std::ptr::null_mut()),
        };
        let uri = jstr(env, &uri);
        let dir = jstr(env, &save_dir);
        let (tx, rx) = channel();
        match h.request(
            Cmd::AddMagnet {
                uri,
                save_dir: dir,
                tx,
            },
            rx,
            REPLY_TIMEOUT,
        ) {
            Some(Ok(hex)) => Ok(new_jstring(env, &hex)),
            _ => Ok(std::ptr::null_mut()),
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeStart(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
    hash: JString,
) -> jint {
    with_env(unowned, |env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(-4),
        };
        let hash = jstr(env, &hash);
        let (tx, rx) = channel();
        Ok(
            match h.request(Cmd::Start { hash, tx }, rx, REPLY_TIMEOUT) {
                Some(Ok(())) => 0,
                Some(Err(_)) => -1,
                None => -2,
            },
        )
    })
}

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativePause(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
    hash: JString,
) -> jint {
    with_env(unowned, |env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(-4),
        };
        let hash = jstr(env, &hash);
        h.send(Cmd::Pause { hash });
        Ok(0)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeResume(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
    hash: JString,
) -> jint {
    with_env(unowned, |env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(-4),
        };
        let hash = jstr(env, &hash);
        h.send(Cmd::Resume { hash });
        Ok(0)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeRemove(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
    hash: JString,
) -> jint {
    with_env(unowned, |env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(-4),
        };
        let hash = jstr(env, &hash);
        let (tx, rx) = channel();
        Ok(
            match h.request(Cmd::Remove { hash, tx }, rx, REPLY_TIMEOUT) {
                Some(Ok(())) => 0,
                Some(Err(_)) => -1,
                None => -2,
            },
        )
    })
}

/// Rename one file of a running torrent (0 = ok, -1 = invalid rename,
/// -2 = timeout, -4 = engine missing).
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeRenameFile(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
    hash: JString,
    file: jint,
    name: JString,
) -> jint {
    with_env(unowned, |env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(-4),
        };
        let hash = jstr(env, &hash);
        let name = jstr(env, &name);
        let (tx, rx) = channel();
        Ok(
            match h.request(
                Cmd::RenameFile {
                    hash,
                    file: file as u32,
                    name,
                    tx,
                },
                rx,
                REPLY_TIMEOUT,
            ) {
                Some(Ok(_)) => 0,
                Some(Err(_)) => -1,
                None => -2,
            },
        )
    })
}

/// Rename the torrent itself (display name). 0 = ok, -1 = invalid rename,
/// -2 = timeout, -4 = engine missing.
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeRenameTorrent(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
    hash: JString,
    name: JString,
) -> jint {
    with_env(unowned, |env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(-4),
        };
        let hash = jstr(env, &hash);
        let name = jstr(env, &name);
        let (tx, rx) = channel();
        Ok(
            match h.request(Cmd::RenameTorrent { hash, name, tx }, rx, REPLY_TIMEOUT) {
                Some(Ok(_)) => 0,
                Some(Err(_)) => -1,
                None => -2,
            },
        )
    })
}

// ---------------------------------------------------------------------------
// per-torrent queries
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeProgress(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
    hash: JString,
) -> jdouble {
    with_env(unowned, |env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(0.0),
        };
        let hash = jstr(env, &hash);
        let (tx, rx) = channel();
        Ok(h.request(Cmd::Progress { hash, tx }, rx, REPLY_TIMEOUT)
            .unwrap_or(0.0))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeDownloaded(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
    hash: JString,
) -> jlong {
    with_env(unowned, |env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(0),
        };
        let hash = jstr(env, &hash);
        let (tx, rx) = channel();
        Ok(h.request(Cmd::Downloaded { hash, tx }, rx, REPLY_TIMEOUT)
            .unwrap_or(0) as jlong)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeIsComplete(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
    hash: JString,
) -> jboolean {
    with_env(unowned, |env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(false),
        };
        let hash = jstr(env, &hash);
        let (tx, rx) = channel();
        Ok(h.request(Cmd::IsComplete { hash, tx }, rx, REPLY_TIMEOUT)
            .unwrap_or(false))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeTorrentInfo(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
    hash: JString,
) -> jstring {
    with_env(unowned, |env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(std::ptr::null_mut()),
        };
        let hash = jstr(env, &hash);
        let (tx, rx) = channel();
        Ok(
            match h.request(Cmd::TorrentInfo { hash, tx }, rx, REPLY_TIMEOUT) {
                Some(Some(json)) => new_jstring(env, &json),
                _ => std::ptr::null_mut(),
            },
        )
    })
}

/// The raw bencoded `info` dict of a torrent, base64-encoded (for
/// persistence so magnets never re-fetch metadata after a restart). Returns
/// null when the torrent has no metadata yet.
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeTorrentInfoRaw(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
    hash: JString,
) -> jstring {
    with_env(unowned, |env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(std::ptr::null_mut()),
        };
        let hash = jstr(env, &hash);
        let (tx, rx) = channel();
        let raw = h.request(Cmd::TorrentInfoRaw { hash, tx }, rx, REPLY_TIMEOUT);
        match raw {
            Some(Some(bytes)) => Ok(new_jstring(env, &base64_encode(&bytes))),
            _ => Ok(std::ptr::null_mut()),
        }
    })
}

/// All torrents' persisted state (have counts, paused flags) as JSON.
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeTorrentStates(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jstring {
    with_env(unowned, |env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(new_jstring(env, "[]")),
        };
        let (tx, rx) = channel();
        let json = h
            .request(Cmd::TorrentStates { tx }, rx, REPLY_TIMEOUT)
            .unwrap_or_else(|| "[]".to_string());
        Ok(new_jstring(env, &json))
    })
}

/// Live peer snapshot of a torrent as a JSON array.
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativePeers(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
    hash: JString,
) -> jstring {
    with_env(unowned, |env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(new_jstring(env, "[]")),
        };
        let hash = jstr(env, &hash);
        let (tx, rx) = channel();
        let json = h
            .request(Cmd::Peers { hash, tx }, rx, REPLY_TIMEOUT)
            .unwrap_or_else(|| "[]".to_string());
        Ok(new_jstring(env, &json))
    })
}

/// One batched UI snapshot: `{"dht":n,"totals":{"d","u"},"torrents":[…]}`.
/// This is the single per-tick query — it replaces the per-torrent
/// round-trips the store used to make for every hash.
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeSnapshot(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jstring {
    with_env(unowned, |env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(new_jstring(env, "{\"dht\":0,\"torrents\":[]}")),
        };
        let (tx, rx) = channel();
        let json = h
            .request(Cmd::Snapshot { tx }, rx, REPLY_TIMEOUT)
            .unwrap_or_else(|| "{\"dht\":0,\"torrents\":[]}".to_string());
        Ok(new_jstring(env, &json))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeTorrentCount(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jint {
    with_env(unowned, |_env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(0),
        };
        let (tx, rx) = channel();
        Ok(h.request(Cmd::TorrentCount { tx }, rx, REPLY_TIMEOUT)
            .unwrap_or(0) as jint)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeDhtNodeCount(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jint {
    with_env(unowned, |_env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(0),
        };
        let (tx, rx) = channel();
        Ok(h.request(Cmd::DhtCount { tx }, rx, REPLY_TIMEOUT)
            .unwrap_or(0) as jint)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativePeerId(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jstring {
    with_env(unowned, |env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(new_jstring(env, "")),
        };
        let (tx, rx) = channel();
        let pid = h
            .request(Cmd::PeerId { tx }, rx, REPLY_TIMEOUT)
            .unwrap_or_default();
        Ok(new_jstring(env, &pid))
    })
}

/// Global wire counters as `{"d":down_total,"u":up_total}`.
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeTotals(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jstring {
    with_env(unowned, |env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(new_jstring(env, "{\"d\":0,\"u\":0}")),
        };
        let (tx, rx) = channel();
        let (d, u) = h
            .request(Cmd::Totals { tx }, rx, REPLY_TIMEOUT)
            .unwrap_or((0, 0));
        let json = format!("{{\"d\":{d},\"u\":{u}}}");
        Ok(new_jstring(env, &json))
    })
}

/// Engine-wide statistics JSON for the stats dialog (see `stats_to_json`).
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeStats(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jstring {
    with_env(unowned, |env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(new_jstring(env, "{}")),
        };
        let (tx, rx) = channel();
        let json = h
            .request(Cmd::Stats { tx }, rx, REPLY_TIMEOUT)
            .unwrap_or_else(|| String::from("{}"));
        Ok(new_jstring(env, &json))
    })
}

/// Export a signed proof-of-download receipt for a torrent over an absolute
/// byte range and wall-clock window (unix seconds). Returns the receipt JSON
/// on success, or `{"error":"…"}` when coverage is insufficient.
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeExportReceipt(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
    hash: JString,
    range_start: jlong,
    range_end: jlong,
    epoch_start: jlong,
    epoch_end: jlong,
) -> jstring {
    with_env(unowned, |env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(new_jstring(env, "{\"error\":\"engine not running\"}")),
        };
        let hash = jstr(env, &hash);
        let (tx, rx) = channel();
        let res = h.request(
            Cmd::ExportReceipt {
                hash,
                range_start: range_start.max(0) as u64,
                range_end: range_end.max(0) as u64,
                epoch_start: epoch_start.max(0) as u64,
                epoch_end: epoch_end.max(0) as u64,
                tx,
            },
            rx,
            REPLY_TIMEOUT,
        );
        let out = match res {
            Some(Ok(json)) => json,
            Some(Err(e)) => format!("{{\"error\":{}}}", json_escape(&e)),
            None => "{\"error\":\"engine busy\"}".to_string(),
        };
        Ok(new_jstring(env, &out))
    })
}

/// Verify a receipt JSON (Ed25519 signature + structure). Returns a result
/// JSON with the attested fields and `ok`.
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeVerifyReceipt(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
    json: JString,
) -> jstring {
    with_env(unowned, |env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => {
                return Ok(new_jstring(
                    env,
                    "{\"ok\":false,\"error\":\"engine not running\"}",
                ))
            }
        };
        let json = jstr(env, &json);
        let (tx, rx) = channel();
        let out = h
            .request(Cmd::VerifyReceipt { json, tx }, rx, REPLY_TIMEOUT)
            .unwrap_or_else(|| "{\"ok\":false,\"error\":\"engine busy\"}".to_string());
        Ok(new_jstring(env, &out))
    })
}

// ---------------------------------------------------------------------------
// configuration
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeSetGlobalLimits(
    _unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
    down: jlong,
    up: jlong,
) -> jint {
    let h = match handle_from(handle) {
        Some(h) => h,
        None => return -4,
    };
    let down = if down < 0 { 0 } else { down as u64 };
    let up = if up < 0 { 0 } else { up as u64 };
    h.send(Cmd::SetLimits { down, up });
    0
}

/// Apply per-torrent session defaults for torrents added from now on.
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeSetSessionConfig(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
    config_json: JString,
) -> jint {
    with_env(unowned, |env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(-4),
        };
        let json = jstr(env, &config_json);
        match parse_session_config(&json) {
            Ok(cfg) => {
                h.send(Cmd::SetSessionConfig { cfg });
                Ok(0)
            }
            Err(e) => {
                throw(env, &format!("nativeSetSessionConfig: {e}"));
                Ok(-1)
            }
        }
    })
}

// ---------------------------------------------------------------------------
// selective download + runtime trackers (typebit 0.1.1)
// ---------------------------------------------------------------------------

/// Set one file's priority (0=Skip, 1=Normal, 2=High).
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeSetFilePriority(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
    hash: JString,
    file: jint,
    prio: jint,
) -> jint {
    with_env(unowned, |env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(-4),
        };
        let hash = jstr(env, &hash);
        let prio = match prio {
            0 => 0u8,
            2 => 2u8,
            _ => 1u8,
        };
        let (tx, rx) = channel();
        Ok(
            match h.request(
                Cmd::SetFilePriority {
                    hash,
                    file: file.max(0) as u32,
                    prio,
                    tx,
                },
                rx,
                REPLY_TIMEOUT,
            ) {
                Some(Ok(())) => 0,
                Some(Err(_)) => -1,
                None => -2,
            },
        )
    })
}

/// Current per-file priorities of a torrent as a JSON array.
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeFilePriorities(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
    hash: JString,
) -> jstring {
    with_env(unowned, |env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(std::ptr::null_mut()),
        };
        let hash = jstr(env, &hash);
        let (tx, rx) = channel();
        Ok(
            match h.request(Cmd::FilePriorities { hash, tx }, rx, REPLY_TIMEOUT) {
                Some(Some(json)) => new_jstring(env, &json),
                _ => std::ptr::null_mut(),
            },
        )
    })
}

/// Atomically replace ALL per-file priorities and release any two-phase
/// magnet hold (0=Skip, 1=Normal, 2=High). `priorities_json` is a JSON array
/// aligned with the file table.
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeSetFilePriorities(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
    hash: JString,
    priorities_json: JString,
) -> jint {
    with_env(unowned, |env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(-4),
        };
        let hash = jstr(env, &hash);
        let prio_json = jstr(env, &priorities_json);
        let priorities = parse_priority_json(&prio_json);
        let (tx, rx) = channel();
        Ok(
            match h.request(
                Cmd::SetFilePriorities {
                    hash,
                    priorities,
                    tx,
                },
                rx,
                REPLY_TIMEOUT,
            ) {
                Some(Ok(())) => 0,
                Some(Err(_)) => -1,
                None => -2,
            },
        )
    })
}

/// Two-phase magnet support: hold off data downloads until per-file
/// priorities are committed. `hold != 0` enables the hold, 0 releases it.
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeSetHoldData(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
    hash: JString,
    hold: jint,
) -> jint {
    with_env(unowned, |env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(-4),
        };
        let hash = jstr(env, &hash);
        let (tx, rx) = channel();
        Ok(
            match h.request(
                Cmd::SetHoldData {
                    hash,
                    hold: hold != 0,
                    tx,
                },
                rx,
                REPLY_TIMEOUT,
            ) {
                Some(Ok(())) => 0,
                Some(Err(_)) => -1,
                None => -2,
            },
        )
    })
}

/// Add a tracker URL to a running torrent.
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeAddTracker(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
    hash: JString,
    url: JString,
) -> jint {
    with_env(unowned, |env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(-4),
        };
        let hash = jstr(env, &hash);
        let url = jstr(env, &url);
        let (tx, rx) = channel();
        Ok(
            match h.request(Cmd::AddTracker { hash, url, tx }, rx, REPLY_TIMEOUT) {
                Some(Ok(())) => 0,
                Some(Err(_)) => -1,
                None => -2,
            },
        )
    })
}

/// Remove a tracker URL from a running torrent.
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeRemoveTracker(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
    hash: JString,
    url: JString,
) -> jint {
    with_env(unowned, |env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(-4),
        };
        let hash = jstr(env, &hash);
        let url = jstr(env, &url);
        let (tx, rx) = channel();
        Ok(
            match h.request(Cmd::RemoveTracker { hash, url, tx }, rx, REPLY_TIMEOUT) {
                Some(Ok(())) => 0,
                Some(Err(_)) => -1,
                None => -2,
            },
        )
    })
}

/// Current tracker URLs of a torrent as a JSON array.
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeTrackers(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
    hash: JString,
) -> jstring {
    with_env(unowned, |env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(std::ptr::null_mut()),
        };
        let hash = jstr(env, &hash);
        let (tx, rx) = channel();
        Ok(
            match h.request(Cmd::Trackers { hash, tx }, rx, REPLY_TIMEOUT) {
                Some(Some(json)) => new_jstring(env, &json),
                _ => std::ptr::null_mut(),
            },
        )
    })
}

// ---------------------------------------------------------------------------
// persistence
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeSaveState(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jbyteArray {
    with_env(unowned, |env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(std::ptr::null_mut()),
        };
        let (tx, rx) = channel();
        Ok(match h.request(Cmd::SaveState { tx }, rx, REPLY_TIMEOUT) {
            Some(Some(bytes)) => env
                .byte_array_from_slice(&bytes)
                .map(|a| a.into_raw())
                .unwrap_or(std::ptr::null_mut()),
            _ => std::ptr::null_mut(),
        })
    })
}

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeLoadState(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
    data: JByteArray,
) -> jint {
    with_env(unowned, |env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(-4),
        };
        let bytes = env.convert_byte_array(&data).unwrap_or_default();
        h.send(Cmd::LoadState { data: bytes });
        Ok(0)
    })
}

// ---------------------------------------------------------------------------
// polling
// ---------------------------------------------------------------------------

/// Drain all pending events and return them as one JSON array.
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeTakeEvents(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jstring {
    with_env(unowned, |env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(new_jstring(env, "[]")),
        };
        let mut out = String::with_capacity(256);
        out.push('[');
        let mut first = true;
        if let Ok(mut q) = h.events.lock() {
            while let Some(ev) = q.pop_front() {
                if !first {
                    out.push(',');
                }
                out.push_str(&ev);
                first = false;
            }
        }
        out.push(']');
        Ok(new_jstring(env, &out))
    })
}

/// Drain all pending log lines as `[{"l":level,"m":"msg"},…]`.
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeTakeLogs(
    unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jstring {
    with_env(unowned, |env| {
        let h = match handle_from(handle) {
            Some(h) => h,
            None => return Ok(new_jstring(env, "[]")),
        };
        let mut out = String::with_capacity(512);
        out.push('[');
        let mut first = true;
        if let Ok(mut q) = h.logs.lock() {
            while let Some((lvl, msg)) = q.pop_front() {
                if !first {
                    out.push(',');
                }
                out.push_str("{\"l\":");
                out.push_str(&lvl.to_string());
                out.push_str(",\"m\":");
                let mut w = crate::json::JsonWriter::new();
                w.string(&msg);
                out.push_str(w.as_str());
                out.push('}');
                first = false;
            }
        }
        out.push(']');
        Ok(new_jstring(env, &out))
    })
}

// ---------------------------------------------------------------------------
// Windows system integration (firewall / ICS)
//
// These are one-shot, user-initiated operations that do NOT touch the
// engine handle: they run `netsh` / `powershell` on the calling (JVM IO)
// thread and return a `{"ok":bool,"message":".."}` JSON string. Slow system
// calls therefore never block the engine loop. Android simply reports
// "仅 Windows 支持" from `firewall.rs`.
// ---------------------------------------------------------------------------

/// Add inbound firewall rules for `port` (TCP+UDP). May fail with an
/// elevation hint; call `nativeFirewallAddElevated` for the UAC retry.
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeFirewallAdd(
    unowned: EnvUnowned,
    _class: JClass,
    port: jint,
) -> jstring {
    with_env(unowned, |env| {
        let r = crate::firewall::firewall_add(port.max(0) as u16);
        Ok(new_jstring(env, &crate::firewall::sys_result_to_json(&r)))
    })
}

/// Retry [`nativeFirewallAdd`] through a single UAC elevation prompt.
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeFirewallAddElevated(
    unowned: EnvUnowned,
    _class: JClass,
    port: jint,
) -> jstring {
    with_env(unowned, |env| {
        let r = crate::firewall::firewall_add_elevated(port.max(0) as u16);
        Ok(new_jstring(env, &crate::firewall::sys_result_to_json(&r)))
    })
}

/// Remove the inbound firewall rules for `port`.
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeFirewallRemove(
    unowned: EnvUnowned,
    _class: JClass,
    port: jint,
) -> jstring {
    with_env(unowned, |env| {
        let r = crate::firewall::firewall_remove(port.max(0) as u16);
        Ok(new_jstring(env, &crate::firewall::sys_result_to_json(&r)))
    })
}

/// Whether the firewall rules for `port` exist.
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeFirewallStatus(
    unowned: EnvUnowned,
    _class: JClass,
    port: jint,
) -> jstring {
    with_env(unowned, |env| {
        let r = crate::firewall::firewall_status(port.max(0) as u16);
        Ok(new_jstring(env, &crate::firewall::sys_result_to_json(&r)))
    })
}

/// Query whether Internet Connection Sharing is currently enabled.
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeIcsStatus(
    unowned: EnvUnowned,
    _class: JClass,
) -> jstring {
    with_env(unowned, |env| {
        let r = crate::firewall::ics_status();
        Ok(new_jstring(env, &crate::firewall::sys_result_to_json(&r)))
    })
}

/// Enable Internet Connection Sharing (explicit, admin-gated, changes
/// system networking).
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeIcsEnable(
    unowned: EnvUnowned,
    _class: JClass,
) -> jstring {
    with_env(unowned, |env| {
        let r = crate::firewall::ics_enable();
        Ok(new_jstring(env, &crate::firewall::sys_result_to_json(&r)))
    })
}

/// Disable Internet Connection Sharing on all shared connections.
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeIcsDisable(
    unowned: EnvUnowned,
    _class: JClass,
) -> jstring {
    with_env(unowned, |env| {
        let r = crate::firewall::ics_disable();
        Ok(new_jstring(env, &crate::firewall::sys_result_to_json(&r)))
    })
}
