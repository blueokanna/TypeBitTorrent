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
 * qBittorrent-style torrent file tree.
 *
 * A torrent's file list is flat, but users think in directories. [buildFileTree]
 * reconstructs the nested structure from each file's path segments, so the add
 * dialog and the detail Files tab can show collapsible folders with aggregate
 * sizes, tri-state selection and "mixed" priorities — exactly like qBittorrent.
 *
 * The view is stateless about *which* files are selected/prioritised; the
 * caller owns `isSelected`/`priority` keyed by the flat file index. Directory
 * rows derive their state from all descendant leaves.
 */

/** One leaf file, keyed by its position in the torrent's flat file list. */
data class TreeLeaf(
    val index: Int,
    val path: List<String>,
    val length: Long,
)

/** A node in the reconstructed file tree (directory or file). */
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

/**
 * Rebuild the nested file tree from a flat list of files.
 *
 * Directories always sort before files, and both sort alphabetically (the
 * qBittorrent layout). Single-file torrents yield one leaf at depth 0.
 */
fun buildFileTree(files: List<TreeLeaf>): List<FileTreeNode> {
    // Path -> child nodes built so far (directories only). "" is the virtual root.
    val childrenOf = mutableMapOf<String, MutableList<Pair<String, String>>>()
    val dirsSeen = mutableSetOf<String>()

    fun ensureDir(dirKey: String, name: String, parentKey: String) {
        if (dirKey in dirsSeen) return
        dirsSeen.add(dirKey)
        childrenOf.getOrPut(parentKey) { mutableListOf() }.add(dirKey to name)
    }

    // Pass 1: register every directory and file into its parent's bucket.
    for (f in files) {
        val segs = f.path.filter { it.isNotEmpty() }
        if (segs.isEmpty()) {
            childrenOf.getOrPut("") { mutableListOf() }
                .add("f:${f.index}" to f.path.joinToString("/"))
            continue
        }
        var parentKey = ""
        // The PARENT'S NODE key ("d:<path>", "" for root). Nested directories
        // must be registered under this bucket — using the bare path ("d")
        // instead orphaned every deeper level (a directory's children bucket
        // is keyed by its NODE key, not its bare path).
        var parentDirKey = ""
        for (i in 0 until segs.lastIndex) {
            val seg = segs[i]
            val key = if (parentKey.isEmpty()) seg else "$parentKey/$seg"
            ensureDir("d:$key", seg, parentDirKey)
            parentKey = key
            parentDirKey = "d:$key"
        }
        val leafKey = "f:${f.index}"
        // A single-segment path (path = ["name"], i.e. a root-level file)
        // leaves parentKey == "". It must land in the ROOT bucket (""), not
        // a phantom "d:" directory — the old code bucketed it as "d:", the
        // root stayed empty, and the whole tree rendered ZERO rows for any
        // torrent whose files live at the top level. This is the root cause
        // of "文件树空白 / 无法选择文件".
        val bucket = if (parentKey.isEmpty()) "" else "d:$parentKey"
        childrenOf.getOrPut(bucket) { mutableListOf() }
            .add(leafKey to segs.last())
    }

    // Pass 2: materialise nodes bottom-up (children before parents).
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

    // Root bucket entries: files first sorted alphabetically, dirs too.
    val rootEntries = (childrenOf[""] ?: emptyList())
        .sortedWith(compareBy({ !it.second.startsWith("d:") }, { it.first.lowercase() }))
    return rootEntries.map { (childKey, childName) ->
        build(childKey, childName, childKey.startsWith("d:"), 0)
    }
}

// ---------------------------------------------------------------------------
// Priority labels (engine values: 0=Skip, 1=Normal, 2=High)
// ---------------------------------------------------------------------------

/** Labels shown in the priority dropdown / directory rows. */
val TREE_PRIORITY_LABELS = listOf("不下载", "正常", "高优先级")

/** Dropdown options (engine priorities). */
val TREE_PRIORITY_OPTIONS = listOf(0, 1, 2)

/** Label for a directory whose children have differing priorities. */
const val MIXED_PRIORITY_LABEL = "混合"

/** Tri-state checkbox. */
private enum class Tri { UNCHECKED, CHECKED, MIXED }

/**
 * qBittorrent-style collapsible file tree.
 *
 * @param roots          root nodes from [buildFileTree].
 * @param isSelected     leaf selection by flat index.
 * @param priority       leaf priority (0/1/2) by flat index.
 * @param onToggleLeaf   (index, newSelected).
 * @param onToggleDir    (dirKey, newSelected) — apply to all descendants.
 * @param onPriorityLeaf (index, priority).
 * @param onPriorityDir  (dirKey, priority) — apply to all descendants.
 * @param onRename       optional rename callback (index) — Files tab only.
 * @param filter         optional substring filter on node names.
 * @param showSelection  false hides the checkboxes (detail Files tab, where
 *                       only priority + rename apply).
 */
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
) {
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val q = filter.trim()
    // Reads of `expanded` inside the calculation are tracked, so toggling a
    // directory re-runs the flatten and children appear/disappear.
    val visible = remember(roots, q) { collectVisible(roots, q, expanded) }

    LazyColumn(Modifier.fillMaxWidth()) {
        items(visible, key = { it.key }) { node ->
            // Directories default to EXPANDED so the user immediately sees
            // every file inside — the #1 complaint ("不知道种子里有什么").
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
                // Tap the name (not the checkbox) toggles the file too — the
                // whole row is the hit target, matching qBittorrent.
                Row(
                    Modifier.weight(1f).clickable {
                        if (node.isDir) {
                            expanded[node.key] = !open
                        } else {
                            val idx = node.leafIndex ?: -1
                            if (showSelection) onToggleLeaf(idx, !isSelected(idx))
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

/**
 * Depth-first flatten. Directories expand by default; a COLLAPSED directory
 * hides its children (the arrow actually works now). A filter that matches
 * a descendant forces that subtree to be shown so the user always sees the
 * matching files ("筛选后不知道种子里有什么" is fixed by showing matches).
 */
private fun collectVisible(
    nodes: List<FileTreeNode>,
    q: String,
    expanded: MutableMap<String, Boolean>,
): List<FileTreeNode> {
    val out = mutableListOf<FileTreeNode>()
    fun walk(list: List<FileTreeNode>) {
        for (n in list) {
            val match = q.isEmpty() || n.name.contains(q, ignoreCase = true)
            if (match) {
                out.add(n)
                if (n.isDir && (expanded[n.key] ?: true)) walk(n.children)
            } else if (n.isDir && subtreeMatches(n, q)) {
                // A non-matching parent still reveals matching descendants.
                walk(n.children)
            }
        }
    }
    walk(nodes)
    return out
}

/** True when any descendant (recursively) matches the filter. */
private fun subtreeMatches(node: FileTreeNode, q: String): Boolean =
    node.children.any { it.name.contains(q, ignoreCase = true) || subtreeMatches(it, q) }

/**
 * Depth-first lookup of a node by its stable key. Used by directory
 * toggle/priority callbacks to address every descendant leaf.
 */
fun findNodeByKey(roots: List<FileTreeNode>, key: String): FileTreeNode? {
    for (r in roots) {
        if (r.key == key) return r
        findNodeByKey(r.children, key)?.let { return it }
    }
    return null
}
