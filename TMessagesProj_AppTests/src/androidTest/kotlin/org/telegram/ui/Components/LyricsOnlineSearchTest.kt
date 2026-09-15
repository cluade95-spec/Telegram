package org.telegram.ui.Components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.telegram.messenger.SyncedLyricsController

class LyricsOnlineSearchTest {
    @Test fun richSyncPreservesLineAndDoesNotTimeWhitespaceChunks() {
        val richSync = """[{"ts":10.0,"te":12.0,"x":"hello world","l":[{"c":"hello","o":0.0},{"c":" ","o":0.3},{"c":"world","o":0.5}]}]"""

        val enhanced = LyricsOnlineSearch.richSyncToEnhancedLrc(richSync)
        assertNotNull(enhanced)
        assertEquals(2, Regex("<").findAll(enhanced!!).count())
        val parsed = SyncedLyricsController.parse(enhanced)
        assertEquals(1, parsed.lines.size)
        assertEquals("hello world", parsed.lines.single().text)
        assertEquals(2, parsed.lines.single().karaokeSegments.size)
    }

    @Test fun richSyncRejectsChunksThatDoNotMatchDisplayedText() {
        val richSync = """[{"ts":10.0,"te":12.0,"x":"hello world","l":[{"c":"helloworld","o":0.0}]}]"""
        assertNull(LyricsOnlineSearch.richSyncToEnhancedLrc(richSync))
    }
}
