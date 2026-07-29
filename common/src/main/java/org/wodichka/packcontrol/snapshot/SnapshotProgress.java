package org.wodichka.packcontrol.snapshot;

public record SnapshotProgress(String stage, int current, int total, String detail, boolean done, boolean success) {
    public static SnapshotProgress step(String stage, int current, int total, String detail) {
        return new SnapshotProgress(stage, current, Math.max(total, 1), detail == null ? "" : detail, false, true);
    }

    public static SnapshotProgress done(String detail, boolean success) {
        return new SnapshotProgress(success ? "Done" : "Failed", 1, 1, detail == null ? "" : detail, true, success);
    }

    public int percent() {
        if (total <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(100, Math.round(current * 100.0F / total)));
    }

    public String display() {
        return stage + " " + percent() + "%" + (detail.isBlank() ? "" : ": " + detail);
    }
}
