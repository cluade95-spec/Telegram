package org.telegram.ui.Components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.telegram.messenger.SyncedLyricsController
import org.telegram.messenger.SyncedLyricsController.Line

/**
 * What may become karaoke lyrics, and what may not.
 *
 * [LyricsOnlineSearch.toEnhancedLrc] is the whole of that decision: it turns a word-timing
 * provider's response into the Enhanced LRC this app already parses, and returns null for every
 * response that does not genuinely state word timing. Nothing in it interpolates, divides a line
 * among its words, estimates from word length, or writes an end time no one stated - so the tests
 * below are mostly about what it refuses.
 *
 * Fixtures are written with ' where JSON needs " , purely so they stay readable.
 */
class LyricsKaraokeAcquisitionTest {

    private fun convert(body: String): String? = LyricsOnlineSearch.toEnhancedLrc(body.replace('\'', '"'))

    /** "start-end@time:[text]" per captured segment, or "-" when the line has no word timing. */
    private fun segments(line: Line): String {
        val segments = line.segments ?: return "-"
        return (0 until segments.size()).joinToString(" ") { i ->
            val start = segments.startOffset(i)
            val end = segments.endOffset(i)
            "$start-$end@${segments.startTimeMs(i)}:[${line.text.substring(start, end)}]"
        }
    }

    private fun wordTimed(type: String) =
        "{'type':'$type','lyrics':[{'time':1000,'text':'a b','syllabus':[" +
            "{'time':1000,'text':'a '},{'time':1500,'text':'b'}]}]}"

    // ------------------------------------------------------------------ the confirmed response

    @Test
    fun theConfirmedWordResponseConvertsToEnhancedLrc() {
        assertEquals(
            "[00:34.16]<00:34.16>You <00:34.50>leapt <00:35.10>from <00:35.46>crumbling <00:36.05>bridges",
            convert(APOCALYPSE)
        )
    }

    @Test
    fun theConvertedDocumentPlaysAsWordTimed() {
        val lyrics = SyncedLyricsController.parse(convert(APOCALYPSE))
        assertEquals(SyncedLyricsController.Kind.SYNCED, lyrics.kind)
        assertEquals(1, lyrics.lines.size)
        assertEquals("You leapt from crumbling bridges", lyrics.lines[0].text)
        assertEquals(34160L, lyrics.lines[0].timeMs)
        assertEquals(5, lyrics.lines[0].segments!!.size())
        assertEquals(
            "0-4@34160:[You ] 4-10@34500:[leapt ] 10-15@35100:[from ] " +
                "15-25@35460:[crumbling ] 25-32@36050:[bridges]",
            segments(lyrics.lines[0])
        )
    }

    // ------------------------------------------------------------------ what is refused

    @Test
    fun onlyAWordOrSyllableTypeIsAccepted() {
        assertNotNull(convert(wordTimed("Word")))
        assertNotNull(convert(wordTimed("WORD")))
        assertNotNull(convert(wordTimed("Syllable")))
        assertNull(convert(wordTimed("Line")))
        assertNull(convert(wordTimed("LINE")))
        assertNull(convert(wordTimed("Plain")))
        assertNull(convert(wordTimed("Karaoke")))
        assertNull(convert("{'lyrics':[{'time':1000,'text':'a'}]}"))
        assertNull(convert("{'type':1,'lyrics':[{'time':1000,'text':'a'}]}"))
    }

    @Test
    fun aResponseWithOnlyLineTimingIsNotKaraoke() {
        assertNull(convert("{'type':'Word','lyrics':[{'time':1000,'duration':500,'text':'just a line'}," +
            "{'time':2000,'duration':500,'text':'and another'}]}"))
        assertNull(convert("{'type':'Word','lyrics':[{'time':1000,'text':'a line','syllabus':null}]}"))
        assertNull(convert("{'type':'Word','lyrics':[{'time':1000,'text':'a line','syllabus':[]}]}"))
    }

    @Test
    fun oneTagCoveringEachWholeLineIsNotKaraoke() {
        // A tag that restates the line's own timestamp subdivides nothing. A document made only of
        // those is line timing wearing word timing's clothes, and is refused rather than served.
        assertNull(convert("{'type':'Word','lyrics':[" +
            "{'time':1000,'text':'a whole line','syllabus':[{'time':1000,'text':'a whole line'}]}," +
            "{'time':5000,'text':'and another','syllabus':[{'time':5000,'text':'and another'}]}]}"))
    }

