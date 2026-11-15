package com.rrajath.grove.data

import com.rrajath.grove.attach.attachDirProperty
import com.rrajath.grove.attach.fileAttachDirProperty
import com.rrajath.grove.markdown.MarkdownTaskParser
import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.org.OrgKeywords
import com.rrajath.grove.org.OrgParser
import com.rrajath.grove.org.INTRO_LINE_INDEX
import com.rrajath.grove.sync.KnownNotebook
import com.rrajath.grove.sync.NoteIndex
import com.rrajath.grove.sync.NotebookStub

/** [NoteIndex] over Room: parses notebook text into row entities. */
class RoomNoteIndex(
    private val db: GroveDatabase,
    private val keywords: () -> OrgKeywords = { OrgKeywords.DEFAULT },
    /** Called after indexing for file-specific side effects (currently Org reminders). */
    private val onIndexed: suspend (fileName: String, doc: OrgDocument) -> Unit = { _, _ -> },
) : NoteIndex {

    private val dao get() = db.indexDao()

    override suspend fun knownNotebooks(): Map<String, KnownNotebook> =
        dao.notebookSyncStates().associate { it.fileName to KnownNotebook(it.revision, it.conflictFileName, it.isIndexed) }

    override suspend fun stubNotebooks(stubs: List<NotebookStub>) {
        dao.insertNotebookStubs(stubs.map {
            NotebookEntity(
                fileName = it.fileName, revision = it.revision, noteCount = 0,
                lastModified = it.lastModified, conflictFileName = it.conflictFileName,
                title = null, isIndexed = false,
            )
        })
    }

    override suspend fun indexNotebook(
        fileName: String,
        revision: String,
        text: String,
        lastModified: Long,
        conflictFileName: String?,
    ) {
        val configuredKeywords = keywords()
        if (fileName.endsWith(".md", ignoreCase = true)) {
            indexMarkdown(fileName, revision, text, lastModified, conflictFileName, configuredKeywords)
            // Markdown tasks do not carry Org reminders. Deliver an empty Org parse
            // to maintain the index callback contract and clear any obsolete rows.
            onIndexed(fileName, OrgParser.parse("", configuredKeywords))
            return
        }

        val doc = OrgParser.parse(text, configuredKeywords)
        val inheritedTagsAll = doc.inheritedTagsAll()
        val notes = doc.headlines.mapIndexed { i, h ->
            NoteEntity(
                fileName = fileName,
                lineIndex = h.lineIndex,
                level = h.level,
                title = h.title,
                keyword = h.keyword,
                priority = h.priority?.toString(),
                tags = h.tags.joinToString(":"),
                inheritedTags = inheritedTagsAll[i].joinToString(":"),
                scheduled = h.planning.scheduled?.format(),
                deadline = h.planning.deadline?.format(),
                closed = h.planning.closed?.format(),
                activeTimestamps = h.activeTimestamps.takeIf { it.isNotEmpty() }?.joinToString(" ") { it.format() },
                orgId = h.id,
                customId = h.customId,
                createdAt = h.properties["CREATED"],
                body = doc.bodyOf(h).joinToString("\n"),
                isDone = h.keyword != null && doc.keywords.isDone(h.keyword),
                lastModified = lastModified,
                dirProperty = h.attachDirProperty(),
            )
        } + introNote(doc, fileName, lastModified)
        dao.replaceNotebook(
            NotebookEntity(
                fileName = fileName,
                revision = revision,
                noteCount = doc.headlines.count { it.level == 1 } + (if (doc.hasIntro) 1 else 0),
                lastModified = lastModified,
                conflictFileName = conflictFileName,
                title = doc.preambleKeywords.firstOrNull { it.first.equals("#+TITLE:", ignoreCase = true) }?.second,
                orgId = doc.filePropertyDrawer.firstOrNull { it.first.equals(":ID:", ignoreCase = true) }?.second,
                dirProperty = doc.fileAttachDirProperty(),
            ),
            notes,
        )
        onIndexed(fileName, doc)
    }

    private suspend fun indexMarkdown(
        fileName: String,
        revision: String,
        text: String,
        lastModified: Long,
        conflictFileName: String?,
        configuredKeywords: OrgKeywords,
    ) {
        val tasks = MarkdownTaskParser.parse(text, configuredKeywords)
        val notes = tasks.map { task ->
            val taskBody = buildString {
                append(task.title)
                val lines = text.split('\n')
                var i = task.lineIndex + 1
                while (i < lines.size && lines[i].isNotBlank() && !Regex("^( *)(?:[-+*]|[0-9]+[.)])[ \\t]+.*$").matches(lines[i])) {
                    append('\n').append(lines[i].trim())
                    i++
                }
            }
            NoteEntity(
                fileName = fileName,
                lineIndex = task.lineIndex,
                level = task.level,
                title = task.title,
                keyword = task.keyword,
                priority = task.priority?.toString(),
                tags = task.tags.joinToString(":"),
                inheritedTags = task.tags.joinToString(":"),
                scheduled = null,
                deadline = null,
                closed = null,
                activeTimestamps = null,
                orgId = null,
                customId = null,
                createdAt = null,
                body = taskBody,
                isDone = configuredKeywords.isDone(task.keyword),
                lastModified = lastModified,
            )
        }
        dao.replaceNotebook(
            NotebookEntity(
                fileName = fileName,
                revision = revision,
                noteCount = tasks.count { it.parentLineIndex == null },
                lastModified = lastModified,
                conflictFileName = conflictFileName,
                title = null,
            ),
            notes,
        )
    }

    private fun introNote(doc: OrgDocument, fileName: String, lastModified: Long): List<NoteEntity> {
        if (!doc.hasIntro) return emptyList()
        return listOf(
            NoteEntity(
                fileName = fileName,
                lineIndex = INTRO_LINE_INDEX,
                level = 0,
                title = doc.introTitle,
                keyword = null,
                priority = null,
                tags = "",
                inheritedTags = doc.fileTags.joinToString(":"),
                scheduled = null,
                deadline = null,
                closed = null,
                activeTimestamps = null,
                orgId = null,
                customId = null,
                createdAt = null,
                body = doc.introBody.joinToString("\n").trim(),
                isDone = false,
                lastModified = lastModified,
            ),
        )
    }

    override suspend fun setConflict(fileName: String, conflictFileName: String?) {
        dao.setConflict(fileName, conflictFileName)
    }

    override suspend fun removeNotebook(fileName: String) {
        dao.removeNotebook(fileName)
    }
}
