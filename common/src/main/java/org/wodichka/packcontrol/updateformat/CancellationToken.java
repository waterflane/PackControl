package org.wodichka.packcontrol.updateformat;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public static CancellationToken none() {
        return new CancellationToken();
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            listeners.forEach(Runnable::run);
            listeners.clear();
        }
    }

    public void throwIfCancelled() throws PackRequestCancelledException {
        if (isCancelled()) {
            throw new PackRequestCancelledException();
        }
    }

    Registration onCancel(Runnable listener) {
        if (isCancelled()) {
            listener.run();
            return () -> {
            };
        }
        listeners.add(listener);
        if (isCancelled() && listeners.remove(listener)) {
            listener.run();
        }
        return () -> listeners.remove(listener);
    }

    void waitBeforeRetry(long millis) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            throwIfCancelled();
            long remainingMillis = Math.max(1, (deadline - System.nanoTime()) / 1_000_000L);
            Thread.sleep(Math.min(remainingMillis, 25));
        }
    }

    @FunctionalInterface
    interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    public static final class PackRequestCancelledException extends IOException {
        public PackRequestCancelledException() {
            super("PackControl request was cancelled");
        }
    }
}
