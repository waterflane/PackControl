package org.wodichka.packcontrol.publisher;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class ReproducibleZip {
    private static final FileTime EPOCH = FileTime.fromMillis(0);

    private ReproducibleZip() {
    }

    static void write(Path output, List<Entry> entries) throws IOException {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(Entry::name));
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) {
            zip.setLevel(Deflater.BEST_COMPRESSION);
            for (Entry content : sorted) {
                ZipEntry entry = new ZipEntry(content.name());
                entry.setTime(0);
                entry.setCreationTime(EPOCH);
                entry.setLastAccessTime(EPOCH);
                entry.setLastModifiedTime(EPOCH);
                zip.putNextEntry(entry);
                content.writer().writeTo(zip);
                zip.closeEntry();
            }
        }
    }

    record Entry(String name, ContentWriter writer) {
        static Entry bytes(String name, byte[] bytes) {
            return new Entry(name, output -> output.write(bytes));
        }

        static Entry file(String name, Path path) {
            return new Entry(name, output -> Files.copy(path, output));
        }
    }

    @FunctionalInterface
    interface ContentWriter {
        void writeTo(OutputStream output) throws IOException;
    }
}
