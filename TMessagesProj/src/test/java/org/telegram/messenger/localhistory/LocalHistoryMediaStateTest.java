package org.telegram.messenger.localhistory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.telegram.messenger.localhistory.LocalHistoryMediaManager.Files;
import org.telegram.messenger.localhistory.LocalHistoryMediaManager.Holds;
import org.telegram.messenger.localhistory.LocalHistoryRepository.Media;

public class LocalHistoryMediaStateTest {

    private static class FakeFiles implements Files {
        final Map<String, Long> sizes = new HashMap<>();
        boolean copyFails;

        @Override
        public boolean exists(String path) {
            return sizes.containsKey(path);
        }

        @Override
        public long size(String path) {
            return sizes.get(path);
        }

        @Override
        public boolean copy(String source, String target) {
            if (copyFails || !sizes.containsKey(source)) {
                return false;
            }
            sizes.put(target, sizes.get(source));
            return true;
        }

        @Override
        public void delete(String path) {
            sizes.remove(path);
        }

        @Override
        public String targetFor(long entryId, int revisionIdx, long mediaId, String sourcePath) {
            return "/lh/" + entryId + "_" + revisionIdx + "_" + mediaId;
        }
    }

    private static class FakeHolds implements Holds {
        final Set<String> held = new HashSet<>();

        @Override
        public void hold(String path) {
            held.add(path);
        }

        @Override
        public void release(String path) {
            held.remove(path);
        }
    }

    private InMemoryRepository repo;
    private FakeFiles files;
    private FakeHolds holds;
    private LocalHistoryMediaManager manager;

    @Before
    public void setUp() {
        repo = new InMemoryRepository();
        files = new FakeFiles();
        holds = new FakeHolds();
        manager = new LocalHistoryMediaManager(repo, files, holds, 100, 250);
    }

    private Media only(long entryId) {
        return repo.mediaForEntry(entryId).get(0);
    }

    @Test
    public void missingFileIsNotDownloadedAndNeverHeld() {
        assertEquals(LocalHistoryMediaState.NOT_DOWNLOADED, manager.register(1, 0, LocalHistoryMediaState.KIND_PHOTO, "/cache/a", 10));
        assertTrue(holds.held.isEmpty());
        assertEquals(LocalHistoryMediaState.NOT_DOWNLOADED, only(1).state);
    }

    @Test
    public void oversizedFileIsTooLargeAndNotHeld() {
        files.sizes.put("/cache/a", 101L);
        assertEquals(LocalHistoryMediaState.TOO_LARGE, manager.register(1, 0, LocalHistoryMediaState.KIND_DOCUMENT, "/cache/a", 10));
        assertTrue(holds.held.isEmpty());
        assertTrue(files.exists("/cache/a")); // Telegram deletes it, we do not touch it
    }

    @Test
    public void existingFileIsHeldUntilTheCopyIsSafe() {
        files.sizes.put("/cache/a", 40L);
        assertEquals(LocalHistoryMediaState.PENDING_COPY, manager.register(1, 0, LocalHistoryMediaState.KIND_PHOTO, "/cache/a", 10));
        assertTrue(holds.held.contains("/cache/a"));
        manager.processPending();
        Media m = only(1);
        assertEquals(LocalHistoryMediaState.PRESERVED, m.state);
        assertTrue(files.exists(m.localPath));
        assertFalse("original goes once the copy exists", files.exists("/cache/a"));
        assertFalse(holds.held.contains("/cache/a"));
    }

    @Test
    public void sourceThatVanishedBeforeTheCopyIsLost() {
        files.sizes.put("/cache/a", 40L);
        manager.register(1, 0, LocalHistoryMediaState.KIND_PHOTO, "/cache/a", 10);
        files.delete("/cache/a"); // e.g. cache cleanup during the copy window
        manager.processPending();
        assertEquals(LocalHistoryMediaState.LOST, only(1).state);
        assertTrue(holds.held.isEmpty());
    }

    @Test
    public void failedCopyIsLostAndKeepsTheOriginal() {
        files.sizes.put("/cache/a", 40L);
        manager.register(1, 0, LocalHistoryMediaState.KIND_PHOTO, "/cache/a", 10);
        files.copyFails = true;
        manager.processPending();
        assertEquals(LocalHistoryMediaState.LOST, only(1).state);
        assertTrue(files.exists("/cache/a"));
        assertTrue(holds.held.isEmpty());
    }

    @Test
    public void budgetEvictsTheOldestPreservedFirst() {
        for (int i = 1; i <= 3; i++) {
            files.sizes.put("/cache/" + i, 100L);
            manager.register(i, 0, LocalHistoryMediaState.KIND_PHOTO, "/cache/" + i, i);
        }
        manager.processPending(); // 300 > 250: the first one is evicted while the third is copied
        assertEquals(LocalHistoryMediaState.EVICTED, only(1).state);
        assertNull(only(1).localPath);
        assertEquals(LocalHistoryMediaState.PRESERVED, only(2).state);
        assertEquals(LocalHistoryMediaState.PRESERVED, only(3).state);
        assertEquals(200, repo.preservedBytes());
    }

    @Test
    public void fileLargerThanTheWholeBudgetIsOverBudget() {
        LocalHistoryMediaManager small = new LocalHistoryMediaManager(repo, files, holds, 1000, 50);
        files.sizes.put("/cache/a", 60L);
        small.register(1, 0, LocalHistoryMediaState.KIND_DOCUMENT, "/cache/a", 10);
        small.processPending();
        assertEquals(LocalHistoryMediaState.OVER_BUDGET, only(1).state);
        assertTrue(holds.held.isEmpty());
    }

    @Test
    public void recoveryReholdsPendingRowsAndMarksVanishedOnesLost() {
        files.sizes.put("/cache/a", 40L);
        files.sizes.put("/cache/b", 40L);
        manager.register(1, 0, LocalHistoryMediaState.KIND_PHOTO, "/cache/a", 10);
        manager.register(2, 0, LocalHistoryMediaState.KIND_PHOTO, "/cache/b", 11);
        holds.held.clear(); // process died: holds were in memory only
        files.delete("/cache/b");
        FakeFiles frozen = files;
        frozen.copyFails = true; // keep the first one pending after recover's own processing
        manager.recover();
        assertEquals(LocalHistoryMediaState.LOST, only(2).state);
        assertEquals(LocalHistoryMediaState.LOST, only(1).state); // copy failed, so it ends LOST and released
        assertTrue(holds.held.isEmpty());
    }

    @Test
    public void recoveryReholdsWhileTheCopyIsStillPending() {
        files.sizes.put("/cache/a", 40L);
        manager.register(1, 0, LocalHistoryMediaState.KIND_PHOTO, "/cache/a", 10);
        holds.held.clear();
        for (Media m : repo.mediaByState(LocalHistoryMediaState.PENDING_COPY)) {
            holds.hold(m.sourcePath); // what recover() does before copying
        }
        assertTrue(holds.held.contains("/cache/a"));
        manager.recover();
        assertEquals(LocalHistoryMediaState.PRESERVED, only(1).state);
        assertTrue(holds.held.isEmpty());
    }

    @Test
    public void deletingAnEntryRemovesItsPreservedFiles() {
        files.sizes.put("/cache/a", 40L);
        manager.register(1, 0, LocalHistoryMediaState.KIND_PHOTO, "/cache/a", 10);
        manager.processPending();
        String local = only(1).localPath;
        assertTrue(files.exists(local));
        manager.deleteFilesOf(1);
        assertFalse(files.exists(local));
    }
}
