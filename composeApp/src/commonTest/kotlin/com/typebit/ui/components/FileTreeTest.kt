package com.typebit.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for [buildFileTree] — the tree must render for EVERY
 * path shape, most importantly root-level single-segment files, which used
 * to be bucketed into a phantom `d:` directory and produced an EMPTY tree
 * ("文件树空白，无法选择文件").
 */
class FileTreeTest {

    @Test
    fun rootLevelSingleSegmentFilesProduceRootLeaves() {
        val files =
                listOf(
                        TreeLeaf(0, listOf("[AI增强] 01.mkv"), 4_000_000_000),
                        TreeLeaf(1, listOf("[AI增强] 02.mkv"), 3_500_000_000),
                        TreeLeaf(2, listOf("README.txt"), 128),
                )
        val roots = buildFileTree(files)
        // Three top-level leaves, no phantom directory.
        assertEquals(3, roots.size, "root-level files must be root nodes")
        assertTrue(roots.all { !it.isDir }, "no phantom directory allowed")
        assertEquals(listOf(0, 1, 2), roots.flatMap { it.leafIndices }.sorted())
        assertEquals(4_000_000_000L + 3_500_000_000L + 128L, roots.sumOf { it.size })
    }

    @Test
    fun directoryTreeGroupsChildren() {
        val files =
                listOf(
                        TreeLeaf(0, listOf("movies", "a.mkv"), 100),
                        TreeLeaf(1, listOf("movies", "b.mkv"), 200),
                        TreeLeaf(2, listOf("music", "c.mp3"), 50),
                )
        val roots = buildFileTree(files)
        assertEquals(2, roots.size)
        val movies = roots.first { it.name == "movies" }
        assertTrue(movies.isDir)
        assertEquals(listOf(0, 1), movies.leafIndices.sorted())
        assertEquals(300L, movies.size)
    }

    @Test
    fun emptyPathLeafLandsAtRoot() {
        val files = listOf(TreeLeaf(0, emptyList(), 42))
        val roots = buildFileTree(files)
        assertEquals(1, roots.size)
        assertEquals(listOf(0), roots[0].leafIndices)
    }

    @Test
    fun allLeavesReachableByDepthFirst() {
        // Every leaf must appear exactly once when walking only leaf nodes.
        val files =
                listOf(
                        TreeLeaf(0, listOf("a.mkv"), 1),
                        TreeLeaf(1, listOf("d", "b.mkv"), 2),
                        TreeLeaf(2, listOf("d", "sub", "c.mkv"), 3),
                )
        val roots = buildFileTree(files)
        val leaves = mutableListOf<Int>()
        fun walk(nodes: List<FileTreeNode>) {
            for (n in nodes) {
                if (!n.isDir) leaves += n.leafIndices
                walk(n.children)
            }
        }
        walk(roots)
        assertEquals(listOf(0, 1, 2), leaves.sorted(), "every leaf must be reachable exactly once")
    }
}
