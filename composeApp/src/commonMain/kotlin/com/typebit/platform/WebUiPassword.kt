package com.typebit.platform

// Storage format `pbkdf2$<iterations>$<base64url-salt>$<base64url-hash>` (PBKDF2-HMAC-SHA256, 120 000
// iterations). Plaintext passwords are never written, and comparison is constant-time.
expect fun hashWebUiPassword(password: String): String

expect fun verifyWebUiPassword(password: String, stored: String): Boolean

fun isAcceptableWebUiPassword(password: String): Boolean = password.length >= 8
