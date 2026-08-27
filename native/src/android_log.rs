//! Direct Android logcat output for engine diagnostics.
//!
//! The engine's normal logs go to an in-memory buffer that only the app's UI
//! reads, so a crash/leak that happens before the UI is up is invisible.
//! This module prints straight to logcat (`adb logcat -s typebit_native`),
//! which survives process death and needs no app cooperation.
//!
//! On non-Android targets (desktop/tests) the same diagnostics are appended
//! to a persistent file `<temp>/typebit_native.log` — otherwise the panic
//! location and backtrace that `engine.rs` logs here would be silently lost,
//! making recovered panics impossible to diagnose on desktop.

/// Path of the desktop diagnostics log; lazily resolved so the temp dir is
/// only queried on first use. Writes use `O_APPEND`, which is atomic per
/// line on all supported platforms, so no cross-thread lock is needed.
///
/// `#[allow(dead_code)]`: on Android the desktop branch of [`log`] is
/// compiled out and this helper is never called.
#[allow(dead_code)]
fn log_path() -> Option<std::path::PathBuf> {
    #[cfg(not(target_os = "android"))]
    {
        Some(std::env::temp_dir().join("typebit_native.log"))
    }
    #[cfg(target_os = "android")]
    {
        let _ = ();
        None
    }
}

pub fn log(msg: &str) {
    #[cfg(target_os = "android")]
    {
        use std::ffi::CString;
        use std::os::raw::{c_char, c_int};

        const ANDROID_LOG_ERROR: c_int = 6;

        #[link(name = "log")]
        extern "C" {
            fn __android_log_print(
                prio: c_int,
                tag: *const c_char,
                fmt: *const c_char,
                ...
            ) -> c_int;
        }

        let tag = CString::new("typebit_native").unwrap_or_default();
        let fmt = CString::new("%s").unwrap_or_default();
        let body = CString::new(msg).unwrap_or_default();
        unsafe {
            __android_log_print(ANDROID_LOG_ERROR, tag.as_ptr(), fmt.as_ptr(), body.as_ptr());
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        use std::io::Write;
        let Some(path) = log_path() else { return };
        let Ok(mut f) = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(&path)
        else {
            return;
        };
        // Ignore write errors: diagnostics must never take the engine down.
        let _ = writeln!(f, "{msg}");
    }
}
