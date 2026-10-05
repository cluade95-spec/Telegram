package org.telegram.messenger.localhistory;

import java.nio.charset.StandardCharsets;

/**
 * Decides whether an edit changed what was said (plan B7.1 step 3) and fingerprints content (B8).
 * Markup, web-preview fill, reactions and edit_hide-only updates are not part of {@link Content}, so they never count.
 */
public final class LocalHistoryDiff {

    private LocalHistoryDiff() {
    }

    /** The user-visible content of a message in a stable, comparable form. */
    public static final class Content {
        public final String text;
        /** canonical serialization of entities, "" when none. */
        public final String entities;
        /** media type plus identity (photo id / document id / geo / contact), "" when none. */
        public final String media;

        public Content(String text, String entities, String media) {
            this.text = text == null ? "" : text;
            this.entities = entities == null ? "" : entities;
            this.media = media == null ? "" : media;
        }
    }

    static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    public static boolean isContentChange(Content before, Content after) {
        if (before == null || after == null) {
            return before != after;
        }
        return !normalize(before.text).equals(normalize(after.text))
                || !before.entities.equals(after.entities)
                || !before.media.equals(after.media);
    }

    /** 64-bit FNV-1a of text, entities and media identity. */
    public static long contentHash(Content c) {
        long h = 0xcbf29ce484222325L;
        h = mix(h, normalize(c.text));
        h = mix(h, "\u0001" + c.entities);
        h = mix(h, "\u0002" + c.media);
        return h;
    }

    private static long mix(long h, String s) {
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xff);
            h *= 0x100000001b3L;
        }
        h ^= 0xff;
        h *= 0x100000001b3L;
        return h;
    }
}