    @Test
    fun malformedBodiesAreRefused() {
        assertNull(convert(""))
        assertNull(convert("hello"))
        assertNull(convert("{'type':'Word','lyrics':["))
        assertNull(convert("[{'time':1000}]"))
        assertNull(convert(wordTimed("Word") + " garbage"))
        assertNull(convert(wordTimed("Word") + " #}"))
        assertNull(convert("{'type':'Word','lyrics':'nope'}"))
        assertNull(convert("{'type':'Word','lyrics':[1,2]}"))
        assertNull(convert("{'type':'Word','lyrics':[{'time':1000,'text':'a','syllabus':[1]}]}"))
        assertNull(convert("{'type':'Word'}"))
        assertNull(convert("{'type':'Word','lyrics':[]}"))
    }

    @Test
    fun malformedWordTimingCostsWordTimingAndNeverTheText() {
        // The first line loses its word timing and keeps its own timestamp and text; the second is
        // untouched. A broken word list never takes a lyric away with it.
        assertEquals(
            "[00:01.00]late early\n[00:09.00]<00:09.00>c <00:09.50>d",
            convert("{'type':'Word','lyrics':[" +
                "{'time':1000,'text':'late early','syllabus':[{'time':5000,'text':'late '},{'time':2000,'text':'early'}]}," +
                "{'time':9000,'text':'c d','syllabus':[{'time':9000,'text':'c '},{'time':9500,'text':'d'}]}]}")
        )
        assertEquals(
            "[00:01.00]a b\n[00:05.00]<00:05.00>c <00:05.50>d",
            convert("{'type':'Word','lyrics':[" +
                "{'time':1000,'text':'a b','syllabus':[{'text':'a '},{'time':1500,'text':'b'}]}," +
                "{'time':5000,'text':'c d','syllabus':[{'time':5000,'text':'c '},{'time':5500,'text':'d'}]}]}")
        )
    }

    @Test
    fun aLineWithNoStatedTimeIsDropped() {
        assertEquals(
            "[00:05.00]<00:05.00>c <00:05.50>d",
            convert("{'type':'Word','lyrics':[{'text':'no time here'}," +
                "{'time':5000,'text':'c d','syllabus':[{'time':5000,'text':'c '},{'time':5500,'text':'d'}]}]}")
        )
    }

    // ------------------------------------------------------------------ nothing is invented

    @Test
    fun twoStatedWordsProduceExactlyTwoTags() {
        assertEquals(
            "[00:01.00]<00:01.00>a <00:01.40>b",
            convert("{'type':'Word','lyrics':[{'time':1000,'duration':9999,'text':'a b','syllabus':[" +
                "{'time':1000,'duration':400,'text':'a '},{'time':1400,'duration':600,'text':'b'}]}]}")
        )
    }

    @Test
    fun aStatedDurationNeverBecomesAnEndTime() {
        // duration is in the response and is deliberately unused: Enhanced LRC states starts only,
        // and an end time that nobody stated would be an invented one.
        val converted = convert("{'type':'Word','lyrics':[{'time':1000,'duration':500,'text':'a b','syllabus':[" +
            "{'time':1000,'duration':500,'text':'a '},{'time':1500,'duration':500,'text':'b'}]}]}")
        assertEquals("[00:01.00]<00:01.00>a <00:01.50>b", converted)
        assertEquals(2, SyncedLyricsController.parse(converted).lines[0].segments!!.size())
    }

    @Test
    fun timesTruncateAndNeverRoundUp() {
        // A word may be written as starting up to 9ms earlier than stated, never later.
        assertEquals(
            "[00:01.00]<00:01.00>a <00:01.99>b",
            convert("{'type':'Word','lyrics':[{'time':1009,'text':'a b','syllabus':[" +
                "{'time':1009,'text':'a '},{'time':1999,'text':'b'}]}]}")
        )
        assertEquals(
            "[62:03.45]<62:03.45>a <62:04.00>b",
            convert("{'type':'Word','lyrics':[{'time':3723456,'text':'a b','syllabus':[" +
                "{'time':3723456,'text':'a '},{'time':3724000,'text':'b'}]}]}")
        )
    }

    @Test
    fun aWordBeforeItsLineMovesTheLineToTheEarlierStatedTime() {
        // Both times came from the source; the line takes the earlier one so that every word time
        // survives exactly as stated instead of the line's timing being thrown away.
        val converted = convert("{'type':'Word','lyrics':[{'time':2000,'text':'a b','syllabus':[" +
            "{'time':1900,'text':'a '},{'time':2500,'text':'b'}]}]}")
        assertEquals("[00:01.90]<00:01.90>a <00:02.50>b", converted)
        assertEquals("0-2@1900:[a ] 2-3@2500:[b]", segments(SyncedLyricsController.parse(converted).lines[0]))
    }

    // ------------------------------------------------------------------ text fidelity

