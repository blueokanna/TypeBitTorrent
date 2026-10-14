package com.typebit.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.typebit.ui.screens.settings.CompactDropdown
import com.typebit.ui.util.Format

/**
 * qBittorrent-style file tree: nested folders are rebuilt from flat file paths
 * (see [buildFileTree]); selection/priority state stays with the caller.
 */

/** One leaf file, keyed by its position in the torrent's flat file list. */
data class TreeLeaf(
    val index: Int,
    val path: List<String>,
    val length: Long,
)

class FileTreeNode(
    /** Path key: `d:<path>` for directories, `f:<index>` for files. */
    val key: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val children: List<FileTreeNode>,
    /** Flat-file index; `null` for directories. */
    val leafIndex: Int?,
    val depth: Int,
    /** Every descendant leaf index (self included for files). */
    val leafIndices: List<Int>,
)

fun buildFileTree(files: List<TreeLeaf>): List<FileTreeNode> {
    // Path -> child nodes built so far (directories only). "" is the virtual root.
    val childrenOf = mutableMapOf<String, MutableList<Pair<String, String>>>()
    val dirsSeen = mutableSetOf<String>()

    fun ensureDir(dirKey: String, name: String, parentKey: String) {
        if (dirKey in dirsSeen) return
        dirsSeen.add(dirKey)
        childrenOf.getOrPut(parentKey) { mutableListOf() }.add(dirKey to name)
    }

    for (f in files) {
        val segs = f.path.filter { it.isNotEmpty() }
        if (segs.isEmpty()) {
            childrenOf.getOrPut("") { mutableListOf() }
                .add("f:${f.index}" to f.path.joinToString("/"))
            continue
        }
        var parentKey = ""
        // Children buckets are keyed by the NODE key ("d:<path>"), not the bare path.
        var parentDirKey = ""
        for (i in 0 until segs.lastIndex) {
            val seg = segs[i]
            val key = if (parentKey.isEmpty()) seg else "$parentKey/$seg"
            ensureDir("d:$key", seg, parentDirKey)
            parentKey = key
            parentDirKey = "d:$key"
        }
        val leafKey = "f:${f.index}"
        // Root-level files must land in the "" bucket; bucketing them as "d:" emptied the whole tree.
        val bucket = if (parentKey.isEmpty()) "" else "d:$parentKey"
        childrenOf.getOrPut(bucket) { mutableListOf() }
            .add(leafKey to segs.last())
    }

    val fileByIndex = files.associateBy { it.index }

    fun build(key: String, name: String, isDir: Boolean, depth: Int): FileTreeNode {
        if (!isDir) {
            val f = fileByIndex[key.removePrefix("f:").toIntOrNull() ?: -1]
            return FileTreeNode(
                key = key,
                name = name,
                isDir = false,
                size = f?.length ?: 0L,
                children = emptyList(),
                leafIndex = f?.index,
                depth = depth,
                leafIndices = listOfNotNull(f?.index),
            )
        }
        val kids = childrenOf[key] ?: emptyList()
        val built = kids.map { (childKey, childName) ->
            build(childKey, childName, childKey.startsWith("d:"), depth + 1)
        }
        val size = built.sumOf { it.size }
        val leaves = built.flatMap { it.leafIndices }
        return FileTreeNode(
            key = key,
            name = name,
            isDir = true,
            size = size,
            children = built,
            leafIndex = null,
            depth = depth,
            leafIndices = leaves,
        )
    }

    val rootEntries = (childrenOf[""] ?: emptyList())
        .sortedWith(compareBy({ !it.second.startsWith("d:") }, { it.first.lowercase() }))
    return rootEntries.map { (childKey, childName) ->
        build(childKey, childName, childKey.startsWith("d:"), 0)
    }
}

// Engine priority values: 0 = skip, 1 = normal, 2 = high.

val TREE_PRIORITY_LABELS = listOf("不下载", "正常", "高优先级")

val TREE_PRIORITY_OPTIONS = listOf(0, 1, 2)

const val MIXED_PRIORITY_LABEL = "混合"

private enum class Tri { UNCHECKED, CHECKED, MIXED }

