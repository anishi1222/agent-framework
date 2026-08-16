// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp.skills;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class MCPArchiveExtractor {
    private static final int TAR_BLOCK_SIZE = 512;
    private static final int BUFFER_SIZE = 81920;

    private MCPArchiveExtractor() {}

    static void extract(byte[] archive, String mediaType, URI uri, Path target, MCPSkillsSourceOptions options)
            throws IOException {
        ArchiveFormat format = detect(archive, mediaType, uri);
        if (format == null) {
            throw new IOException("Unsupported skill archive format.");
        }
        Files.createDirectories(target);
        Counter counter = new Counter(options.archiveMaxFileCount(), options.archiveMaxUncompressedSizeBytes());
        if (format == ArchiveFormat.ZIP) {
            extractZip(archive, target, counter);
        } else {
            InputStream source = new ByteArrayInputStream(archive);
            if (format == ArchiveFormat.TAR_GZIP) {
                source = new GZIPInputStream(source);
            }
            try (InputStream input = source) {
                extractTar(input, target, counter);
            }
        }
    }

    static ArchiveFormat detect(byte[] archive, String mediaType, URI uri) {
        if (archive.length >= 2 && Byte.toUnsignedInt(archive[0]) == 0x1f && Byte.toUnsignedInt(archive[1]) == 0x8b) {
            return ArchiveFormat.TAR_GZIP;
        }
        if (archive.length >= 4
                && archive[0] == 'P'
                && archive[1] == 'K'
                && (archive[2] == 3 || archive[2] == 5 || archive[2] == 7)
                && (archive[3] == 4 || archive[3] == 6 || archive[3] == 8)) {
            return ArchiveFormat.ZIP;
        }
        String type = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT);
        if (type.equals("application/zip") || type.equals("application/x-zip-compressed")) {
            return ArchiveFormat.ZIP;
        }
        if (type.equals("application/gzip")
                || type.equals("application/x-gzip")
                || type.equals("application/x-compressed-tar")) {
            return ArchiveFormat.TAR_GZIP;
        }
        if (type.equals("application/x-tar") || type.equals("application/tar")) {
            return ArchiveFormat.TAR;
        }
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        if (path.endsWith(".zip")) {
            return ArchiveFormat.ZIP;
        }
        if (path.endsWith(".tar.gz") || path.endsWith(".tgz")) {
            return ArchiveFormat.TAR_GZIP;
        }
        return path.endsWith(".tar") ? ArchiveFormat.TAR : null;
    }

    private static void extractZip(byte[] archive, Path target, Counter counter) throws IOException {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                counter.nextFile();
                Path destination = resolve(target, entry.getName());
                Files.createDirectories(destination.getParent());
                try (OutputStream output = Files.newOutputStream(
                        destination,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
                    copyUntilEnd(input, output, counter);
                }
            }
        }
    }

    private static void extractTar(InputStream input, Path target, Counter counter) throws IOException {
        byte[] header = new byte[TAR_BLOCK_SIZE];
        while (readBlock(input, header)) {
            if (allZero(header)) {
                return;
            }
            String name = tarString(header, 0, 100);
            String prefix = tarString(header, 345, 155);
            if (!prefix.isEmpty()) {
                name = prefix + "/" + name;
            }
            long size = tarSize(header, 124, 12);
            byte type = header[156];
            boolean regular = type == 0 || type == '0';
            if (regular) {
                counter.nextFile();
                Path destination = resolve(target, name);
                Files.createDirectories(destination.getParent());
                try (OutputStream output = Files.newOutputStream(
                        destination,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
                    copyExact(input, output, size, counter);
                }
            } else {
                skipExact(input, size, counter);
            }
            long padding = (TAR_BLOCK_SIZE - (size % TAR_BLOCK_SIZE)) % TAR_BLOCK_SIZE;
            skipExact(input, padding);
        }
    }

    private static Path resolve(Path target, String entryName) throws IOException {
        String normalizedName = entryName.replace('\\', '/');
        if (normalizedName.startsWith("/")
                || Arrays.stream(normalizedName.split("/")).anyMatch(part -> part.equals(".."))) {
            throw new IOException("Archive entry escapes the target directory.");
        }
        Path normalizedTarget = target.toAbsolutePath().normalize();
        Path destination = normalizedTarget.resolve(normalizedName).normalize();
        if (!destination.startsWith(normalizedTarget) || destination.equals(normalizedTarget)) {
            throw new IOException("Archive entry escapes the target directory.");
        }
        return destination;
    }

    private static void copyUntilEnd(InputStream input, OutputStream output, Counter counter) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            counter.addBytes(read);
            output.write(buffer, 0, read);
        }
    }

    private static void copyExact(InputStream input, OutputStream output, long bytes, Counter counter)
            throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long remaining = bytes;
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new IOException("Truncated TAR entry.");
            }
            counter.addBytes(read);
            output.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static void skipExact(InputStream input, long bytes) throws IOException {
        skipExact(input, bytes, null);
    }

    private static void skipExact(InputStream input, long bytes, Counter counter) throws IOException {
        long remaining = bytes;
        byte[] buffer = new byte[BUFFER_SIZE];
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new IOException("Truncated archive.");
            }
            if (counter != null) {
                counter.addBytes(read);
            }
            remaining -= read;
        }
    }

    private static boolean readBlock(InputStream input, byte[] block) throws IOException {
        int offset = 0;
        while (offset < block.length) {
            int read = input.read(block, offset, block.length - offset);
            if (read < 0) {
                return offset == 0 ? false : truncated();
            }
            offset += read;
        }
        return true;
    }

    private static boolean truncated() throws IOException {
        throw new IOException("Truncated TAR header.");
    }

    private static boolean allZero(byte[] value) {
        for (byte item : value) {
            if (item != 0) {
                return false;
            }
        }
        return true;
    }

    private static String tarString(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8).trim();
    }

    private static long tarSize(byte[] header, int offset, int length) throws IOException {
        String value = tarString(header, offset, length).trim();
        if (value.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(value, 8);
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid TAR entry size.", exception);
        }
    }

    enum ArchiveFormat {
        ZIP,
        TAR,
        TAR_GZIP
    }

    private static final class Counter {
        private final int maxFiles;
        private final long maxBytes;
        private int files;
        private long bytes;

        private Counter(int maxFiles, long maxBytes) {
            this.maxFiles = maxFiles;
            this.maxBytes = maxBytes;
        }

        private void nextFile() throws IOException {
            files++;
            if (files > maxFiles) {
                throw new IOException("Skill archive exceeds the file-count limit.");
            }
        }

        private void addBytes(int added) throws IOException {
            bytes += added;
            if (bytes > maxBytes) {
                throw new IOException("Skill archive exceeds the uncompressed-size limit.");
            }
        }
    }
}
