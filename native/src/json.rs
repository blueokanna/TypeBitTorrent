//! Minimal, dependency-free JSON writer for the JNI surface.
//!
//! We deliberately hand-roll this instead of pulling a serde-style stack:
//! the shapes we emit (engine events, torrent metadata, hashes, stats) are
//! tiny, fixed, and hot — and we want byte-level control over escaping and
//! number formatting with zero allocation beyond the single output buffer.

/// Streaming JSON writer. Callers push tokens in order; the writer owns the
/// buffer and never constructs an intermediate tree.
#[derive(Default)]
pub struct JsonWriter {
    buf: String,
}

impl JsonWriter {
    pub fn new() -> Self {
        JsonWriter { buf: String::new() }
    }

    pub fn as_str(&self) -> &str {
        &self.buf
    }

    pub fn into_string(self) -> String {
        self.buf
    }

    pub fn len(&self) -> usize {
        self.buf.len()
    }

    pub fn is_empty(&self) -> bool {
        self.buf.is_empty()
    }

    // ---- structural tokens ----

    pub fn begin_object(&mut self) {
        self.buf.push('{');
    }

    pub fn end_object(&mut self) {
        self.buf.push('}');
    }

    pub fn begin_array(&mut self) {
        self.buf.push('[');
    }

    pub fn end_array(&mut self) {
        self.buf.push(']');
    }

    pub fn comma(&mut self) {
        self.buf.push(',');
    }

    /// `"key":` — pushes the key as a quoted string followed by a colon.
    pub fn key(&mut self, k: &str) {
        self.string(k);
        self.buf.push(':');
    }

    /// Append a raw, pre-encoded token (e.g. `true`, `null`, a number).
    pub fn raw(&mut self, s: &str) {
        self.buf.push_str(s);
    }

    // ---- values ----

    /// JSON string with full RFC 8259 escaping (incl. control characters).
    pub fn string(&mut self, s: &str) {
        self.buf.push('"');
        for c in s.chars() {
            match c {
                '"' => self.buf.push_str("\\\""),
                '\\' => self.buf.push_str("\\\\"),
                '\n' => self.buf.push_str("\\n"),
                '\r' => self.buf.push_str("\\r"),
                '\t' => self.buf.push_str("\\t"),
                '\u{0008}' => self.buf.push_str("\\b"),
                '\u{000C}' => self.buf.push_str("\\f"),
                c if (c as u32) < 0x20 => {
                    // \uXXXX for the remaining C0 controls
                    use core::fmt::Write;
                    let _ = write!(self.buf, "\\u{:04x}", c as u32);
                }
                c => self.buf.push(c),
            }
        }
        self.buf.push('"');
    }

    pub fn u64(&mut self, v: u64) {
        self.buf.push_str(&v.to_string());
    }

    pub fn i64(&mut self, v: i64) {
        self.buf.push_str(&v.to_string());
    }

    /// Numbers must be finite for JSON; NaN/Inf collapse to 0 defensively.
    pub fn f64(&mut self, v: f64) {
        if v.is_finite() {
            self.buf.push_str(&format!("{}", v));
        } else {
            self.buf.push('0');
        }
    }

    pub fn boolean(&mut self, b: bool) {
        self.buf.push_str(if b { "true" } else { "false" });
    }

    pub fn null(&mut self) {
        self.buf.push_str("null");
    }

    /// Convenience: quoted string value for a key inside an object.
    pub fn kv_string(&mut self, key: &str, value: &str) {
        self.key(key);
        self.string(value);
    }

    pub fn kv_u64(&mut self, key: &str, value: u64) {
        self.key(key);
        self.u64(value);
    }

    pub fn kv_i64(&mut self, key: &str, value: i64) {
        self.key(key);
        self.i64(value);
    }

    pub fn kv_f64(&mut self, key: &str, value: f64) {
        self.key(key);
        self.f64(value);
    }

    pub fn kv_bool(&mut self, key: &str, value: bool) {
        self.key(key);
        self.boolean(value);
    }
}
