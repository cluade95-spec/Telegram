package org.telegram.messenger.usage;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Opaque persistent idempotency key, without retaining dialog or message identifiers. */
public final class UsageSendIdentity {
    private UsageSendIdentity() { }
    public static boolean eligible(long account, long message, boolean outgoing, boolean confirmed,
                                   boolean schedulingAcknowledgment, boolean secret) {
        return account != 0 && (secret ? message != 0 : message > 0) && outgoing && confirmed && !schedulingAcknowledgment;
    }
    public static String digest(String salt, long account, long dialog, long message, boolean secret) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            digest.update(salt.getBytes(StandardCharsets.UTF_8));
            digest.update(ByteBuffer.allocate(25).putLong(account).putLong(dialog).putLong(message).put((byte)(secret?1:0)).array());
            StringBuilder hex=new StringBuilder(64);
            for(byte b:digest.digest()) { hex.append(Character.forDigit((b>>>4)&15,16)); hex.append(Character.forDigit(b&15,16)); }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
