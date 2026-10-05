package org.telegram.messenger.localhistory;

import java.util.ArrayList;
import java.util.List;

/** Orders and describes the revisions of one entry for the version-history sheet (plan B9). Pure. */
public final class LocalHistoryVersions {

    private LocalHistoryVersions() {
    }

    /** Oldest first, by revision index. */
    public static List<LocalHistoryRepository.Revision> ordered(List<LocalHistoryRepository.Revision> revisions) {
        List<LocalHistoryRepository.Revision> out = new ArrayList<>(revisions);
        out.sort((a, b) -> Integer.compare(a.idx, b.idx));
        return out;
    }

    /**
     * True when the first state we saw was already an edit: the message had been edited before we started to watch,
     * so its older versions are unknown.
     */
    public static boolean earlierNotSeen(List<LocalHistoryRepository.Revision> revisions) {
        List<LocalHistoryRepository.Revision> ordered = ordered(revisions);
        return !ordered.isEmpty() && ordered.get(0).kind == LocalHistoryRepository.KIND_ORIGINAL && ordered.get(0).editDate != 0;
    }

    /** Number of the edit for the label "Edit n": the count of EDIT revisions up to and including this one. */
    public static int editNumber(List<LocalHistoryRepository.Revision> ordered, int position) {
        int n = 0;
        for (int i = 0; i <= position && i < ordered.size(); i++) {
            if (ordered.get(i).kind == LocalHistoryRepository.KIND_EDIT) {
                n++;
            }
        }
        return n;
    }
}
