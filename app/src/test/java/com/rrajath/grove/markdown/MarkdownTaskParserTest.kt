package com.rrajath.grove.markdown

import com.rrajath.grove.org.OrgKeywords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MarkdownTaskParserTest {
    private val keywords = OrgKeywords.parse("TODO DOING WAITING | DONE CANCELLED")

    @Test fun unorderedAndOrderedMarkersAreSupported() {
        val tasks = MarkdownTaskParser.parse("- TODO dash\n* DOING star\n+ DONE plus\n1. WAITING numbered\n2) CANCELLED paren", keywords)
        assertEquals(listOf("dash", "star", "plus", "numbered", "paren"), tasks.map { it.title })
        assertEquals(listOf("TODO", "DOING", "DONE", "WAITING", "CANCELLED"), tasks.map { it.keyword })
    }

    @Test fun nestedItemsKeepDepthAndNearestTaskParent() {
        val tasks = MarkdownTaskParser.parse("- TODO Parent\n  - TODO Child\n    1. DONE Grandchild\n  - DOING Sibling", keywords)
        assertEquals(listOf(0, 1, 2, 1), tasks.map { it.level })
        assertEquals(listOf(null, 0, 1, 0), tasks.map { it.parentLineIndex })
    }

    @Test fun configurableKeywordsRequireCompleteToken() {
        val tasks = MarkdownTaskParser.parse("- TODOX not a task\n- TODAY not a task\n- TODO valid", keywords)
        assertEquals(listOf("valid"), tasks.map { it.title })
    }

    @Test fun proseAndContinuationLinesAreNotTasks() {
        val tasks = MarkdownTaskParser.parse("TODO prose\n- ordinary list item\n  TODO continuation\n- TODO Real\n  continuation\n\n- DONE Next", keywords)
        assertEquals(listOf("Real", "Next"), tasks.map { it.title })
    }

    @Test fun skipsBothFenceStylesAndLongerClosers() {
        val source = "```markdown\n- TODO ignored\n````\n- DONE real\n~~~\n- TODO ignored too\n~~~~~\n"
        assertEquals(listOf("real"), MarkdownTaskParser.parse(source, keywords).map { it.title })
    }

    @Test fun extractsPriorityAndTrailingOrgTags() {
        val task = MarkdownTaskParser.parse("- TODO [#A] Prepare slides :work:urgent:", keywords).single()
        assertEquals('A', task.priority)
        assertEquals(listOf("work", "urgent"), task.tags)
        assertEquals("Prepare slides", task.title)
    }

    @Test fun stateEditChangesOnlyKeywordAndKeepsCrlfAndFinalNewline() {
        val source = "prose\r\n  * TODO [link](https://example.test) **bold**\r\n\r\n~~~\r\n- DONE sample\r\n~~~\r\n"
        val task = MarkdownTaskParser.parse(source, keywords).single()
        val updated = MarkdownTaskParser.changeKeyword(source, task, "DONE", keywords)
        assertEquals(source.replace("TODO", "DONE"), updated)
    }

    @Test fun staleReferenceWillNotChangeAReplacedLine() {
        val original = "- TODO Original\n"
        val task = MarkdownTaskParser.parse(original, keywords).single()
        assertNull(MarkdownTaskParser.changeKeyword("- TODO Different\n", task, "DONE", keywords))
        assertNull(MarkdownTaskParser.changeKeyword(original, task, "UNKNOWN", keywords))
    }

    @Test fun positionsReferToSourceLinesAndExactKeywordOffset() {
        val task = MarkdownTaskParser.parse("text\n    12. DOING work", keywords).single()
        assertEquals(1, task.lineIndex)
        assertEquals("    12. ".length, task.keywordStart)
        val updated = MarkdownTaskParser.changeKeyword("text\n    12. DOING work", task, "DONE", keywords)
        assertNotEquals("text\n12. DONE work", updated)
        assertEquals("text\n    12. DONE work", updated)
    }
}
