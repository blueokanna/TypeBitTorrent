//! JNI bridge — the Kotlin ↔ Rust boundary.
//!
//! Every `external` Kotlin function declared in `NativeBridge.kt` (package
//! `com.typebit.engine`, top-level → class `com.typebit.engine.NativeBridgeKt`)
//! is implemented here. The engine handle is passed around as an opaque
//! `jlong`. Blocking commands wait up to [`REPLY_TIMEOUT`] on a one-shot
//! channel; event batches are pulled with `nativeTakeEvents`.

use std::sync::mpsc::channel;
use std::time::Duration;

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jdouble, jint, jlong, jstring};
use jni::JNIEnv;

use crate::engine::{parse_session_config, Cmd, EngineHandle};

/// Upper bound for one blocking engine call (add/start/progress/…).
const REPLY_TIMEOUT: Duration = Duration::from_secs(30);

/// Interpret the opaque handle. `0` is always invalid.
///
/// Safe on purpose: the handle is produced only by `nativeCreateEngine` via
/// `Box::into_raw` and destroyed exactly once by `nativeDestroyEngine`, so
/// the returned reference is valid for the duration of the call. It is
/// `'static` so it does not borrow the `JNIEnv` (which needs `&mut`).
fn handle_from<'a>(_env: &JNIEnv<'a>, h: jlong) -> Option<&'static mut EngineHandle> {
    if h == 0 {
        return None;
    }
    Some(unsafe { &mut *(h as *mut EngineHandle) })
}

fn jstr(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s)
        .map(|j| j.to_str().unwrap_or_default().to_string())
        .unwrap_or_default()
}

