package com.typebit.platform

/**
 * Human-readable date/time rendering. The actual uses `java.text` (present on
 * both Android and desktop JVM), so no extra dependency is needed.
 */
expect fun formatDateTime(epochMillis: Long): String
