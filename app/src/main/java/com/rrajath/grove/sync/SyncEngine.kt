package com.rrajath.grove.sync

import com.rrajath.grove.vault.FileEntry
import com.rrajath.grove.vault.FileStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

sealed class SyncState {
    data object Idle : SyncState()
    data object Checking : SyncState()
    data class Pulling(val fileName: String, val index: Int, val total: Int) : SyncState()
    data class Done(val result: SyncResult) : SyncState()
    data class Error(val message: String) : SyncState()
}

data class SyncResult(
    val pulled: List<String>,
    val removed: List<String>,
    val conflicts: Map<String, String>,
    val completedAt: Long,
)

data class KnownNotebook(val revision: String, val conflictFileName: String?, val isIndexed: Boolean = true)
data class NotebookStub(val fileName: String, val revision: String, val lastModified: Long, val conflictFileName: String?)

/** Persistence boundary so the engine is unit-testable without Room. */
interface NoteIndex {
    suspend fun knownNotebooks(): Map<String, KnownNotebook>
    suspend fun stubNotebooks(stubs: List<NotebookStub>)
    suspend fun indexNotebook(fileName: String, revision: String, text: String, lastModified: Long, conflictFileName: String?)
    suspend fun setConflict(fileName: String, conflictFileName: String?)
    suspend fun removeNotebook(fileName: String)
}

/**
 * Sync state machine over source files. Org and Markdown files share discovery,
 * revision tracking, ignore rules and the same rebuildable Room index.
 */
class SyncEngine(
    private val store: FileStore,
    private val index: NoteIndex,
    private val clock: () -> Long,
) {
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state
    val revision: (FileEntry) -> String = { "${it.lastModified}:${it.size}" }

    companion object { private const val PARSE_CONCURRENCY = 3 }

    suspend fun sync(log: (String) -> Unit = {}): SyncResult? {
        _state.value = SyncState.Checking
        return try {
            val entries = store.list()
            val sourceFiles = entries.filter { isSupportedSource(it.name) && !SyncConflicts.isConflictFile(it.name) }
            val orgNames = sourceFiles.map { it.name }.filter { it.endsWith(".org", ignoreCase = true) }
            val conflicts = SyncConflicts.detect(entries.map { it.name }).filterKeys { base ->
                orgNames.any { it.equals(base, ignoreCase = true) }
            }
            val known = index.knownNotebooks()
            val current = sourceFiles.associateBy({ it.name }, revision)
            val changed = sourceFiles.filter {
                val k = known[it.name]
                k == null || !k.isIndexed || k.revision != current[it.name]
            }
            val removed = (known.keys - current.keys).toList()
            val stubs = changed.filter { it.name !in known }.map {
                NotebookStub(it.name, current.getValue(it.name), it.lastModified, conflicts[it.name])
            }
            if (stubs.isNotEmpty()) index.stubNotebooks(stubs)

            coroutineScope {
                val semaphore = Semaphore(PARSE_CONCURRENCY)
                val completed = AtomicInteger(0)
                changed.map { entry ->
                    async {
                        semaphore.withPermit {
                            index.indexNotebook(
                                entry.name, current.getValue(entry.name), store.read(entry.name),
                                entry.lastModified, conflicts[entry.name],
                            )
                            _state.value = SyncState.Pulling(entry.name, completed.incrementAndGet(), changed.size)
                            log("pulled ${entry.name}")
                        }
                    }
                }.awaitAll()
            }
            removed.forEach { index.removeNotebook(it); log("removed $it from index") }
            val changedNames = changed.mapTo(HashSet()) { it.name }
            sourceFiles.forEach { entry ->
                if (entry.name !in changedNames && known[entry.name]?.conflictFileName != conflicts[entry.name]) {
                    index.setConflict(entry.name, conflicts[entry.name])
                }
            }
            conflicts.forEach { (base, copy) -> log("conflict: $base vs $copy") }
            SyncResult(changed.map { it.name }, removed, conflicts, clock()).also { _state.value = SyncState.Done(it) }
        } catch (e: Exception) {
            log("sync failed: ${e.message}")
            _state.value = SyncState.Error(e.message ?: "Sync failed")
            null
        }
    }

    /** Re-index a single already-known file the caller just wrote. */
    suspend fun reindexOne(fileName: String, text: String, conflictFileName: String?) {
        val stat = store.stat(fileName) ?: return
        index.indexNotebook(fileName, revision(stat), text, stat.lastModified, conflictFileName)
    }

    private fun isSupportedSource(name: String): Boolean =
        (name.endsWith(".org", ignoreCase = true) || name.endsWith(".md", ignoreCase = true)) &&
            !name.contains(".sync-conflict-", ignoreCase = true)
}
