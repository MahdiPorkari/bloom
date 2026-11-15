package com.rrajath.grove.data

import android.app.Application
import com.rrajath.grove.org.OrgKeywords
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MarkdownTaskIndexTest {
    @get:Rule val dispatcher = com.rrajath.grove.testing.support.MainDispatcherRule()
    private val db = com.rrajath.grove.testing.InMemoryGroveDatabase.create(queryCoroutineContext = dispatcher.dispatcher)
    @After fun close() = db.close()

    @Test fun `markdown tasks use the shared note rows and state facets`() = runTest {
        val keywords = OrgKeywords.parse("TODO DOING | DONE")
        RoomNoteIndex(db, { keywords }).indexNotebook(
            fileName = "tasks.md", revision = "r1", text = "- TODO Parent\n  - DONE Child\n", lastModified = 1L, conflictFileName = null,
        )
        val rows = db.indexDao().notesMatching(rawQuery("SELECT * FROM notes")).sortedBy { it.lineIndex }
        assertEquals(listOf("Parent", "Child"), rows.map { it.title })
        assertEquals(listOf("TODO", "DONE"), rows.map { it.keyword })
        assertEquals(listOf(0, 1), rows.map { it.level })
        assertEquals(listOf(false, true), rows.map { it.isDone })
        assertEquals("tasks.md", rows.first().fileName)
        assertTrue(db.ftsAvailable)
        val hits = db.indexDao().noteKeysMatching(rawQuery(
            "SELECT fileName, lineIndex FROM notes_fts WHERE notes_fts MATCH ?", listOf("\"Child\"")
        ))
        assertEquals(1, hits.size)
        assertEquals("tasks.md", hits.single().fileName)
    }
}
