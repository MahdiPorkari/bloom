package com.rrajath.grove.markdown

import com.rrajath.grove.org.OrgKeywords

/** A TODO list item found in Markdown source. Positions are zero-based. */
data class MarkdownTask(
    val lineIndex: Int,
    val keyword: String,
    val title: String,
    /** Number of list-item ancestors (including non-task list items). */
    val level: Int,
    /** Source line of the nearest enclosing TODO list item, if any. */
    val parentLineIndex: Int?,
    val priority: Char? = null,
    val tags: List<String> = emptyList(),
    /** The exact line as parsed, used to reject stale edits. */
    val sourceLine: String,
    /** Offset of the keyword in [sourceLine]. */
    val keywordStart: Int,
)

/**
 * Conservative Org-keyword extraction from Markdown list items. This is not a
 * Markdown renderer: the source text remains authoritative and unknown syntax
 * is left alone. Fenced code blocks are skipped before looking for list items.
 */
object MarkdownTaskParser {
    private val listMarker = Regex("^( *)(?:[-+*]|[0-9]+[.)])[ \\t]+(.*)$")
    private val fence = Regex("^(`{3,}|~{3,})(.*)$")
    private val tagsAtEnd = Regex("\\s+(:[\\p{L}\\p{M}\\p{N}_@#%-]+(?::[\\p{L}\\p{M}\\p{N}_@#%-]+)*:)\\s*$")
    private val priorityAtStart = Regex("^\\[#([A-Za-z])\\]\\s*")

    private data class ListAncestor(val indent: Int, val taskLine: Int?)

    fun parse(text: String, keywords: OrgKeywords = OrgKeywords.DEFAULT): List<MarkdownTask> {
        val lines = text.split('\n')
        val tasks = mutableListOf<MarkdownTask>()
        val ancestors = mutableListOf<ListAncestor>()
        var fenceChar: Char? = null
        var fenceLength = 0

        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.removeSuffix("\r")
            val trimmed = line.trimStart(' ', '\t')
            val activeFenceChar = fenceChar
            if (activeFenceChar != null) {
                val close = trimmed.takeWhile { it == activeFenceChar }.length
                if (close >= fenceLength && trimmed.drop(close).all { it == ' ' || it == '\t' }) {
                    fenceChar = null
                    fenceLength = 0
                }
                return@forEachIndexed
            }

            val opening = fence.matchEntire(trimmed)
            if (opening != null) {
                val delimiter = opening.groupValues[1]
                // Backtick fence info strings cannot themselves contain a backtick.
                if (delimiter.first() != '`' || '`' !in opening.groupValues[2]) {
                    fenceChar = delimiter.first()
                    fenceLength = delimiter.length
                    return@forEachIndexed
                }
            }

            val item = listMarker.matchEntire(line) ?: return@forEachIndexed
            val indent = item.groupValues[1].length + line.takeWhile { it == '\t' }.length * 3
            while (ancestors.isNotEmpty() && ancestors.last().indent >= indent) ancestors.removeAt(ancestors.lastIndex)
            val content = item.groupValues[2]
            val keyword = keywords.all
                .sortedByDescending { it.length }
                .firstOrNull { candidate ->
                    content.startsWith(candidate) &&
                        (content.length == candidate.length || content[candidate.length].isWhitespace())
                }
            if (keyword == null) {
                ancestors += ListAncestor(indent, null)
                return@forEachIndexed
            }

            var title = content.drop(keyword.length).trimStart()
            var tags = emptyList<String>()
            tagsAtEnd.find(title)?.let { match ->
                tags = match.groupValues[1].trim(':').split(':').filter(String::isNotEmpty)
                title = title.removeRange(match.range).trimEnd()
            }
            val priority = priorityAtStart.find(title)?.let { match ->
                title = title.removeRange(match.range)
                match.groupValues[1].first()
            }
            tasks += MarkdownTask(
                lineIndex = index,
                keyword = keyword,
                title = title.trim(),
                level = ancestors.size,
                parentLineIndex = ancestors.asReversed().firstNotNullOfOrNull { it.taskLine },
                priority = priority,
                tags = tags,
                sourceLine = line,
                keywordStart = item.groupValues[1].length + (item.groupValues[0].length - content.length),
            )
            ancestors += ListAncestor(indent, index)
        }
        return tasks
    }

    /**
     * Change just the keyword token in a freshly-read Markdown document. Returns
     * null if the line changed since indexing or no longer identifies this task.
     * No line splitting/joining is used, so CRLF, final newlines and all other
     * bytes remain exactly as read.
     */
    fun changeKeyword(text: String, task: MarkdownTask, newKeyword: String, keywords: OrgKeywords): String? {
        if (newKeyword !in keywords.all) return null
        var lineStart = 0
        var lineIndex = 0
        while (lineIndex < task.lineIndex) {
            val next = text.indexOf('\n', lineStart)
            if (next < 0) return null
            lineStart = next + 1
            lineIndex++
        }
        val newline = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        val rawLine = text.substring(lineStart, newline)
        val line = rawLine.removeSuffix("\r")
        if (line != task.sourceLine) return null
        val start = lineStart + task.keywordStart
        val end = start + task.keyword.length
        if (end > lineStart + line.length || text.substring(start, end) != task.keyword) return null
        return text.replaceRange(start, end, newKeyword)
    }
}