    @Test
    fun aMixedDocumentKeepsLineTimingWhereThereIsNoWordTiming() {
        val converted = convert("{'type':'Word','lyrics':[" +
            "{'time':1000,'text':'sung word by word','syllabus':[{'time':1000,'text':'sung '},{'time':1400,'text':'word by word'}]}," +
            "{'time':5000,'text':'only a line here'}]}")
        val lyrics = SyncedLyricsController.parse(converted)
        assertEquals(2, lyrics.lines.size)
        assertNotNull(lyrics.lines[0].segments)
        assertNull(lyrics.lines[1].segments)
        assertEquals("only a line here", lyrics.lines[1].text)
        assertEquals(5000L, lyrics.lines[1].timeMs)
    }

    @Test
    fun theWordsAreTheTextEvenWhenTheRowDisagrees() {
        // The offsets address the words, so the words are what the line says.
        assertEquals(
            "[00:01.00]<00:01.00>right <00:01.50>words",
            convert("{'type':'Word','lyrics':[{'time':1000,'text':'WRONG','syllabus':[" +
                "{'time':1000,'text':'right '},{'time':1500,'text':'words'}]}]}")
        )
    }

    @Test
    fun unicodeIsTranscribedExactly() {
        assertEquals(
            "[00:01.00]<00:01.00>love <00:01.50>\ud83d\udc9c",
            convert("{'type':'Word','lyrics':[{'time':1000,'text':'love \ud83d\udc9c','syllabus':[" +
                "{'time':1000,'text':'love '},{'time':1500,'text':'\ud83d\udc9c'}]}]}")
        )
        assertEquals(
            "[00:01.00]<00:01.00>\u12a0\u1263 <00:01.50>\u12cd",
            convert("{'type':'Word','lyrics':[{'time':1000,'text':'\u12a0\u1263 \u12cd','syllabus':[" +
                "{'time':1000,'text':'\u12a0\u1263 '},{'time':1500,'text':'\u12cd'}]}]}")
        )
        assertEquals(
            "[00:01.00]<00:01.00>\u0645\u0631\u062d\u0628\u0627 <00:01.50>\u0628\u0643",
            convert("{'type':'Word','lyrics':[{'time':1000,'text':'x','syllabus':[" +
                "{'time':1000,'text':'\u0645\u0631\u062d\u0628\u0627 '},{'time':1500,'text':'\u0628\u0643'}]}]}")
        )
        assertEquals(
            "[00:01.00]<00:01.00>cafe\u0301 <00:01.50>nai\u0308ve",
            convert("{'type':'Word','lyrics':[{'time':1000,'text':'x','syllabus':[" +
                "{'time':1000,'text':'cafe\u0301 '},{'time':1500,'text':'nai\u0308ve'}]}]}")
        )
    }

    @Test
    fun aLoneSurrogateIsRefused() {
        assertNull(convert("{'type':'Word','lyrics':[{'time':1000,'text':'a','syllabus':[" +
            "{'time':1000,'text':'\ud83d'},{'time':1500,'text':'b'}]}]}"))
    }

    @Test
    fun repeatedWordsStayPositional() {
        val converted = convert("{'type':'Word','lyrics':[{'time':1000,'text':'na na na','syllabus':[" +
            "{'time':1000,'text':'na '},{'time':1200,'text':'na '},{'time':1400,'text':'na'}]}]}")
        assertEquals("[00:01.00]<00:01.00>na <00:01.20>na <00:01.40>na", converted)
        assertEquals(
            "0-3@1000:[na ] 3-6@1200:[na ] 6-8@1400:[na]",
            segments(SyncedLyricsController.parse(converted).lines[0])
        )
    }

    @Test
    fun punctuationSurvives() {
        assertEquals(
            "[00:01.00]<00:01.00>Do not <00:01.30>stop, <00:01.60>now!",
            convert("{'type':'Word','lyrics':[{'time':1000,'text':'x','syllabus':[" +
                "{'time':1000,'text':'Do not '},{'time':1300,'text':'stop, '},{'time':1600,'text':'now!'}]}]}")
        )
    }

