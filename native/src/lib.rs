//! typebit_native — the JNI bridge embedding the TypeBit BitTorrent engine.
//!
//! Builds a `cdylib` loadable from both Android (ART) and JVM desktop
//! (Compose Desktop). The engine runs on a dedicated Rust thread; Kotlin
//! submits commands and polls events through the functions in `jni_glue`.
//!
//! Layout:
//! * [`host`]  — a complete std `typebit::Host` (sockets, UDP, HTTP, disk).
//! * [`engine`] — the worker thread, command protocol and config parsing.
//! * [`meta`]  — add-time metadata mirror (the engine exposes no metainfo
//!   getter, so the bridge mirrors name/files/trackers at add time).
//! * [`json`]  — minimal JSON writer for the JNI surface.

pub mod android_log;
pub mod engine;
pub mod host;
pub mod jni_glue;
pub mod json;
pub mod make_torrent;
pub mod meta;

use jni::sys::{jint, JNI_VERSION_1_6};

/// Standard JNI entry point — validates the JVM wants a compatible ABI.
#[no_mangle]
pub extern "system" fn JNI_OnLoad(_vm: jni::JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    JNI_VERSION_1_6
}

/// No-op so `JNI_OnLoad` never appears dead to the linker in release builds.
#[no_mangle]
pub extern "system" fn JNI_OnUnload(_vm: jni::JavaVM, _reserved: *mut std::ffi::c_void) {}
