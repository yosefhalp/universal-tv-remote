package org.yosef.karov;

import static org.junit.Assert.*;

import org.junit.Test;

public final class PointerOperationTest {
    @Test
    public void queuedGestureExpiresEvenWhenDeadlineCallbackHasNotRun() {
        PointerOperation operation = new PointerOperation();
        long id = operation.begin(100, 2560);
        assertTrue(operation.canStart(id, 2659));
        assertFalse(operation.canStart(id, 2660));
        assertFalse(operation.canStart(id, 10000));
    }

    @Test
    public void previousGestureCannotRunOrCompleteANewerGesture() {
        PointerOperation operation = new PointerOperation();
        long previous = operation.begin(100, 2560);
        assertTrue(operation.finish(previous));
        long next = operation.begin(3000, 2560);
        assertFalse(operation.canStart(previous, 3001));
        assertFalse(operation.finish(previous));
        assertTrue(operation.canStart(next, 3001));
        assertTrue(operation.finish(next));
        assertFalse(operation.finish(next));
    }

    @Test
    public void disconnectionInvalidatesQueuedWorkAndLateCallbacksAcrossThreads() throws Exception {
        PointerOperation operation = new PointerOperation();
        long previous = operation.begin(100, 2560);
        Thread disconnect = new Thread(operation::invalidate);
        disconnect.start();
        disconnect.join();
        assertFalse(operation.canStart(previous, 101));
        assertFalse(operation.finish(previous));
        long next = operation.begin(200, 2560);
        assertFalse(operation.finish(previous));
        assertTrue(operation.canStart(next, 201));
    }
}
