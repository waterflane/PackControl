package org.wodichka.packcontrol.snapshot;

public record SnapshotSaveOptions(String name, String version, String commitMessage, String author) {
    public static SnapshotSaveOptions defaults() {
        return new SnapshotSaveOptions("", "", "", "");
    }
}
