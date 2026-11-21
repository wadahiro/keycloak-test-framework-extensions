package io.github.wadahiro.keycloak.testframework.runtimemodern;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

final class RequestGate {
    private static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock(true);
    static volatile boolean poisoned;

    static void read(Runnable action) {
        LOCK.readLock().lock();
        try {
            if (poisoned) throw new IllegalStateException("Runtime is unusable after a snapshot failure");
            action.run();
        } finally { LOCK.readLock().unlock(); }
    }

    static <T> T exclusive(Callable<T> action) throws Exception {
        if (!LOCK.writeLock().tryLock(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Requests or background work did not finish within 10 seconds");
        }
        try { return action.call(); } finally { LOCK.writeLock().unlock(); }
    }
}
