package com.typebit.webui

import com.typebit.platform.hashWebUiPassword
import com.typebit.platform.verifyWebUiPassword
import java.security.SecureRandom
import java.util.Base64

/**
 * WebUI password hashing, 256-bit in-memory session tokens (never derived from the password, behind an
 * `HttpOnly; SameSite=Strict` cookie) and per-address login throttling. Plaintext is never stored or logged.
 */
internal object WebUiCrypto {
    private val random = SecureRandom()
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    fun hash(password: String): String = hashWebUiPassword(password)

    /** Constant-time comparison against a stored `pbkdf2$…` value. */
    fun verify(password: String, stored: String): Boolean =
        verifyWebUiPassword(password, stored)

    fun newToken(): String {
        val raw = ByteArray(32).also { random.nextBytes(it) }
        return encoder.encodeToString(raw)
    }
}

internal class Session(val token: String, val username: String, val expiresAt: Long)

// Sessions and failure counts are bounded on purpose: entries only appear after a real login or attempt,
// and the failure map is pruned on every attempt.
internal class WebUiSessions(
    private val timeoutMinutes: Long,
    private val maxFailures: Int,
    private val banSeconds: Long,
) {
    private val sessions = HashMap<String, Session>()
    private val failures = HashMap<String, Int>()
    private val bannedUntil = HashMap<String, Long>()

    fun banRemainingSeconds(client: String, now: Long): Long =
        ((bannedUntil[client] ?: 0L) - now).coerceAtLeast(0L) / 1000L

    fun isBanned(client: String, now: Long): Boolean = (bannedUntil[client] ?: 0L) > now

    fun recordFailure(client: String, now: Long): Long {
        val count = (failures[client] ?: 0) + 1
        failures[client] = count
        prune(now)
        if (count < maxFailures.coerceAtLeast(1)) return 0L
        failures.remove(client)
        bannedUntil[client] = now + banSeconds * 1000
        return banSeconds
    }

    fun clearFailures(client: String) {
        failures.remove(client)
    }

    fun open(username: String, now: Long): Session {
        prune(now)
        val token = WebUiCrypto.newToken()
        val session = Session(token, username, now + timeoutMinutes.coerceAtLeast(1) * 60_000)
        sessions[token] = session
        return session
    }

    fun resolve(token: String?, now: Long): Session? {
        val t = token?.takeIf { it.isNotEmpty() } ?: return null
        val s = sessions[t] ?: return null
        if (s.expiresAt <= now) {
            sessions.remove(t)
            return null
        }
        return s
    }

    fun close(token: String?) {
        token?.let { sessions.remove(it) }
    }

    fun closeAll() = sessions.clear()

    private fun prune(now: Long) {
        if (sessions.size > 64) {
            val it = sessions.values.iterator()
            while (it.hasNext()) if (it.next().expiresAt <= now) it.remove()
        }
        if (failures.size > 256) {
            // Only reachable with a burst of distinct source addresses.
            val keep = failures.entries.sortedByDescending { it.value }.take(64)
            failures.clear()
            keep.forEach { (k, v) -> failures[k] = v }
        }
        if (bannedUntil.size > 256) bannedUntil.entries.removeIf { it.value <= now }
    }
}
