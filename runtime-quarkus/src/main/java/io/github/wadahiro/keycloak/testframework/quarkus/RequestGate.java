package io.github.wadahiro.keycloak.testframework.quarkus;

/** Shared barrier loaded with the Keycloak provider and invoked by HTTP and timer callbacks. */
public final class RequestGate {
    private static final Object MONITOR = new Object();
    private static int active;
    private static boolean exclusive;

    private RequestGate() { }

    public static void enter() {
        synchronized (MONITOR) {
            while (exclusive) waitForChange();
            active++;
        }
    }

    public static void leave() {
        synchronized (MONITOR) {
            if (--active < 0) throw new IllegalStateException("Snapshot gate count became negative");
            MONITOR.notifyAll();
        }
    }

    public static void beginExclusive() {
        synchronized (MONITOR) {
            while (exclusive) waitForChange();
            exclusive = true;
            try {
                while (active != 0) waitForChange();
            } catch (RuntimeException failure) {
                exclusive = false;
                MONITOR.notifyAll();
                throw failure;
            }
        }
    }

    public static void endExclusive() {
        synchronized (MONITOR) {
            exclusive = false;
            MONITOR.notifyAll();
        }
    }

    public static void read(Runnable action) {
        enter();
        try {
            action.run();
        } finally {
            leave();
        }
    }

    private static void waitForChange() {
        try {
            MONITOR.wait();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the snapshot gate", interrupted);
        }
    }
}
