package org.telegram.messenger.localhistory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class LocalHistorySearchTest {

    private static LocalHistoryRepository.Entry entry(InMemoryRepository repo, int mid, int at, String name, String text) {
        LocalHistoryRepository.Entry e = new LocalHistoryRepository.Entry();
        e.sourceDialogId = 7;
        e.sourceMid = mid;
        e.firstSeenAt = at;
        e.lastEventAt = at;
        e.nameSnapshot = name;
        e.searchText = text;
        repo.insertEntry(e);
        return e;
    }

    @Test
    public void matchesAnyRevisionAndTheSenderNameIgnoringCase() {
        InMemoryRepository repo = new InMemoryRepository();
        entry(repo, 1, 10, "Anna", "see you at ПЯТНИЦА\nsee you at monday");
        entry(repo, 2, 20, "Boris", "unrelated");
        assertEquals(1, LocalHistorySearch.search(repo, "пятница", 50).size());
        assertEquals(1, LocalHistorySearch.search(repo, "MONDAY", 50).size());
        assertEquals(1, LocalHistorySearch.search(repo, "boris", 50).size());
        assertTrue(LocalHistorySearch.search(repo, "nothing", 50).isEmpty());
    }

    @Test
    public void emptyQueryFindsNothingAndResultsAreNewestFirstAndLimited() {
        InMemoryRepository repo = new InMemoryRepository();
        for (int i = 1; i <= 5; i++) {
            entry(repo, i, i * 10, "A", "hello " + i);
        }
        assertTrue(LocalHistorySearch.search(repo, "  ", 50).isEmpty());
        List<LocalHistoryRepository.Entry> found = LocalHistorySearch.search(repo, "hello", 3);
        assertEquals(3, found.size());
        assertEquals(50, found.get(0).lastEventAt);
        assertEquals(30, found.get(2).lastEventAt);
    }

    @Test
    public void scansMoreThanOnePage() {
        InMemoryRepository repo = new InMemoryRepository();
        for (int i = 1; i <= 450; i++) {
            entry(repo, i, i, "A", i == 3 ? "needle" : "hay");
        }
        List<LocalHistoryRepository.Entry> found = LocalHistorySearch.search(repo, "needle", 10);
        assertEquals(1, found.size());
        assertEquals(3, found.get(0).sourceMid);
    }
}