@Composable
fun FileTreeView(
    roots: List<FileTreeNode>,
    isSelected: (Int) -> Boolean,
    priority: (Int) -> Int,
    onToggleLeaf: (Int, Boolean) -> Unit,
    onToggleDir: (String, Boolean) -> Unit,
    onPriorityLeaf: (Int, Int) -> Unit,
    onPriorityDir: (String, Int) -> Unit,
    onRename: ((Int) -> Unit)? = null,
    filter: String = "",
    showSelection: Boolean = true,
    onPreview: ((Int) -> Unit)? = null,
    isVideo: ((Int) -> Boolean)? = null,
    modifier: Modifier = Modifier,
) {
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val q = filter.trim()
    // `remember` cannot observe reads made inside its own calculation: keying it on a
    // content snapshot (Map.equals, not identity) is what makes toggles recompute the flatten.
    val expandedSnapshot = expanded.toMap()
    val visible = remember(roots, q, expandedSnapshot) {
        collectVisible(roots, q, expandedSnapshot)
    }
    // Duplicate keys crash LazyColumn; a malformed file list must degrade to a
    // merged view rather than killing the app.
    val rows = remember(visible) { visible.distinctBy { it.key } }

    LazyColumn(modifier.fillMaxWidth()) {
        items(rows, key = { it.key }) { node ->
            val open = expanded[node.key] ?: true
            val state = if (node.isDir) {
                val sel = node.leafIndices.map(isSelected)
                when {
                    sel.isEmpty() || sel.none { it } -> Tri.UNCHECKED
                    sel.all { it } -> Tri.CHECKED
                    else -> Tri.MIXED
                }
            } else if (isSelected(node.leafIndex ?: -1)) Tri.CHECKED else Tri.UNCHECKED

            val prio = if (node.isDir) {
                val uniq = node.leafIndices.map(priority).toSet()
                if (uniq.size == 1) uniq.first() else null
            } else priority(node.leafIndex ?: 1)

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = (node.depth * 18).dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (node.isDir) {
                    IconButton(onClick = { expanded[node.key] = !open },
                        modifier = Modifier.width(28.dp)) {
                        Icon(
                            if (open) Icons.Filled.ArrowDropDown
                            else Icons.AutoMirrored.Filled.ArrowRight,
                            contentDescription = if (open) "折叠" else "展开",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Spacer(Modifier.width(28.dp))
                }
                Row(
                    Modifier.weight(1f).clickable {
                        val idx = node.leafIndex ?: -1
                        when {
                            node.isDir -> expanded[node.key] = !open
                            showSelection -> onToggleLeaf(idx, !isSelected(idx))
                            onPreview != null && isVideo?.invoke(idx) == true -> onPreview(idx)
                        }
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showSelection) {
                        Checkbox(
                            checked = state != Tri.UNCHECKED,
                            onCheckedChange = {
                                val newSel = state != Tri.CHECKED
                                if (node.isDir) onToggleDir(node.key, newSel)
                                else onToggleLeaf(node.leafIndex ?: -1, newSel)
                            },
                        )
                    }
                    Icon(
                        when {
                            node.isDir && open -> Icons.Default.FolderOpen
                            node.isDir -> Icons.Default.Folder
                            else -> Icons.AutoMirrored.Filled.InsertDriveFile
                        },
                        contentDescription = null,
                        tint = if (node.isDir) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            node.name,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            Format.bytes(node.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!node.isDir && onPreview != null &&
                    isVideo?.invoke(node.leafIndex ?: -1) == true
                ) {
                    IconButton(onClick = { node.leafIndex?.let(onPreview) },
                        modifier = Modifier.width(28.dp)) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "边下边播",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (onRename != null && !node.isDir) {
                    IconButton(onClick = { node.leafIndex?.let(onRename) },
                        modifier = Modifier.width(28.dp)) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "重命名",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (node.isDir) {
                    Text(
                        prio?.let { TREE_PRIORITY_LABELS[it.coerceIn(0, 2)] } ?: MIXED_PRIORITY_LABEL,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(64.dp),
                    )
                } else {
                    CompactDropdown(
                        options = TREE_PRIORITY_OPTIONS,
                        selected = (prio ?: 1).coerceIn(0, 2),
                        onSelect = { onPriorityLeaf(node.leafIndex ?: -1, it) },
                        labelOf = { TREE_PRIORITY_LABELS[it.coerceIn(0, 2)] },
                    )
                }
            }
        }
    }
}

private fun collectVisible(
    nodes: List<FileTreeNode>,
    q: String,
    expanded: Map<String, Boolean>,
): List<FileTreeNode> {
    val out = mutableListOf<FileTreeNode>()
    fun walk(list: List<FileTreeNode>) {
        for (n in list) {
            val match = q.isEmpty() || n.name.contains(q, ignoreCase = true)
            if (match) {
                out.add(n)
                if (n.isDir && (expanded[n.key] ?: true)) walk(n.children)
            } else if (n.isDir && subtreeMatches(n, q)) {
                walk(n.children)
            }
        }
    }
    walk(nodes)
    return out
}

private fun subtreeMatches(node: FileTreeNode, q: String): Boolean =
    node.children.any { it.name.contains(q, ignoreCase = true) || subtreeMatches(it, q) }

fun findNodeByKey(roots: List<FileTreeNode>, key: String): FileTreeNode? {
    for (r in roots) {
        if (r.key == key) return r
        findNodeByKey(r.children, key)?.let { return it }
    }
    return null
}
