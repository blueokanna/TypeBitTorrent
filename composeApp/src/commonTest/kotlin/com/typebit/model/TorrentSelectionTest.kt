package com.typebit.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** Regression tests for selective-download display math (the "为什么还是 9 个 G" bug). */
class TorrentSelectionTest {

    private fun torrent(files: List<FileEntry>, priorities: List<Int>): Torrent {
        val total = files.sumOf { it.length }
        return Torrent(
                hash = "h",
                name = "t",
                saveDir = "/tmp",
                status = TorrentStatus.DOWNLOADING,
                sizeBytes = total,
                downloadedBytes = 0L,
                uploadedBytes = 0L,
                progress = 0.0,
                pieceCount = 0,
                havePieces = 0,
                pieceLength = 0L,
                isPrivate = false,
                metadataReady = true,
                addedAt = 0L,
                createdAt = null,
                createdBy = null,
                comment = null,
                kind = "FILE",
                trackers = emptyList(),
                files = files,
                seeds = 0,
                peers = 0,
                downSpeed = 0L,
                upSpeed = 0L,
                completedAt = null,
                category = "",
                tags = emptyList(),
                filePriorities = priorities,
        )
    }

    @Test
    fun selectedBytes_equals_total_when_all_selected() {
        val t = torrent(
                files = listOf(FileEntry(listOf("a.bin"), 4_000_000_000L), FileEntry(listOf("b.bin"), 5_000_000_000L)),
                priorities = listOf(1, 1),
        )
        assertEquals(9_000_000_000L, t.sizeBytes)
        assertEquals(9_000_000_000L, t.selectedBytes, "all Normal → target is the full torrent")
    }

    @Test
    fun selectedBytes_counts_only_nonSkipped_files() {
        // The exact user scenario: a 9.21 GiB torrent where only one 4.83 GiB
        // file is checked — the download target must be ~4.83 GiB, not 9.21.
        val t = torrent(
                files = listOf(
                        FileEntry(listOf("f1.mp4"), 4_350_000_000L),
                        FileEntry(listOf("f2.mp4"), 4_830_000_000L), // selected
                        FileEntry(listOf("f3.txt"), 13_000_000L),
                        FileEntry(listOf("f4.txt"), 136L),
                        FileEntry(listOf("f5.txt"), 14_000_000L),
                        FileEntry(listOf("f6.txt"), 145L),
                ),
                priorities = listOf(0, 1, 0, 0, 0, 0),
        )
        assertEquals(4_830_000_000L, t.selectedBytes)
        assertEquals(9_207_000_281L, t.sizeBytes)
        assertEquals(4_830_000_000L, t.selectedBytes.coerceAtMost(t.sizeBytes))
    }

    @Test
    fun selectedBytes_falls_back_to_total_when_priorities_empty() {
        val t = torrent(
                files = listOf(FileEntry(listOf("a.bin"), 100L), FileEntry(listOf("b.bin"), 200L)),
                priorities = emptyList(), // e.g. search one-tap add → full download
        )
        assertEquals(300L, t.selectedBytes)
    }

    @Test
    fun selectedBytes_handles_short_priority_list() {
        val t = torrent(
                files = listOf(FileEntry(listOf("a.bin"), 100L), FileEntry(listOf("b.bin"), 200L)),
                priorities = listOf(0), // truncated → missing entries default to Normal
        )
        assertEquals(200L, t.selectedBytes)
    }
}
