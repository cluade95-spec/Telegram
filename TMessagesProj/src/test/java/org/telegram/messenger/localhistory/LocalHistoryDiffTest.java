package org.telegram.messenger.localhistory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.telegram.messenger.localhistory.LocalHistoryDiff.Content;

public class LocalHistoryDiffTest {

    private static Content c(String text, String entities, String media) {
        return new Content(text, entities, media);
    }

    @Test
    public void unchangedContentIsNotAChange() {
        assertFalse(LocalHistoryDiff.isContentChange(c("hi", "", ""), c("hi", "", "")));
    }

    @Test
    public void markupOnlyEditsAreNotChanges() {
        // reply markup, web preview fill and reactions are not part of Content, so identical content stays identical
        Content before = c("see https://a.example", "url:4:17", "");
        Content afterPreviewFilled = c("see https://a.example", "url:4:17", "");
        assertFalse(LocalHistoryDiff.isContentChange(before, afterPreviewFilled));
    }

    @Test
    public void textEntityAndMediaChangesAreChanges() {
        assertTrue(LocalHistoryDiff.isContentChange(c("hi", "", ""), c("hello", "", "")));
        assertTrue(LocalHistoryDiff.isContentChange(c("hi", "", ""), c("hi", "bold:0:2", "")));
        assertTrue(LocalHistoryDiff.isContentChange(c("hi", "", "photo:1"), c("hi", "", "photo:2")));
        assertTrue(LocalHistoryDiff.isContentChange(c("", "", "photo:1"), c("", "", "document:9")));
        assertTrue(LocalHistoryDiff.isContentChange(c("cap", "", "photo:1"), c("cap2", "", "photo:1")));
    }

    @Test
    public void lineEndingsAreNormalized() {
        assertFalse(LocalHistoryDiff.isContentChange(c("a\r\nb", "", ""), c("a\nb", "", "")));
    }

    @Test
    public void hashFollowsContentAndIgnoresNothingElse() {
        assertEquals(LocalHistoryDiff.contentHash(c("hi", "", "")), LocalHistoryDiff.contentHash(c("hi", "", "")));
        assertNotEquals(LocalHistoryDiff.contentHash(c("hi", "", "")), LocalHistoryDiff.contentHash(c("hi!", "", "")));
        assertNotEquals(LocalHistoryDiff.contentHash(c("ab", "", "")), LocalHistoryDiff.contentHash(c("a", "b", "")));
        assertNotEquals(LocalHistoryDiff.contentHash(c("hi", "", "photo:1")), LocalHistoryDiff.contentHash(c("hi", "", "photo:2")));
    }
}
