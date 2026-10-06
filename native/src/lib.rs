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
pub mod firewall;
pub mod host;
pub mod jni_glue;
pub mod json;
pub mod make_torrent;
pub mod meta;

use jni::sys::{jint, JNI_VERSION_1_6};

/// ABI revision of the Kotlin ↔ Rust JNI surface.
///
/// **Bump this whenever ANY `actual external fun` signature or JSON contract in
/// `NativeBridge.kt` changes.** The Kotlin side compares it against its own
/// expected value before touching the engine and refuses to run against a
/// stale library — because a mismatched signature does not fail loudly, it
/// makes the JNI call read its arguments from the wrong registers/slots and
/// SIGSEGVs the whole process (the app "闪退"). Shipping an APK whose
/// `jniLibs/*.so` predates a signature change is exactly how that happens.
pub const JNI_ABI: jint = 2;

/// Reports the ABI revision above; called once per engine start.
#[no_mangle]
pub extern "system" fn Java_com_typebit_engine_NativeBridgeKt_nativeBridgeAbi(
    _unowned: jni::EnvUnowned,
    _class: jni::objects::JClass,
) -> jint {
    JNI_ABI
}

/// Standard JNI entry point — validates the JVM wants a compatible ABI.
/// `jni::sys::JavaVM` is the FFI-safe `#[repr(C)]` JNI type; the high-level
/// `jni::JavaVM` wrapper is not FFI-safe and trips
/// `improper_ctypes_definitions`.
#[no_mangle]
pub extern "system" fn JNI_OnLoad(
    _vm: *mut jni::sys::JavaVM,
    _reserved: *mut std::ffi::c_void,
) -> jint {
    JNI_VERSION_1_6
}

/// No-op so `JNI_OnLoad` never appears dead to the linker in release builds.
#[no_mangle]
pub extern "system" fn JNI_OnUnload(_vm: *mut jni::sys::JavaVM, _reserved: *mut std::ffi::c_void) {}
