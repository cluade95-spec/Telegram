package org.telegram.messenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.telegram.messenger.SyncedLyricsController.Kind
import org.telegram.messenger.SyncedLyricsController.Line
import org.telegram.messenger.SyncedLyricsController.parse

/**
 * TTML, the second genuine word-timing dialect this app reads, and the only one that can state an
 * end as well as a start.
 *
 * Three things are asserted throughout. First, that a TTML document is recognised and read without
 * any change to how LRC is read - the two front ends share the same assembly tail, and a document
 * that is not TTML must still take the LRC path. Second, that word timing is captured exactly when
 * the source states it, and that an end survives only when the source genuinely stated one: nothing
 * is interpolated, divided or repaired. Third, that the parts of the AMLL dialect that are not
 * lyrics - translations, romanisations, background vocals - are skipped entirely rather than
 * appearing as text or as timing.
 */
class SyncedLyricsTtmlTest {

    private fun tt(body: String): String =
        """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div>$body</div></body></tt>"""

    /** "start-end@begin..end:[text]" per segment; end is "-" when the source stated none. */
    private fun segments(line: Line): String {
        val segments = line.segments ?: return "-"
        return (0 until segments.size()).joinToString(" ") { i ->
            val start = segments.startOffset(i)
            val end = segments.endOffset(i)
            val stated = if (segments.hasEndTime(i)) segments.endTimeMs(i).toString() else "-"
            "$start-$end@${segments.startTimeMs(i)}..$stated:[${line.text.substring(start, end)}]"
        }
    }

    // ------------------------------------------------------------------ recognition

    @Test
    fun aTtmlDocumentIsReadAsTtml() {
        val lyrics = parse(tt("""<p begin="00:01.000" end="00:02.000"><span begin="00:01.000" end="00:02.000">Hello</span></p>"""))
        assertEquals(Kind.SYNCED, lyrics.kind)
        assertEquals(1, lyrics.lines.size)
        assertEquals("Hello", lyrics.lines[0].text)
        assertEquals(1000L, lyrics.lines[0].timeMs)
    }

    @Test
    fun anXmlPrologueAndCommentsDoNotHideTheRoot() {
        val source = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!-- exported -->\n" +
            tt("""<p begin="00:01.000"><span begin="00:01.000">Hi</span></p>""")
        assertEquals(Kind.SYNCED, parse(source).kind)
    }

    @Test
    fun ordinaryLrcIsUnaffected() {
        val lyrics = parse("[00:01.00]<00:01.00>one <00:01.50>two")
        assertEquals(Kind.SYNCED, lyrics.kind)
        assertEquals("one two", lyrics.lines[0].text)
        assertNotNull(lyrics.lines[0].segments)
    }

    @Test
    fun textThatMerelyMentionsTtIsNotTtml() {
        // A plain lyric whose first characters look tag-ish must not be taken for a document.
        val lyrics = parse("<3 you\nand <3 me")
        assertEquals(Kind.PLAIN, lyrics.kind)
    }

    // ------------------------------------------------------------------ word timing

    @Test
    fun wordSpansKeepTheirStatedStartAndEnd() {
        val lyrics = parse(tt(
            """<p begin="00:01.000" end="00:03.000">""" +
                """<span begin="00:01.000" end="00:01.400">Hello </span>""" +
                """<span begin="00:01.600" end="00:02.900">world</span></p>"""))
        assertEquals("Hello world", lyrics.lines[0].text)
        assertEquals("0-6@1000..1400:[Hello ] 6-11@1600..2900:[world]", segments(lyrics.lines[0]))
    }

    @Test
    fun aSpanWithoutAnEndIsRecordedAsHavingNone() {
        val lyrics = parse(tt(
            """<p begin="00:01.000">""" +
                """<span begin="00:01.000">Hello </span><span begin="00:01.600">world</span></p>"""))
        val segments = lyrics.lines[0].segments!!
        assertFalse(segments.hasEndTime(0))
        assertFalse(segments.hasEndTime(1))
    }

    @Test
    fun anEndBeforeItsOwnStartIsNotKept() {
        val lyrics = parse(tt(
            """<p begin="00:01.000">""" +
                """<span begin="00:01.000" end="00:00.500">Hello </span>""" +
                """<span begin="00:01.600" end="00:02.000">world</span></p>"""))
        val segments = lyrics.lines[0].segments!!
        assertFalse("an impossible end is dropped, not repaired", segments.hasEndTime(0))
        assertTrue(segments.hasEndTime(1))
    }

    @Test
    fun anEndPastTheNextLineIsNotKept() {
        val lyrics = parse(tt(
            """<p begin="00:01.000"><span begin="00:01.000" end="00:09.000">Hello</span></p>""" +
                """<p begin="00:02.000"><span begin="00:02.000" end="00:02.500">world</span></p>"""))
        assertFalse(lyrics.lines[0].segments!!.hasEndTime(0))
    }

    @Test
    fun theLineStartsAtTheEarliestThingItStates() {
        // A <p> whose first span begins before the <p> itself still starts when the singing does.
        val lyrics = parse(tt("""<p begin="00:05.000"><span begin="00:04.500">early</span></p>"""))
        assertEquals(4500L, lyrics.lines[0].timeMs)
    }

