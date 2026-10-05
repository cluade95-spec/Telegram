package org.telegram.messenger.localhistory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class LocalHistoryVersionsTest {

    private static LocalHistoryRepository.Revision rev(int idx, int kind, int editDate) {
        LocalHistoryRepository.Revision r = new LocalHistoryRepository.Revision();
        r.idx = idx;
        r.kind = kind;
        r.editDate = editDate;
        return r;
    }

    @Test
    public void ordersOldestFirstAndNumbersEdits() {
        List<LocalHistoryRepository.Revision> in = new ArrayList<>();
        in.add(rev(2, LocalHistoryRepository.KIND_EDIT, 30));
        in.add(rev(0, LocalHistoryRepository.KIND_ORIGINAL, 0));
        in.add(rev(1, LocalHistoryRepository.KIND_EDIT, 20));
        List<LocalHistoryRepository.Revision> ordered = LocalHistoryVersions.ordered(in);
        assertEquals(0, ordered.get(0).idx);
        assertEquals(2, ordered.get(2).idx);
        assertEquals(0, LocalHistoryVersions.editNumber(ordered, 0));
        assertEquals(1, LocalHistoryVersions.editNumber(ordered, 1));
        assertEquals(2, LocalHistoryVersions.editNumber(ordered, 2));
    }

    @Test
    public void earlierVersionsAreNotSeenWhenTheFirstStateWasAlreadyEdited() {
        List<LocalHistoryRepository.Revision> seen = new ArrayList<>();
        seen.add(rev(0, LocalHistoryRepository.KIND_ORIGINAL, 0));
        seen.add(rev(1, LocalHistoryRepository.KIND_EDIT, 20));
        assertFalse(LocalHistoryVersions.earlierNotSeen(seen));
        List<LocalHistoryRepository.Revision> late = new ArrayList<>();
        late.add(rev(0, LocalHistoryRepository.KIND_ORIGINAL, 15));
        late.add(rev(1, LocalHistoryRepository.KIND_EDIT, 20));
        assertTrue(LocalHistoryVersions.earlierNotSeen(late));
        assertFalse(LocalHistoryVersions.earlierNotSeen(new ArrayList<>()));
    }
}
