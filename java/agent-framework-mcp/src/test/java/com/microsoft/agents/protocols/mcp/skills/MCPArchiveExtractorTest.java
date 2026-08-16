// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp.skills;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MCPArchiveExtractorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void extract_shouldReadTarGzipAndSkipSymbolicLinks() throws IOException {
        byte[] archive = tarGzip(
                new TarEntry("skill/SKILL.md", '0', "---\nname: skill\ndescription: Skill.\n---\n"),
                new TarEntry("skill/guide.md", '0', "guide"),
                new TarEntry("skill/link.md", '2', ""));
        MCPSkillsSourceOptions options = MCPSkillsSourceOptions.defaults();

        MCPArchiveExtractor.extract(
                archive,
                "application/octet-stream",
                URI.create("skill://catalog/skill.tgz"),
                temporaryDirectory,
                options);

        assertThat(Files.readString(temporaryDirectory.resolve("skill/guide.md")))
                .isEqualTo("guide");
        assertThat(Files.exists(temporaryDirectory.resolve("skill/link.md"))).isFalse();
    }

    private static byte[] tarGzip(TarEntry... entries) throws IOException {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        for (TarEntry entry : entries) {
            byte[] content = entry.content().getBytes(StandardCharsets.UTF_8);
            byte[] header = new byte[512];
            write(header, 0, 100, entry.name());
            write(header, 100, 8, "0000644");
            write(header, 108, 8, "0000000");
            write(header, 116, 8, "0000000");
            write(header, 124, 12, String.format("%011o", content.length));
            write(header, 136, 12, "00000000000");
            java.util.Arrays.fill(header, 148, 156, (byte) ' ');
            header[156] = (byte) entry.type();
            write(header, 257, 6, "ustar");
            long checksum = 0;
            for (byte value : header) {
                checksum += Byte.toUnsignedInt(value);
            }
            write(header, 148, 8, String.format("%06o\0 ", checksum));
            tar.write(header);
            tar.write(content);
            int padding = (512 - (content.length % 512)) % 512;
            tar.write(new byte[padding]);
        }
        tar.write(new byte[1024]);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(tar.toByteArray());
        }
        return compressed.toByteArray();
    }

    private static void write(byte[] target, int offset, int length, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(encoded, 0, target, offset, Math.min(encoded.length, length));
    }

    private record TarEntry(String name, char type, String content) {}
}
