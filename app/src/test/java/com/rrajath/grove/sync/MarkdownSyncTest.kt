package com.rrajath.grove.sync

import com.rrajath.grove.vault.JvmFileStore
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private class MarkdownRecordingIndex : NoteIndex {
    val revisions = mutableMapOf<String, String>()
    val contents = mutableMapOf<String, String>()
    override suspend fun knownNotebooks() = revisions.mapValues { KnownNotebook(it.value, null) }
    override suspend fun stubNotebooks(stubs: List<NotebookStub>) = Unit
    override suspend fun indexNotebook(fileName: String, revision: String, text: String, lastModified: Long, conflictFileName: String?) {
        revisions[fileName] = revision
        contents[fileName] = text
    }
    override suspend fun setConflict(fileName: String, conflictFileName: String?) = Unit
    override suspend fun removeNotebook(fileName: String) { revisions.remove(fileName); contents.remove(fileName) }
}

class MarkdownSyncTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `markdown and org files coexist and changes are refreshed`() = runTest {
        File(tmp.root, "work").mkdirs()
        File(tmp.root, "work/tasks.md").writeText("- TODO Markdown task\n")
        File(tmp.root, "work/notes.org").writeText("* TODO Org task\n")
        File(tmp.root, "ignored.txt").writeText("not indexed")
        val index = MarkdownRecordingIndex()
        val engine = SyncEngine(JvmFileStore(tmp.root), index) { 1L }

        val first = engine.sync()!!
        assertEquals(setOf("work/tasks.md", "work/notes.org"), first.pulled.toSet())
        assertNull(index.contents["ignored.txt"])
        assertNotNull(index.contents["work/tasks.md"])

        File(tmp.root, "work/tasks.md").writeText("- DONE Markdown task\n")
        File(tmp.root, "work/tasks.md").setLastModified(System.currentTimeMillis() + 2_000)
        assertEquals(listOf("work/tasks.md"), engine.sync()!!.pulled)
        assertEquals("- DONE Markdown task\n", index.contents["work/tasks.md"])
    }
}