    @Test
    fun aLineWithNoTimedSpansIsStillALine() {
        val lyrics = parse(tt("""<p begin="00:01.000">just a line</p>"""))
        assertEquals(Kind.SYNCED, lyrics.kind)
        assertEquals("just a line", lyrics.lines[0].text)
        assertNull("nothing was timed inside it", lyrics.lines[0].segments)
    }

    @Test
    fun nestedSpansOnlyCountOnce() {
        // The AMLL dialect nests per-character spans inside per-word ones; only the outermost
        // timed span is a word, or one word would be counted several times over.
        val lyrics = parse(tt(
            """<p begin="00:01.000"><span begin="00:01.000" end="00:01.500">""" +
                """<span begin="00:01.000">He</span><span begin="00:01.200">llo</span>""" +
                """</span></p>"""))
        assertEquals("Hello", lyrics.lines[0].text)
        assertEquals("0-5@1000..1500:[Hello]", segments(lyrics.lines[0]))
    }

    @Test
    fun malformedTimingCostsTheLineItsWordTimingOnly() {
        val lyrics = parse(tt(
            """<p begin="00:01.000"><span begin="not a time">Hello </span>""" +
                """<span begin="00:01.500">world</span></p>"""))
        assertEquals("Hello world", lyrics.lines[0].text)
        assertNull("one unreadable tag makes the whole line untrusted", lyrics.lines[0].segments)
    }

    @Test
    fun anUntimedSpanIsNotMalformed() {
        val lyrics = parse(tt(
            """<p begin="00:01.000">(<span begin="00:01.200">Hello</span>)</p>"""))
        assertEquals("(Hello)", lyrics.lines[0].text)
        assertNotNull(lyrics.lines[0].segments)
    }

    // ------------------------------------------------------------------ non-lyric content

    @Test
    fun translationsAndRomanisationsAreSkippedWhole() {
        val lyrics = parse(tt(
            """<p begin="00:01.000"><span begin="00:01.000">Hello</span>""" +
                """<span ttm:role="x-translation">Bonjour</span>""" +
                """<span ttm:role="x-roman">Herro</span></p>"""))
        assertEquals("Hello", lyrics.lines[0].text)
        assertEquals("0-5@1000..-:[Hello]", segments(lyrics.lines[0]))
    }

    @Test
    fun backgroundVocalsAreSkippedWhole() {
        val lyrics = parse(tt(
            """<p begin="00:01.000"><span begin="00:01.000">Hello</span>""" +
                """<span ttm:role="x-bg"><span begin="00:01.100">ooh</span></span></p>"""))
        assertEquals("Hello", lyrics.lines[0].text)
    }

    @Test
    fun prettyPrintedWhitespaceDoesNotBecomeText() {
        val lyrics = parse(
            """<tt xmlns="http://www.w3.org/ns/ttml">
                 <body>
                   <div>
                     <p begin="00:01.000">
                       <span begin="00:01.000">Hello </span>
                       <span begin="00:01.500">world</span>
                     </p>
                   </div>
                 </body>
               </tt>""")
        assertEquals("Hello world", lyrics.lines[0].text)
    }

    // ------------------------------------------------------------------ time grammar

    @Test
    fun theTimeFormatsTheDialectUses() {
        assertEquals(3_723_400L, parse(tt("""<p begin="01:02:03.400">x</p>""")).lines[0].timeMs)
        assertEquals(62_400L, parse(tt("""<p begin="01:02.400">x</p>""")).lines[0].timeMs)
        assertEquals(12_300L, parse(tt("""<p begin="12.3s">x</p>""")).lines[0].timeMs)
        assertEquals(1_500L, parse(tt("""<p begin="1.5">x</p>""")).lines[0].timeMs)
    }

    @Test
    fun anOutOfRangeFieldIsNotATime() {
        assertEquals(Kind.MALFORMED, parse(tt("""<p begin="90:00.000">x</p>""")).kind)
        assertEquals(Kind.MALFORMED, parse(tt("""<p begin="01:02:99.000">x</p>""")).kind)
    }

    @Test
    fun theLeadingFieldOfAnHourTimeMayExceedSixty() {
        assertEquals(360_000_000L, parse(tt("""<p begin="100:00:00.000">x</p>""")).lines[0].timeMs)
    }

    // ------------------------------------------------------------------ whole documents

    @Test
    fun aDocumentWithNoTimedLineIsNotSynced() {
        assertEquals(Kind.MALFORMED, parse(tt("<p>no timing at all</p>")).kind)
    }

    @Test
    fun anEmptyDocumentIsNotSynced() {
        assertEquals(Kind.MALFORMED, parse(tt("")).kind)
    }

    @Test
    fun linesAreOrderedByTimeRegardlessOfDocumentOrder() {
        val lyrics = parse(tt(
            """<p begin="00:05.000">second</p><p begin="00:01.000">first</p>"""))
        assertEquals(listOf("first", "second"), lyrics.lines.map { it.text })
    }

    @Test
    fun theSourceIsPreservedVerbatim() {
        val source = tt("""<p begin="00:01.000"><span begin="00:01.000">Hello</span></p>""")
        assertEquals(source, parse(source).source)
    }
}
