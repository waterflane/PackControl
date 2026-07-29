package org.wodichka.packcontrol.updateformat;

import org.wodichka.packcontrol.updateformat.PackControlManifest.Hashes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class FileHashing {
    private FileHashing() {
    }

    static String sha256(Path path) throws IOException {
        return hashes(path).sha256();
    }

    public static Hashes hashes(Path path) throws IOException {
        return inspect(path).hashes();
    }

    public static DigestedContent inspect(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return inspect(input);
        }
    }

    public static DigestedContent inspect(InputStream input) throws IOException {
        return digest(input, null, Long.MAX_VALUE);
    }

    static DigestedContent copyAndHash(InputStream input, Path target, long maximumBytes) throws IOException {
        Files.createDirectories(target.getParent());
        try (OutputStream output = Files.newOutputStream(target)) {
            return digest(input, output, maximumBytes);
        }
    }

    private static DigestedContent digest(InputStream input, OutputStream output, long maximumBytes) throws IOException {
        MessageDigest sha1 = digest("SHA-1");
        MessageDigest sha256 = digest("SHA-256");
        MessageDigest sha512 = digest("SHA-512");
        byte[] buffer = new byte[16 * 1024];
        long size = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            if (size > maximumBytes - read) {
                throw new IOException("Downloaded content exceeds declared size");
            }
            if (output != null) {
                output.write(buffer, 0, read);
            }
            sha1.update(buffer, 0, read);
            sha256.update(buffer, 0, read);
            sha512.update(buffer, 0, read);
            size += read;
        }
        return new DigestedContent(
                size,
                new Hashes(hex(sha1), hex(sha256), hex(sha512))
        );
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(algorithm + " is unavailable", exception);
        }
    }

    private static String hex(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }

    public record DigestedContent(long size, Hashes hashes) {
    }
}
