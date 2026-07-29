package org.wodichka.packcontrol.snapshot;

import java.util.ArrayList;
import java.util.List;

public final class PackSnapshotManifest {
    public int schemaVersion = 1;
    public String name = "release-1";
    public long createdAt = 0L;
    public String version = "0.1.0-dev";
    public String commitMessage = "";
    public String author = "";
    public String minecraftVersion = "1.21.1";
    public String loader = "neoforge";
    public String loaderVersion = "21.1.233";
    public List<ModEntry> mods = new ArrayList<>();
    public List<FileEntry> configs = new ArrayList<>();
    public List<FileEntry> kubejs = new ArrayList<>();
    public List<FileEntry> files = new ArrayList<>();
    public String filesArchive = "snapshot-files.zip";

    public static final class ModEntry {
        public String name = "";
        public String filename = "";
        public String originalFilename = "";
        public boolean enabled = true;
        public String source = "custom";
        public String downloadUrl = "";
        public String sha256 = "";
        public String sha1 = "";
        public String sha512 = "";
        public long size = 0L;
        public boolean required = true;
    }

    public static final class FileEntry {
        public String path = "";
        public String archiveEntry = "";
        public String sha256 = "";
        public long size = 0L;
        public String type = "file";
    }
}
