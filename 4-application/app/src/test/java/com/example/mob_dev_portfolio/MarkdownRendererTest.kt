package com.example.mob_dev_portfolio

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.example.mob_dev_portfolio.ui.analysis.MarkdownBlock
import com.example.mob_dev_portfolio.ui.analysis.parseMarkdown
import com.example.mob_dev_portfolio.ui.analysis.renderInline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pinning tests for the hand-rolled markdown parser.
 *
 * The renderer underpins the AI summary card — if any of these regress the
 * user sees literal `**asterisks**` in their health summary, which was the
 * exact complaint we shipped this parser to fix. Keep this file small and
 * focused on the observable grammar rather than the exact span boundaries
 * (those are implementation detail of buildAnnotatedString).
 */
class MarkdownRendererTest {

    // --- parseMarkdown ---------------------------------------------------

    @Test
    fun blank_input_returns_no_blocks() {
        assertTrue(parseMarkdown("").isEmpty())
        assertTrue(parseMarkdown("   \n\n  ").isEmpty())
    }

    @Test
    fun single_paragraph_is_one_block() {
        val blocks = parseMarkdown("Hello world")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
        assertEquals("Hello world", (blocks[0] as MarkdownBlock.Paragraph).text.text)
    }

    @Test
    fun hash_prefixes_become_headings_at_each_level() {
        val blocks = parseMarkdown(
            """
            # Title
            ## Sub
            ### Smaller
            """.trimIndent(),
        )
        val headings = blocks.filterIsInstance<MarkdownBlock.Heading>()
        assertEquals(3, headings.size)
        assertEquals(listOf(1, 2, 3), headings.map { it.level })
        assertEquals("Title", headings[0].text.text)
        assertEquals("Sub", headings[1].text.text)
        assertEquals("Smaller", headings[2].text.text)
    }

    @Test
    fun dash_and_asterisk_bullets_both_parse() {
        val blocks = parseMarkdown(
            """
            - one
            * two
            """.trimIndent(),
        )
        val bullets = blocks.filterIsInstance<MarkdownBlock.Bullet>()
        assertEquals(2, bullets.size)
        assertEquals("one", bullets[0].text.text)
        assertEquals("two", bullets[1].text.text)
    }

    @Test
    fun numbered_list_preserves_index() {
        val blocks = parseMarkdown(
            """
            1. first
            2. second
            10. tenth
            """.trimIndent(),
        )
        val numbered = blocks.filterIsInstance<MarkdownBlock.Numbered>()
        assertEquals(3, numbered.size)
        assertEquals(1, numbered[0].index)
        assertEquals(2, numbered[1].index)
        assertEquals(10, numbered[2].index)
        assertEquals("first", numbered[0].text.text)
    }

    @Test
    fun blank_lines_collapse_to_single_spacer() {
        val blocks = parseMarkdown(
            """
            one


            two
            """.trimIndent(),
        )
        // Expect: Paragraph(one), Spacer, Paragraph(two).
        val kinds = blocks.map { it::class.simpleName }
        assertEquals(listOf("Paragraph", "Spacer", "Paragraph"), kinds)
    }

    @Test
    fun trailing_blank_lines_do_not_leave_phantom_spacer() {
        val blocks = parseMarkdown("hello\n\n\n")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
    }

    @Test
    fun mixed_document_routes_each_line_to_the_right_block() {
        val source = """
            ## Patterns
            - You **often** log migraines on *rainy* days.
            - Severity is usually 6-8.

            ## Suggestions
            1. Try hydration on humid afternoons.
        """.trimIndent()

        val blocks = parseMarkdown(source)
        val kinds = blocks.map { it::class.simpleName }
        // Heading, Bullet, Bullet, Spacer, Heading, Numbered
        assertEquals(
            listOf("Heading", "Bullet", "Bullet", "Spacer", "Heading", "Numbered"),
            kinds,
        )
    }

    // --- renderInline ----------------------------------------------------

    @Test
    fun plain_text_has_no_styling() {
        val annotated = renderInline("just words")
        assertEquals("just words", annotated.text)
        assertTrue(
            "plain text must not carry any spans",
            annotated.spanStyles.isEmpty(),
        )
    }

    @Test
    fun bold_applies_bold_weight_to_inner_text() {
        val annotated = renderInline("before **strong** after")
        assertEquals("before strong after", annotated.text)
        val spans = annotated.spanStyles
        assertEquals(1, spans.size)
        val span = spans[0]
        assertEquals(FontWeight.Bold, span.item.fontWeight)
        assertEquals("strong", annotated.text.substring(span.start, span.end))
    }

    @Test
    fun underscore_bold_alternate_form_is_supported() {
        val annotated = renderInline("__heavy__")
        assertEquals("heavy", annotated.text)
        assertEquals(FontWeight.Bold, annotated.spanStyles[0].item.fontWeight)
    }

    @Test
    fun italic_single_asterisk_applies_italic_style() {
        val annotated = renderInline("maybe *soft* note")
        assertEquals("maybe soft note", annotated.text)
        val span = annotated.spanStyles.single()
        assertEquals(FontStyle.Italic, span.item.fontStyle)
        assertEquals("soft", annotated.text.substring(span.start, span.end))
    }

    @Test
    fun unclosed_marker_falls_back_to_literal_character() {
        val annotated = renderInline("trailing * asterisk")
        // The lone `*` should survive as text, not swallow the rest of the line.
        assertTrue(
            "expected lone asterisk to remain, got '${annotated.text}'",
            annotated.text.contains("*"),
        )
        assertTrue(annotated.spanStyles.isEmpty())
    }

    @Test
    fun bold_containing_italic_produces_nested_spans() {
        val annotated = renderInline("**big *and* loud**")
        // Outer bold covers the inner italic word plus the wrappers.
        assertEquals("big and loud", annotated.text)
        val styles = annotated.spanStyles.map { it.item }
        assertTrue("expected a bold span", styles.any { it.fontWeight == FontWeight.Bold })
        assertTrue("expected an italic span", styles.any { it.fontStyle == FontStyle.Italic })
    }

    @Test
    fun inline_code_is_rendered_without_backticks() {
        val annotated = renderInline("use `reload()` to retry")
        assertEquals("use reload() to retry", annotated.text)
        assertFalse(
            "backticks must be stripped from rendered text",
            annotated.text.contains('`'),
        )
    }

    @Test
    fun raw_markdown_asterisks_never_appear_in_rendered_text() {
        // Regression pin: the original bug surfaced literal ** asterisks to
        // the user inside the AI summary card.
        val annotated = renderInline("**important** and *gentle*")
        assertFalse(
            "asterisks leaked into rendered output: '${annotated.text}'",
            annotated.text.contains('*'),
        )
    }
}
