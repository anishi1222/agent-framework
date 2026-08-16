// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.valkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.ValidationException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValkeyKeyDerivationTest {
    @Test
    void historyKeys_shouldShareOneHashTagAndContainNoRawIdentifiers() {
        // Arrange
        ValkeyHistoryOptions options = ValkeyTestSupport.options();

        // Act
        ValkeyHistoryKeys keys = ValkeyKeyDerivation.historyKeys(options, "session-sensitive");

        // Assert
        String tag = hashTag(keys.messages());
        assertThat(hashTag(keys.deduplication())).isEqualTo(tag);
        assertThat(hashTag(keys.deduplicationOrder())).isEqualTo(tag);
        assertThat(List.of(keys.messages(), keys.deduplication(), keys.deduplicationOrder()))
                .allSatisfy(key -> assertThat(key)
                        .startsWith("test:history:{")
                        .doesNotContain("tenant-a", "isolation-a", "agent-a", "session-sensitive"));
    }

    @Test
    void lengthPrefixing_shouldPreventDelimiterAndConcatenationCollisions() {
        // Arrange
        ValkeyClientOptions client = ValkeyClientOptions.defaults(new ValkeyEndpoint("localhost", 6379));
        ValkeyHistoryOptions first = new ValkeyHistoryOptions(
                client, new ValkeyPartitionContext("ab", "c", "d"), "history", "prefix", 10, 5, null, 1024, 4096);
        ValkeyHistoryOptions second = new ValkeyHistoryOptions(
                client, new ValkeyPartitionContext("a", "bc", "d"), "history", "prefix", 10, 5, null, 1024, 4096);

        // Act
        String firstTag = hashTag(ValkeyKeyDerivation.historyKeys(first, "ef").messages());
        String secondTag = hashTag(ValkeyKeyDerivation.historyKeys(second, "ef").messages());

        // Assert
        assertThat(firstTag).isNotEqualTo(secondTag);
    }

    @Test
    void operationAndAppendDigests_shouldBeStableBoundedAndOrderSensitive() {
        // Arrange
        byte[] first = "first".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second".getBytes(StandardCharsets.UTF_8);

        // Act
        String operation = ValkeyKeyDerivation.operationToken("raw-run-id");
        String digest = ValkeyKeyDerivation.appendDigest(List.of(first, second));
        String reversed = ValkeyKeyDerivation.appendDigest(List.of(second, first));

        // Assert
        assertThat(operation).hasSize(43).doesNotContain("raw-run-id");
        assertThat(ValkeyKeyDerivation.operationToken("raw-run-id")).isEqualTo(operation);
        assertThat(digest).hasSize(43).isNotEqualTo(reversed);
    }

    @Test
    void identifiers_shouldRejectMalformedUtf16InsteadOfHashingReplacementBytes() {
        assertThatThrownBy(() -> new ValkeyPartitionContext("\uD800", "isolation", "agent"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("well-formed Unicode");
        assertThatThrownBy(() -> ValkeyKeyDerivation.operationToken("\uD801"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("well-formed Unicode");
    }

    private static String hashTag(String key) {
        return key.substring(key.indexOf('{') + 1, key.indexOf('}'));
    }
}
