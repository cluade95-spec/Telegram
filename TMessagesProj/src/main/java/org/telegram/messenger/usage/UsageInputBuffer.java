package org.telegram.messenger.usage;

/** Cheap input recording; delivery is driven by input/navigation, never by a timer. */
public final class UsageInputBuffer {
    public interface Delivery { void input(long elapsed); }
    private static final long DELIVERY_MS = UsagePolicy.IDLE_MS / 2;
    private final Delivery delivery;
    private long pending = -1, delivered = -1, nextDelivery;

    public UsageInputBuffer(Delivery delivery) { this.delivery = delivery; }

    public void record(long now) {
        if (now >= nextDelivery) {
            deliver();
            pending = now;
            deliver();
            nextDelivery = now + DELIVERY_MS;
        } else {
            pending = now;
        }
    }

    public void deliver() {
        if (pending > delivered) {
            delivery.input(pending);
            delivered = pending;
        }
    }
}