fn new_jstring(env: &JNIEnv, s: &str) -> jstring {
    env.new_string(s)
        .map(|j| j.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn throw(env: &mut JNIEnv, msg: &str) {
    let _ = env.throw_new("java/lang/IllegalStateException", msg);
}

// ---------------------------------------------------------------------------
// lifecycle
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeCreateEngine(
    mut env: JNIEnv,
    _class: JClass,
    config_json: JString,
    save_dir: JString,
) -> jlong {
    let cfg = jstr(&mut env, &config_json);
    let dir = jstr(&mut env, &save_dir);
    let logs = crate::host::LogBuffer::default();
    match crate::engine::spawn_engine(&cfg, &dir, logs) {
        Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
        Err(e) => {
            throw(&mut env, &format!("nativeCreateEngine: {e}"));
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeDestroyEngine(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // Safety: paired with nativeCreateEngine's Box::into_raw.
    let h = unsafe { Box::from_raw(handle as *mut EngineHandle) };
    h.shutdown();
    drop(h);
}

// ---------------------------------------------------------------------------
// torrents
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeAddTorrent(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    data: JByteArray,
    save_dir: JString,
) -> jstring {
    let h = match handle_from(&env, handle) {
        Some(h) => h,
        None => return std::ptr::null_mut(),
    };
    let bytes = env.convert_byte_array(&data).unwrap_or_default();
    let dir = jstr(&mut env, &save_dir);
    let (tx, rx) = channel();
    match h.request(Cmd::AddTorrent { data: bytes, save_dir: dir, tx }, rx, REPLY_TIMEOUT) {
        Some(Ok(hex)) => new_jstring(&env, &hex),
        _ => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeAddMagnet(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    uri: JString,
    save_dir: JString,
) -> jstring {
    let h = match handle_from(&env, handle) {
        Some(h) => h,
        None => return std::ptr::null_mut(),
    };
    let uri = jstr(&mut env, &uri);
    let dir = jstr(&mut env, &save_dir);
    let (tx, rx) = channel();
    match h.request(Cmd::AddMagnet { uri, save_dir: dir, tx }, rx, REPLY_TIMEOUT) {
        Some(Ok(hex)) => new_jstring(&env, &hex),
        _ => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    hash: JString,
) -> jint {
    let h = match handle_from(&env, handle) {
        Some(h) => h,
        None => return -4,
    };
    let hash = jstr(&mut env, &hash);
    let (tx, rx) = channel();
    match h.request(Cmd::Start { hash, tx }, rx, REPLY_TIMEOUT) {
        Some(Ok(())) => 0,
        Some(Err(_)) => -1,
        None => -2,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativePause(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    hash: JString,
) -> jint {
    let h = match handle_from(&env, handle) {
        Some(h) => h,
        None => return -4,
    };
    let hash = jstr(&mut env, &hash);
    h.send(Cmd::Pause { hash });
    0
}

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeResume(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    hash: JString,
) -> jint {
    let h = match handle_from(&env, handle) {
        Some(h) => h,
        None => return -4,
    };
    let hash = jstr(&mut env, &hash);
    h.send(Cmd::Resume { hash });
    0
}

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeRemove(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    hash: JString,
) -> jint {
    let h = match handle_from(&env, handle) {
        Some(h) => h,
        None => return -4,
    };
    let hash = jstr(&mut env, &hash);
    let (tx, rx) = channel();
    match h.request(Cmd::Remove { hash, tx }, rx, REPLY_TIMEOUT) {
        Some(Ok(())) => 0,
        Some(Err(_)) => -1,
        None => -2,
    }
}

// ---------------------------------------------------------------------------
// per-torrent queries
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeProgress(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    hash: JString,
) -> jdouble {
    let h = match handle_from(&env, handle) {
        Some(h) => h,
        None => return 0.0,
    };
    let hash = jstr(&mut env, &hash);
    let (tx, rx) = channel();
    h.request(Cmd::Progress { hash, tx }, rx, REPLY_TIMEOUT).unwrap_or(0.0)
}

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeDownloaded(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    hash: JString,
) -> jlong {
    let h = match handle_from(&env, handle) {
        Some(h) => h,
        None => return 0,
    };
    let hash = jstr(&mut env, &hash);
    let (tx, rx) = channel();
    h.request(Cmd::Downloaded { hash, tx }, rx, REPLY_TIMEOUT).unwrap_or(0) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeIsComplete(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    hash: JString,
) -> jboolean {
    let h = match handle_from(&env, handle) {
        Some(h) => h,
        None => return 0,
    };
    let hash = jstr(&mut env, &hash);
    let (tx, rx) = channel();
    if h.request(Cmd::IsComplete { hash, tx }, rx, REPLY_TIMEOUT).unwrap_or(false) {
        1
    } else {
        0
    }
}

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeTorrentInfo(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    hash: JString,
) -> jstring {
    let h = match handle_from(&env, handle) {
        Some(h) => h,
        None => return std::ptr::null_mut(),
    };
    let hash = jstr(&mut env, &hash);
    let (tx, rx) = channel();
    match h.request(Cmd::TorrentInfo { hash, tx }, rx, REPLY_TIMEOUT) {
        Some(Some(json)) => new_jstring(&env, &json),
        _ => std::ptr::null_mut(),
    }
}

/// All torrents' persisted state (have counts, paused flags) as JSON.
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeTorrentStates(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let h = match handle_from(&env, handle) {
        Some(h) => h,
        None => return new_jstring(&env, "[]"),
    };
    let (tx, rx) = channel();
    let json = h
        .request(Cmd::TorrentStates { tx }, rx, REPLY_TIMEOUT)
        .unwrap_or_else(|| "[]".to_string());
    new_jstring(&env, &json)
}

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeTorrentCount(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    let h = match handle_from(&env, handle) {
        Some(h) => h,
        None => return 0,
    };
    let (tx, rx) = channel();
    h.request(Cmd::TorrentCount { tx }, rx, REPLY_TIMEOUT).unwrap_or(0) as jint
}

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeDhtNodeCount(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    let h = match handle_from(&env, handle) {
        Some(h) => h,
        None => return 0,
    };
    let (tx, rx) = channel();
    h.request(Cmd::DhtCount { tx }, rx, REPLY_TIMEOUT).unwrap_or(0) as jint
}

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativePeerId(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let h = match handle_from(&env, handle) {
        Some(h) => h,
        None => return new_jstring(&env, ""),
    };
    let (tx, rx) = channel();
    let pid = h
        .request(Cmd::PeerId { tx }, rx, REPLY_TIMEOUT)
        .unwrap_or_default();
    new_jstring(&env, &pid)
}
/// Global wire counters as `{"d":down_total,"u":up_total}`.
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeTotals(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let h = match handle_from(&env, handle) {
        Some(h) => h,
        None => return new_jstring(&env, "{\"d\":0,\"u\":0}"),
    };
    let (tx, rx) = channel();
    let (d, u) = h
        .request(Cmd::Totals { tx }, rx, REPLY_TIMEOUT)
        .unwrap_or((0, 0));
    let json = format!("{{\"d\":{d},\"u\":{u}}}");
    new_jstring(&env, &json)
}
// ---------------------------------------------------------------------------
// configuration
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeSetGlobalLimits(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    down: jlong,
    up: jlong,
) -> jint {
    let h = match handle_from(&env, handle) {
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
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    config_json: JString,
) -> jint {
    let h = match handle_from(&env, handle) {
        Some(h) => h,
        None => return -4,
    };
    let json = jstr(&mut env, &config_json);
    match parse_session_config(&json) {
        Ok(cfg) => {
            h.send(Cmd::SetSessionConfig { cfg });
            0
        }
        Err(e) => {
            throw(&mut env, &format!("nativeSetSessionConfig: {e}"));
            -1
        }
    }
}

// ---------------------------------------------------------------------------
// persistence
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeSaveState(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jbyteArray {
    let h = match handle_from(&env, handle) {
        Some(h) => h,
        None => return std::ptr::null_mut(),
    };
    let (tx, rx) = channel();
    match h.request(Cmd::SaveState { tx }, rx, REPLY_TIMEOUT) {
        Some(Some(bytes)) => env
            .byte_array_from_slice(&bytes)
            .map(|a| a.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        _ => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeLoadState(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    data: JByteArray,
) -> jint {
    let h = match handle_from(&env, handle) {
        Some(h) => h,
        None => return -4,
    };
    let bytes = env.convert_byte_array(&data).unwrap_or_default();
    h.send(Cmd::LoadState { data: bytes });
    0
}

// ---------------------------------------------------------------------------
// polling
// ---------------------------------------------------------------------------

/// Drain all pending events and return them as one JSON array.
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeTakeEvents(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let h = match handle_from(&env, handle) {
        Some(h) => h,
        None => return new_jstring(&env, "[]"),
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
    new_jstring(&env, &out)
}

/// Drain all pending log lines as `[{"l":level,"m":"msg"},…]`.
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeTakeLogs(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let h = match handle_from(&env, handle) {
        Some(h) => h,
        None => return new_jstring(&env, "[]"),
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
    new_jstring(&env, &out)
}
