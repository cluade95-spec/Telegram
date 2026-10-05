package org.telegram.messenger.usage;

import java.util.Objects;

public final class SurfaceKey {
    public final long accountUserId;
    public final UsageSurface surface;
    public final long dialogId;

    public SurfaceKey(long accountUserId, UsageSurface surface, long dialogId) {
        this.accountUserId = accountUserId;
        this.surface = Objects.requireNonNull(surface);
        this.dialogId = dialogId;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SurfaceKey)) return false;
        SurfaceKey key = (SurfaceKey) other;
        return accountUserId == key.accountUserId && surface == key.surface && dialogId == key.dialogId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(accountUserId, surface, dialogId);
    }
}
