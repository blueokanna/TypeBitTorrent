//! typebit_native — the JNI bridge embedding the TypeBit BitTorrent engine.
//!
//! Builds a `cdylib` loadable from both Android (ART) and JVM desktop
//! (Compose Desktop). The engine runs on a dedicated Rust thread; Kotlin
//! submits commands and polls events through the functions in `jni_glue`.
//!
//! Layout:
//! * [`host`]  — a complete std `typebit::Host` (sockets, UDP, HTTP, disk).
//! * [`engine`] — the worker thread, command protocol and config parsing.
//! * [`meta`]  — add-time metadata mirror (the 0.1.0 engine has no getters).
//! * [`json`]  — minimal JSON writer for the JNI surface.

pub mod engine;
pub mod host;
pub mod jni_glue;
pub mod json;
pub mod leech;
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

#[cfg(test)]
mod tests {
    /// Builds a minimal single-file torrent with typebit's own bencode
    /// helpers and verifies it round-trips through `Torrent::from_bytes`.
    #[test]
    fn parses_minimal_torrent() {
        let piece = [0x5Au8; 16 * 1024];
        let info = typebit::bencode::dict(vec![
            (b"name".as_slice(), typebit::bencode::bytes("smoke.bin")),
            (b"piece length".as_slice(), typebit::bencode::int(16 * 1024)),
            (
                b"length".as_slice(),
                typebit::bencode::int(piece.len() as i64),
            ),
            (
                b"pieces".as_slice(),
                typebit::bencode::bytes(typebit::crypto::Sha1::digest(&piece)),
            ),
        ]);
        let root = typebit::bencode::dict(vec![(b"info".as_slice(), info)]);
        let bytes = typebit::bencode::encode_to_vec(&root);

        let t = typebit::metainfo::Torrent::from_bytes(&bytes).expect("minimal torrent must parse");
        assert_eq!(t.name, "smoke.bin");
        assert_eq!(t.piece_count(), 1);
        assert_eq!(t.total_size, 16 * 1024);
        assert_eq!(t.info_hash.to_hex().len(), 40);
    }
}
