package com.typebit.platform

/**
 * Shares plain text through the platform's share mechanism.
 *
 * * Android — system share sheet (`ACTION_SEND`, `EXTRA_TEXT`), so the user
 *   can hand a magnet link to any messaging app.
 * * Desktop — copies the text to the system clipboard and shows a brief
 *   status, since there is no OS share sheet on Windows/Linux/macOS.
 *
 * Never throws; failures (no activity, clipboard blocked) are swallowed.
 */
expect fun shareText(subject: String, text: String)
