package org.yosef.karov;

// מצב משותף ללולאת הממשק ולעובד ADB. כל גישה מסונכרנת כדי שביטול יהיה גלוי בשני הצדדים.
final class PointerOperation {
    private long id;
    private long expiresAt;
    private boolean active;

    synchronized long begin(long now, long timeout) {
        id++;
        expiresAt = now + timeout;
        active = true;
        return id;
    }

    synchronized boolean canStart(long expectedId, long now) {
        return active && expectedId == id && now < expiresAt;
    }

    synchronized boolean finish(long expectedId) {
        if (!active || expectedId != id) return false;
        active = false;
        return true;
    }

    synchronized void invalidate() {
        active = false;
        id++;
    }
}