    @Test
    fun textThatCouldNotBeWrittenAsLrcIsDroppedRatherThanCorrupted() {
        // A line break cannot live on one LRC line, and text shaped like a timestamp would be read
        // back as timing rather than as words. Both drop the line instead of writing it broken.
        assertEquals(
            "[00:05.00]<00:05.00>c <00:05.50>d",
            convert("{'type':'Word','lyrics':[{'time':1000,'text':'x','syllabus':[{'time':1000,'text':'a\\nb'},{'time':1500,'text':'c'}]}," +
                "{'time':5000,'text':'c d','syllabus':[{'time':5000,'text':'c '},{'time':5500,'text':'d'}]}]}")
        )
        assertEquals(
            "[00:05.00]<00:05.00>c <00:05.50>d",
            convert("{'type':'Word','lyrics':[{'time':1000,'text':'x','syllabus':[{'time':1000,'text':'<00:09.99>'},{'time':1500,'text':'b'}]}," +
                "{'time':5000,'text':'c d','syllabus':[{'time':5000,'text':'c '},{'time':5500,'text':'d'}]}]}")
        )
        assertEquals(
            "[00:05.00]<00:05.00>c <00:05.50>d",
            convert("{'type':'Word','lyrics':[{'time':1000,'text':'[00:09.99] hey'}," +
                "{'time':5000,'text':'c d','syllabus':[{'time':5000,'text':'c '},{'time':5500,'text':'d'}]}]}")
        )
    }

    @Test
    fun textThatMerelyLooksLikeTimingIsKept() {
        assertEquals(
            "[00:01.00]<00:01.00>see <3 <00:01.50>you [chorus]",
            convert("{'type':'Word','lyrics':[{'time':1000,'text':'x','syllabus':[" +
                "{'time':1000,'text':'see <3 '},{'time':1500,'text':'you [chorus]'}]}]}")
        )
    }

    // ------------------------------------------------------------------ the imported file

    @Test
    fun aFileAndAPasteOfTheSameLyricsParseIdentically() {
        // Import reads a file's bytes as UTF-8 and hands the parser the same string a paste would,
        // so the two cannot diverge. This is what the .lrc picker fix restored access to.
        val pasted = convert(APOCALYPSE)!!
        val fromFile = String(pasted.toByteArray(Charsets.UTF_8), Charsets.UTF_8)
        assertEquals(pasted, fromFile)
        val a = SyncedLyricsController.parse(pasted)
        val b = SyncedLyricsController.parse(fromFile)
        assertEquals(a.kind, b.kind)
        assertEquals(a.lines.size, b.lines.size)
        for (i in a.lines.indices) {
            assertEquals(a.lines[i].text, b.lines[i].text)
            assertEquals(a.lines[i].timeMs, b.lines[i].timeMs)
            assertEquals(segments(a.lines[i]), segments(b.lines[i]))
        }
    }

    @Test
    fun windowsLineEndingsFromAFileParseTheSame() {
        val unix = "[00:01.00]<00:01.00>a <00:01.50>b\n[00:05.00]<00:05.00>c <00:05.50>d"
        val windows = unix.replace("\n", "\r\n")
        val a = SyncedLyricsController.parse(unix)
        val b = SyncedLyricsController.parse(windows)
        assertEquals(a.kind, b.kind)
        assertEquals(a.lines.size, b.lines.size)
        for (i in a.lines.indices) {
            assertEquals(a.lines[i].text, b.lines[i].text)
            assertEquals(segments(a.lines[i]), segments(b.lines[i]))
        }
    }

    @Test
    fun aByteOrderMarkHasToBeStrippedBeforeParsing() {
        // Why Import strips it: a BOM left in front of the first timestamp stops that line being
        // read as timed at all, which would turn a word-timed file into plain text.
        val text = "[00:01.00]<00:01.00>a <00:01.50>b"
        assertEquals(SyncedLyricsController.Kind.SYNCED, SyncedLyricsController.parse(text).kind)
        val withBom = "\ufeff" + text
        assertTrue(SyncedLyricsController.parse(withBom).kind != SyncedLyricsController.Kind.SYNCED)
        assertEquals(SyncedLyricsController.Kind.SYNCED, SyncedLyricsController.parse(withBom.substring(1)).kind)
    }

    private companion object {
        /** The response a real device received for Cigarettes After Sex - Apocalypse, source=apple. */
        const val APOCALYPSE =
            "{'type':'Word'," +
                "'metadata':{'source':'qApple','title':'Apocalypse','artist':'Cigarettes After Sex'," +
                "'album':'Cigarettes After Sex','durationMs':290147,'isrc':'USBQU1700034'}," +
                "'lyrics':[{'time':34160,'duration':2590,'text':'You leapt from crumbling bridges'," +
                "'element':{'key':'L1','songPartIndex':0},'syllabus':[" +
                "{'time':34160,'duration':340,'text':'You '}," +
                "{'time':34500,'duration':600,'text':'leapt '}," +
                "{'time':35100,'duration':360,'text':'from '}," +
                "{'time':35460,'duration':590,'text':'crumbling '}," +
                "{'time':36050,'duration':700,'text':'bridges'}]}]}"
    }
}
