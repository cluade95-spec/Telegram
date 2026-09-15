package org.telegram.messenger

import org.junit.Assert.*
import org.junit.Test

class SyncedLyricsControllerTest {
    @Test fun standardLrcHasOneLineAndNoSegments() {
        val lyrics = SyncedLyricsController.parse("[00:10.00]I've been tryna call")
        assertEquals(1, lyrics.lines.size)
        assertEquals("I've been tryna call", lyrics.lines[0].text)
        assertTrue(lyrics.lines[0].karaokeSegments.isEmpty())
        assertFalse(SyncedLyricsController.hasKaraokeTiming(lyrics.source))
    }

    @Test fun enhancedLrcKeepsWordsInsideOneLine() {
        val lyrics = SyncedLyricsController.parse(
            "[00:10.00]<00:10.00>I've <00:10.42>been <00:10.76>tryna <00:11.20>call"
        )
        assertEquals(1, lyrics.lines.size)
        assertEquals("I've been tryna call", lyrics.lines[0].text)
        assertEquals(4, lyrics.lines[0].karaokeSegments.size)
        assertEquals(listOf(10000L, 10420L, 10760L, 11200L),
            lyrics.lines[0].karaokeSegments.map { it.startTimeMs })
    }

    @Test fun linesNotWordsDefineModelRows() {
        val lyrics = SyncedLyricsController.parse(
            "[00:01]<00:01>go <00:02>go <00:03>go\n[00:04]<00:04>Hello, <00:05>world!"
        )
        assertEquals(2, lyrics.lines.size)
        assertEquals("go go go", lyrics.lines[0].text)
        assertEquals(3, lyrics.lines[0].karaokeSegments.size)
        assertEquals("Hello, world!", lyrics.lines[1].text)
    }

    @Test fun blankContractionsUnicodeAndEmojiArePreserved() {
        val lyrics = SyncedLyricsController.parse(
            "[00:01]\n[00:02]<00:02>I've <00:03>don't <00:04>we're\n" +
                "[00:05]<00:05>ሰላም <00:06>ዓለም🙂"
        )
        assertEquals(3, lyrics.lines.size)
        assertEquals("", lyrics.lines[0].text)
        assertEquals("I've don't we're", lyrics.lines[1].text)
        assertEquals("ሰላም ዓለም🙂", lyrics.lines[2].text)
        val last = lyrics.lines[2].karaokeSegments.last()
        assertEquals(lyrics.lines[2].text.length, last.endOffsetUtf16)
    }

    @Test fun malformedWordOrderRetainsSafeLineSync() {
        val lyrics = SyncedLyricsController.parse("[00:10]<00:11>Hello <00:10>world")
        assertEquals(SyncedLyricsController.Kind.SYNCED, lyrics.kind)
        assertEquals("Hello world", lyrics.lines.single().text)
        assertTrue(lyrics.lines.single().karaokeSegments.isEmpty())
    }

    @Test fun onlyEnhancedLrcQualifiesAsKaraoke() {
        assertFalse(SyncedLyricsController.hasKaraokeTiming("[00:10]ordinary line"))
        assertTrue(SyncedLyricsController.hasKaraokeTiming("[00:10]<00:10>word timed"))
    }
}
