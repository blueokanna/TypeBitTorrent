//! Direct Android logcat output for engine diagnostics.
//!
//! The engine's normal logs go to an in-memory buffer that only the app's UI
//! reads, so a crash/leak that happens before the UI is up is invisible.
//! This module prints straight to logcat (`adb logcat -s typebit_native`),
//! which survives process death and needs no app cooperation.
//!
//! On non-Android targets it is a no-op (kept for the desktop/tests build).

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
        let _ = msg;
    }
}
