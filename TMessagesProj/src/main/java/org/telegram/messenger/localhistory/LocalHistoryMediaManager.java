package org.telegram.messenger.localhistory;

import java.util.List;

/**
 * Hold, copy, budget and evict (plan B10). Pure: files and holds are ports, so every transition is testable.
 * Call {@link #register} on the storage thread before Telegram deletes the file, {@link #processPending} later on
 * the feature queue.
 */
public class LocalHistoryMediaManager {

    public interface Files {
        boolean exists(String path);

        long size(String path);

        /** Copies completely or not at all (partial file, fsync, rename). */
        boolean copy(String source, String target);

        void delete(String path);

        String targetFor(long entryId, int revisionIdx, long mediaId, String sourcePath);
    }

    public interface Holds {
        void hold(String path);

        void release(String path);
    }

    private final LocalHistoryRepository repo;
    private final Files files;
    private final Holds holds;
    private final long maxFileBytes;
    private final long budgetBytes;

    public LocalHistoryMediaManager(LocalHistoryRepository repo, Files files, Holds holds) {
        this(repo, files, holds, LocalHistoryMediaState.MAX_FILE_BYTES, LocalHistoryMediaState.BUDGET_BYTES);
    }

    public LocalHistoryMediaManager(LocalHistoryRepository repo, Files files, Holds holds, long maxFileBytes, long budgetBytes) {
        this.repo = repo;
        this.files = files;
        this.holds = holds;
        this.maxFileBytes = maxFileBytes;
        this.budgetBytes = budgetBytes;
    }

    /**
     * Records one media file of an archived revision and decides its first state. A file that exists and fits is held so
     * Telegram's own deletion skips it until the copy is made. Returns the state.
     */
    public int register(long entryId, int revisionIdx, int kind, String sourcePath, int now) {
        LocalHistoryRepository.Media media = new LocalHistoryRepository.Media();
        media.entryId = entryId;
        media.revisionIdx = revisionIdx;
        media.kind = kind;
        media.sourcePath = sourcePath;
        media.createdAt = now;
        if (sourcePath == null || !files.exists(sourcePath)) {
            media.state = LocalHistoryMediaState.NOT_DOWNLOADED;
        } else {
            media.size = files.size(sourcePath);
            if (media.size > maxFileBytes) {
                media.state = LocalHistoryMediaState.TOO_LARGE;
            } else {
                media.state = LocalHistoryMediaState.PENDING_COPY;
                holds.hold(sourcePath);
            }
        }
        repo.insertMedia(media);
        return media.state;
    }

    /** Copies every pending file, evicting the oldest preserved media when the budget requires it. */
    public void processPending() {
        for (LocalHistoryRepository.Media media : repo.mediaByState(LocalHistoryMediaState.PENDING_COPY)) {
            String source = media.sourcePath;
            if (source == null || !files.exists(source)) {
                finish(media, LocalHistoryMediaState.LOST, false);
                continue;
            }
            long size = files.size(source);
            media.size = size;
            if (size > maxFileBytes) {
                finish(media, LocalHistoryMediaState.TOO_LARGE, true);
                continue;
            }
            while (repo.preservedBytes() + size > budgetBytes) {
                List<LocalHistoryRepository.Media> oldest = repo.oldestPreserved(1);
                if (oldest.isEmpty()) {
                    break;
                }
                evict(oldest.get(0));
            }
            if (repo.preservedBytes() + size > budgetBytes) {
                finish(media, LocalHistoryMediaState.OVER_BUDGET, true);
                continue;
            }
            String target = files.targetFor(media.entryId, media.revisionIdx, media.id, source);
            if (files.copy(source, target)) {
                media.localPath = target;
                finish(media, LocalHistoryMediaState.PRESERVED, true);
            } else {
                finish(media, LocalHistoryMediaState.LOST, false);
            }
        }
    }

    /** After a restart: pending rows get their holds back, rows whose source vanished become LOST. */
    public void recover() {
        for (LocalHistoryRepository.Media media : repo.mediaByState(LocalHistoryMediaState.PENDING_COPY)) {
            if (media.sourcePath != null && files.exists(media.sourcePath)) {
                holds.hold(media.sourcePath);
            } else {
                finish(media, LocalHistoryMediaState.LOST, false);
            }
        }
        processPending();
    }

    /** Removes the preserved copy and keeps the row, so the entry shows that its media is gone. */
    public void evict(LocalHistoryRepository.Media media) {
        if (media.localPath != null) {
            files.delete(media.localPath);
        }
        media.localPath = null;
        media.state = LocalHistoryMediaState.EVICTED;
        repo.updateMedia(media);
    }

    /** Deletes the preserved files of one entry; call before removing the entry itself. */
    public void deleteFilesOf(long entryId) {
        for (LocalHistoryRepository.Media media : repo.mediaForEntry(entryId)) {
            if (media.localPath != null) {
                files.delete(media.localPath);
            }
        }
    }

    /**
     * Telegram's own deletion of the original was skipped while the hold lasted; once the copy is safe (or the
     * copy cannot happen) the original goes, then the hold is released.
     */
    private void finish(LocalHistoryRepository.Media media, int state, boolean deleteSource) {
        media.state = state;
        repo.updateMedia(media);
        if (media.sourcePath != null) {
            if (deleteSource) {
                files.delete(media.sourcePath);
            }
            holds.release(media.sourcePath);
        }
    }
}
