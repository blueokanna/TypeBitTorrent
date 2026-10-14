package com.typebit.platform

// Shares plain text: Android through the system share sheet, desktop through the clipboard. Never throws;
// failures (no activity, clipboard blocked) are swallowed.
expect fun shareText(subject: String, text: String)
