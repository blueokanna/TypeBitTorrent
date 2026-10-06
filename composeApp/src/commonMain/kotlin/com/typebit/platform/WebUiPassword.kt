package com.typebit.platform

/**
 * WebUI credential hashing, shared by every target (the phone app hashes too —
 * the same settings file can be carried to a NAS).
 *
 * Storage format: `pbkdf2$<iterations>$<base64url-salt>$<base64url-hash>`
 * (PBKDF2-HMAC-SHA256, 120 000 iterations). Plaintext passwords are never
 * written anywhere, and the comparison is constant-time.
 */
expect fun hashWebUiPassword(password: String): String

/** Constant-time verification of [password] against a stored hash. */
expect fun verifyWebUiPassword(password: String, stored: String): Boolean

/** True when [password] is long enough to be a WebUI credential. */
fun isAcceptableWebUiPassword(password: String): Boolean = password.length >= 8
