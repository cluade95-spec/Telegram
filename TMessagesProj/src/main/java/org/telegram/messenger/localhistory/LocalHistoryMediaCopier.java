package org.telegram.messenger.localhistory;

import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** The real file system behind {@link LocalHistoryMediaManager}: copy to a .part file, fsync, rename. */
public class LocalHistoryMediaCopier implements LocalHistoryMediaManager.Files {

    private final File mediaDir;

    public LocalHistoryMediaCopier(File mediaDir) {
        this.mediaDir = mediaDir;
    }

    public File getMediaDir() {
        return mediaDir;
    }

    @Override
    public boolean exists(String path) {
        return path != null && new File(path).isFile();
    }

    @Override
    public long size(String path) {
        return new File(path).length();
    }

    @Override
    public boolean copy(String source, String target) {
        File dst = new File(target);
        File part = new File(target + ".part");
        dst.getParentFile().mkdirs();
        try (InputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            OutputStream stream = out;
            while ((read = in.read(buffer)) > 0) {
                stream.write(buffer, 0, read);
            }
            out.getFD().sync();
        } catch (Throwable e) {
            FileLog.e(e);
            part.delete();
            return false;
        }
        if (!part.renameTo(dst)) {
            part.delete();
            return false;
        }
        return true;
    }

    @Override
    public void delete(String path) {
        if (path != null) {
            new File(path).delete();
        }
    }

    @Override
    public String targetFor(long entryId, int revisionIdx, long mediaId, String sourcePath) {
        String ext = "bin";
        if (sourcePath != null) {
            int dot = sourcePath.lastIndexOf('.');
            int slash = sourcePath.lastIndexOf('/');
            if (dot > slash && sourcePath.length() - dot <= 6) {
                ext = sourcePath.substring(dot + 1);
            }
        }
        return new File(mediaDir, entryId + "_" + revisionIdx + "_" + mediaId + "." + ext).getAbsolutePath();
    }

    /** Removes the whole media directory. */
    public void deleteAll() {
        File[] files = mediaDir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        mediaDir.delete();
    }
}
