package org.telegram.ui.Components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which AMLL TTML Database rows may answer a karaoke request, and which may not.
 *
 * A Telegram audio file states a title and a performer and nothing else - no album, no catalogue
 * id, no ISRC - so those two fields, plus the document's own stated times measured against the
 * track's duration, are the whole of what there is to judge a candidate with. The tests below are
 * mostly about what that is enough to refuse: a live take, a remix, a sped-up edit, a re-recording
 * and a different artist all share a title with the recording being played, and animating any of
 * them word by word would be worse than falling back to line-synced lyrics that are right.
 *
 * Fixtures are written with ' where JSON needs " , purely so they stay readable.
 */
class LyricsAmllMatchingTest {

    private fun item(id: String, names: String, artists: String, albums: String = ""): String {
        fun arr(csv: String) = if (csv.isEmpty()) "[]" else csv.split("|").joinToString(",", "[", "]") { "'$it'" }
        return "{'id':'$id','musicNames':${arr(names)},'artistNames':${arr(artists)},'albumNames':${arr(albums)}}"
    }

    private fun page(vararg items: String): String =
        "{'status':200,'data':{'items':[${items.joinToString(",")}]," +
            "'pagination':{'page':1,'pageSize':20,'total':${items.size},'totalPages':1,'hasMore':false}}}"

    private fun rank(body: String, artist: String, title: String): List<String>? =
        LyricsOnlineSearch.rankAmllCandidates(body.replace('\'', '"'), artist, title)

    private fun accepts(title: String, artist: String, names: String, artists: String, albums: String = ""): Boolean =
        rank(page(item("x", names, artists, albums)), artist, title)!!.isNotEmpty()

    private fun ttml(body: String): String =
        """<tt xmlns="http://www.w3.org/ns/ttml"><body><div>$body</div></body></tt>"""

    private val wordTimed = ttml(
        """<p begin="00:00.000" end="00:02.000">""" +
            """<span begin="00:00.000" end="00:01.000">Hello </span>""" +
            """<span begin="00:01.000" end="00:02.000">world</span></p>""")

    // ------------------------------------------------------------------ what is accepted

    @Test
    fun theSameRecordingIsAccepted() {
        assertTrue(accepts("Song", "Artist", "Song", "Artist", "Album"))
    }

    @Test
    fun aRemasterIsTheSamePerformance() {
        assertTrue(accepts("Song", "Artist", "Song (2011 Remastered Version)", "Artist"))
        assertTrue(accepts("Song", "Artist", "Song - Remastered", "Artist"))
        assertTrue(accepts("Song", "Artist", "Song (Album Version)", "Artist"))
    }

    @Test
    fun creditsMayBeWrittenEitherWay() {
        assertTrue(accepts("Song", "Calvin Harris & Dua Lipa", "Song", "Calvin Harris|Dua Lipa"))
        assertTrue(accepts("Song", "Artist feat. Other", "Song", "Artist"))
        assertTrue("a tag that omits the feature still matches",
            accepts("Song", "Beyonce", "Song", "Beyonce|Jay-Z"))
    }

    @Test
    fun aLeadingArticleIsNotADifferentBand() {
        assertTrue(accepts("Song", "Beatles", "Song", "The Beatles"))
        assertTrue(accepts("The Scientist", "Coldplay", "Scientist", "Coldplay"))
    }

    @Test
    fun askingForALiveTakeFindsTheLiveTake() {
        assertTrue(accepts("Song (Live)", "Artist", "Song (Live)", "Artist", "Live At Wembley"))
    }

    // ------------------------------------------------------------------ what is refused

    @Test
    fun aDifferentRecordingOfTheSameSongIsRefused() {
        assertFalse("live", accepts("Song", "Artist", "Song (Live)", "Artist"))
        assertFalse("remix", accepts("Song", "Artist", "Song (Dance Remix)", "Artist"))
        assertFalse("acoustic", accepts("Song", "Artist", "Song - Acoustic", "Artist"))
        assertFalse("instrumental", accepts("Song", "Artist", "Song - Instrumental", "Artist"))
        assertFalse("sped up", accepts("Song", "Artist", "Song - Sped Up", "Artist"))
        assertFalse("slowed and reverbed", accepts("Song", "Artist", "Song (Slowed + Reverb)", "Artist"))
        assertFalse("cover", accepts("Song", "Artist", "Song (Cover)", "Artist"))
        assertFalse("a re-recording", accepts("Love Story", "Taylor Swift", "Love Story (Taylor\u2019s Version)", "Taylor Swift"))
    }

    @Test
    fun theAlbumCanGiveAwayALiveTakeTheTitleDoesNot() {
        assertFalse(accepts("Song", "Artist", "Song", "Artist", "Live At Wembley"))
        assertTrue(accepts("Song", "Artist", "Song", "Artist", "Greatest Hits"))
    }

    @Test
    fun askingForTheStudioCutRefusesTheLiveOne() {
        assertFalse(accepts("Song (Live)", "Artist", "Song", "Artist", "Album"))
    }

