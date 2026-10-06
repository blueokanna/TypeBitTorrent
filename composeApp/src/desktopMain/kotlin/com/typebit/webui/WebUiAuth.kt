package com.typebit.webui

import com.typebit.platform.hashWebUiPassword
import com.typebit.platform.verifyWebUiPassword
import java.security.SecureRandom
import java.util.Base64

/**
 * Password hashing, sessions and brute-force throttling for the built-in WebUI.
 *
 * Design notes:
 * * Password hashing lives in [com.typebit.platform.hashWebUiPassword] so the
 *   desktop UI, the phone UI and the server all write the exact same
 *   `pbkdf2$…` value — plaintext is never stored or logged, and verification is
 *   constant-time.
 * * Sessions are 256-bit random tokens kept in memory only — a restart logs
 *   everyone out (acceptable, and it means no session secret has to live on
 *   disk). The cookie is `HttpOnly; SameSite=Strict`, so a browser never
 *   exposes it to scripts or cross-site requests.
 * * Failed logins are counted per client address; too many in a row bans that
 *   address for the configured duration (qBittorrent-style), so a NAS exposed
 *   on a LAN cannot be brute-forced at speed.
 */
internal object WebUiCrypto {
    private val random = SecureRandom()
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    /** Hashes a password into the storable `pbkdf2$…` form. */
    fun hash(password: String): String = hashWebUiPassword(password)

    /** True when [password] matches a stored `pbkdf2$…` value (constant time). */
    fun verify(password: String, stored: String): Boolean =
        verifyWebUiPassword(password, stored)

    /** A fresh 256-bit session token (URL-safe, no padding). */
    fun newToken(): String {
        val raw = ByteArray(32).also { random.nextBytes(it) }
        return encoder.encodeToString(raw)
    }
}

/** One authenticated browser session. */
internal class Session(val token: String, val username: String, val expiresAt: Long)

/**
 * In-memory session table + per-address login throttling.
 *
 * Bounded on purpose: an unauthenticated caller cannot grow the maps without
 * bound (a session only appears after a successful login, and the failure map
 * is pruned on every attempt).
 */
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

    /** Records a failed attempt; returns the ban duration when it just triggered. */
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
