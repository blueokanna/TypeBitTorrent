package com.typebit.platform

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * JVM actual (Android + desktop): PBKDF2-HMAC-SHA256 via `javax.crypto`.
 *
 * No third-party dependency, no hand-rolled KDF, and no plaintext storage:
 * iteration count and salt live in the hash string so parameters can be raised
 * later without invalidating existing credentials.
 */
private const val ALGORITHM = "PBKDF2WithHmacSHA256"
private const val ITERATIONS = 120_000
private const val KEY_BITS = 256
private const val SALT_BYTES = 16

private val random = SecureRandom()
private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
private val decoder: Base64.Decoder = Base64.getUrlDecoder()

actual fun hashWebUiPassword(password: String): String {
    val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
    val hash = derive(password, salt, ITERATIONS)
    return "pbkdf2\$$ITERATIONS\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(hash)}"
}

actual fun verifyWebUiPassword(password: String, stored: String): Boolean {
    val parts = stored.split('$')
    if (parts.size != 4 || parts[0] != "pbkdf2") return false
    val iterations = parts[1].toIntOrNull() ?: return false
    val salt = runCatching { decoder.decode(parts[2]) }.getOrNull() ?: return false
    val expected = runCatching { decoder.decode(parts[3]) }.getOrNull() ?: return false
    if (iterations <= 0 || salt.isEmpty() || expected.isEmpty()) return false
    return MessageDigest.isEqual(derive(password, salt, iterations), expected)
}

private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
    val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
    return try {
        SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}