    @Test
    fun aDifferentArtistIsRefused() {
        assertFalse(accepts("Song", "Artist", "Song", "Someone Else"))
        assertFalse("half a name is not the name", accepts("Song", "Artist", "Song", "Other Artist"))
        assertFalse("the lead has to be credited", accepts("Song", "Artist feat. Other", "Song", "Other"))
    }

    @Test
    fun aDifferentTitleIsRefused() {
        assertFalse(accepts("Song", "Artist", "Different Thing Entirely", "Artist"))
    }

    @Test
    fun withNoArtistToCheckNothingClearsTheThreshold() {
        assertFalse(accepts("Song", "", "Song", "Artist"))
    }

    // ------------------------------------------------------------------ the response itself

    @Test
    fun rowsAreRankedAndTheWrongOnesDropped() {
        val ids = rank(page(
            item("a", "Song (Live)", "Artist", "Live At Wembley"),
            item("b", "Song", "Artist", "Greatest Hits"),
            item("c", "Song", "Other Artist"),
            item("d", "Song (2011 Remaster)", "Artist", "Greatest Hits")), "Artist", "Song")!!
        assertEquals(listOf("b", "d"), ids)
    }

    @Test
    fun flatterShapesAreStillRead() {
        assertEquals(1, rank("{'items':[${item("z", "Song", "Artist")}]}", "Artist", "Song")!!.size)
        assertEquals(1, rank("[${item("z", "Song", "Artist")}]", "Artist", "Song")!!.size)
        assertEquals(1, rank("{'data':[${item("z", "Song", "Artist")}]}", "Artist", "Song")!!.size)
    }

    @Test
    fun anUnreadableResponseIsNotAnEmptyOne() {
        assertNull(rank("not json", "Artist", "Song"))
        assertNull("trailing junk", rank(page(item("z", "Song", "Artist")) + " x", "Artist", "Song"))
        assertTrue("an empty page is empty", rank(page(), "Artist", "Song")!!.isEmpty())
    }

    @Test
    fun anIdThatCouldSteerTheRequestIsRefused() {
        assertTrue(rank("{'items':[{'id':'../../etc','musicNames':['Song'],'artistNames':['Artist']}]}", "Artist", "Song")!!.isEmpty())
        assertTrue(rank("{'items':[{'id':'1&format=lrc','musicNames':['Song'],'artistNames':['Artist']}]}", "Artist", "Song")!!.isEmpty())
        assertEquals("a numeric id is fine", 1,
            rank("{'items':[{'id':7,'musicNames':['Song'],'artistNames':['Artist']}]}", "Artist", "Song")!!.size)
    }

    // ------------------------------------------------------------------ the document

    @Test
    fun theDocumentIsTakenFromTheDocumentedEnvelope() {
        val quoted = wordTimed.replace("\"", "\\\"")
        assertEquals(wordTimed, LyricsOnlineSearch.readAmllDocument("""{"status":200,"data":{"format":"ttml","lyrics":"$quoted"}}"""))
        assertEquals("a bare document is accepted too", wordTimed, LyricsOnlineSearch.readAmllDocument(wordTimed))
    }

    @Test
    fun aFormatThisCannotReadIsRefused() {
        assertNull(LyricsOnlineSearch.readAmllDocument("""{"data":{"format":"lrc","lyrics":"[00:01.00]hi"}}"""))
        assertNull(LyricsOnlineSearch.readAmllDocument("""{"data":{"format":"ttml"}}"""))
    }

    @Test
    fun onlyAGenuinelyWordTimedDocumentAnswersAKaraokeRequest() {
        assertTrue(LyricsOnlineSearch.isWordTimedTtml(wordTimed, 180.0))
        assertFalse("line-only TTML is not karaoke", LyricsOnlineSearch.isWordTimedTtml(
            ttml("""<p begin="00:00.000" end="00:02.000">Hello world</p>"""), 180.0))
        assertFalse("nor is something that is not TTML", LyricsOnlineSearch.isWordTimedTtml("[00:01.00]hello", 180.0))
        assertFalse("nor is an empty document", LyricsOnlineSearch.isWordTimedTtml(ttml(""), 180.0))
    }

    @Test
    fun aDocumentTimedAgainstALongerCutIsRefused() {
        val late = ttml(
            """<p begin="00:00.000"><span begin="00:00.000" end="00:01.000">Hi </span>""" +
                """<span begin="00:01.000" end="00:02.000">there</span></p>""" +
                """<p begin="04:00.000"><span begin="04:00.000" end="04:01.000">Late </span>""" +
                """<span begin="04:01.000" end="04:02.000">words</span></p>""")
        assertFalse("its words outlive a three-minute track", LyricsOnlineSearch.isWordTimedTtml(late, 180.0))
        assertTrue("and fit a five-minute one", LyricsOnlineSearch.isWordTimedTtml(late, 300.0))
    }

    @Test
    fun anUnknownDurationCannotRefuseAnything() {
        assertTrue(LyricsOnlineSearch.isWordTimedTtml(wordTimed, -1.0))
    }
}
