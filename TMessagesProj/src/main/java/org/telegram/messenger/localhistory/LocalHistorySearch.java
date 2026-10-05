package org.telegram.messenger.localhistory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * In-chat search over the archive (plan B20). Matching is done here rather than with SQL LIKE so that case folding
 * works for every script, not only ASCII. Pure.
 */
public final class LocalHistorySearch {

    private LocalHistorySearch() {
    }

    /** Lowercased, trimmed query; empty means "no search". */
    public static String normalize(String query) {
        return query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean matches(LocalHistoryRepository.Entry entry, String normalizedQuery) {
        if (normalizedQuery.isEmpty()) {
            return true;
        }
        return contains(entry.searchText, normalizedQuery) || contains(entry.nameSnapshot, normalizedQuery);
    }

    private static boolean contains(String text, String normalizedQuery) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(normalizedQuery);
    }

    /** Newest first, at most {@code limit} entries; scans the archive in pages so memory stays flat. */
    public static List<LocalHistoryRepository.Entry> search(LocalHistoryRepository repo, String query, int limit) {
        final String q = normalize(query);
        List<LocalHistoryRepository.Entry> out = new ArrayList<>();
        if (q.isEmpty()) {
            return out;
        }
        int beforeAt = Integer.MAX_VALUE;
        long beforeId = Long.MAX_VALUE;
        while (out.size() < limit) {
            List<LocalHistoryRepository.Entry> page = repo.pageFeedBefore(beforeAt, beforeId, 200);
            if (page.isEmpty()) {
                break;
            }
            for (LocalHistoryRepository.Entry e : page) {
                if (matches(e, q) && out.size() < limit) {
                    out.add(e);
                }
            }
            LocalHistoryRepository.Entry last = page.get(page.size() - 1);
            beforeAt = last.lastEventAt;
            beforeId = last.id;
            if (page.size() < 200) {
                break;
            }
        }
        return out;
    }
}
