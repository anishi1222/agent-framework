// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.valkey;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ValkeyScriptContractTest {
    @Test
    void appendScript_shouldImplementAtomicReplayConflictTrimDedupBoundAndTtl() {
        // Arrange
        String source = ValkeyScript.APPEND.source();

        // Act
        int lookup = source.indexOf("redis.call('HGET', KEYS[2], operation)");
        int append = source.indexOf("redis.call('RPUSH', KEYS[1], ARGV[6 + index])");
        int trim = source.indexOf("redis.call('LTRIM', KEYS[1], -maxStored, -1)");
        int bind = source.indexOf("redis.call('HSET', KEYS[2], operation, digest)");
        int bound = source.indexOf("while dedupLength > maxDedup do");
        int evict = source.indexOf("redis.call('HDEL', KEYS[2], oldest)");
        int ttl = source.indexOf("redis.call('PEXPIRE', KEYS[1], ttlMillis)");

        // Assert
        assertThat(lookup).isGreaterThanOrEqualTo(0);
        assertThat(append).isGreaterThan(lookup);
        assertThat(trim).isGreaterThan(append);
        assertThat(bind).isGreaterThan(trim);
        assertThat(bound).isGreaterThan(bind);
        assertThat(evict).isGreaterThan(bound);
        assertThat(ttl).isGreaterThanOrEqualTo(0);
        assertThat(source)
                .contains(
                        "if existing == digest then",
                        "return {1, messagesLength}",
                        "return {2, messagesLength}",
                        "local retainedLength = math.min(messagesLength + messageCount, maxStored)",
                        "redis.call('PEXPIRE', KEYS[2], ttlMillis)",
                        "redis.call('PEXPIRE', KEYS[3], ttlMillis)",
                        "redis.call('PERSIST', KEYS[1])",
                        "messageCount ~= (#ARGV - 6)")
                .doesNotContain("LREM");
    }

    @Test
    void appendScript_shouldRejectWrongTypesBeforeAnyMutation() {
        // Arrange
        String source = ValkeyScript.APPEND.source();

        // Act
        int messagesType = source.indexOf("local messagesType = keyType(KEYS[1])");
        int dedupType = source.indexOf("local dedupType = keyType(KEYS[2])");
        int orderType = source.indexOf("local dedupOrderType = keyType(KEYS[3])");
        int lookup = source.indexOf("redis.call('HGET', KEYS[2], operation)");
        int firstMutation = source.indexOf("redis.call('RPUSH', KEYS[1], ARGV[6 + index])");

        // Assert
        assertThat(messagesType).isGreaterThanOrEqualTo(0);
        assertThat(dedupType).isGreaterThan(messagesType);
        assertThat(orderType).isGreaterThan(dedupType);
        assertThat(lookup).isGreaterThan(orderType);
        assertThat(firstMutation).isGreaterThan(lookup);
        assertThat(source)
                .contains(
                        "return redis.error_reply('AF_VALKEY_WRONG_TYPE_MESSAGES')",
                        "return redis.error_reply('AF_VALKEY_WRONG_TYPE_DEDUP')",
                        "return redis.error_reply('AF_VALKEY_WRONG_TYPE_DEDUP_ORDER')");
        assertThat(source.substring(firstMutation))
                .doesNotContain("keyType(", "redis.call('TYPE'", "redis.call('HGET'", "redis.call('LLEN'");
    }

    @Test
    void appendScript_shouldKeepInsertionConstantTimeAsideFromPrunedEntries() {
        String source = ValkeyScript.APPEND.source();

        assertThat(occurrences(source, "redis.call('LREM'")).isZero();
        assertThat(occurrences(source, "redis.call('HSET', KEYS[2], operation, digest)"))
                .isEqualTo(1);
        assertThat(occurrences(source, "redis.call('RPUSH', KEYS[3], operation)"))
                .isEqualTo(1);
        assertThat(source)
                .contains(
                        "local dedupLength = redis.call('LLEN', KEYS[3])",
                        "dedupLength = dedupLength + 1",
                        "while dedupLength > maxDedup do",
                        "dedupLength = dedupLength - 1");
    }

    @Test
    void clearScript_shouldDeleteExactlyThreeSameSlotStructuresInOneCommand() {
        assertThat(ValkeyScript.CLEAR.source().trim()).isEqualTo("return redis.call('DEL', KEYS[1], KEYS[2], KEYS[3])");
    }

    @Test
    void loadScript_shouldUseOneBoundedTailRangeAndValidateReturnedValuesInOrder() {
        String source = ValkeyScript.LOAD.source();

        assertThat(source)
                .contains(
                        "local count = math.min(length, maxLoaded)",
                        "local first = length - count",
                        "local values = redis.call('LRANGE', KEYS[1], first, -1)",
                        "for index = 1, #values do",
                        "local item = values[index]",
                        "if messageBytes > maxMessageBytes then",
                        "if totalBytes > maxDocumentBytes then",
                        "return redis.error_reply('AF_VALKEY_MESSAGE_BYTES')",
                        "return redis.error_reply('AF_VALKEY_DOCUMENT_BYTES')")
                .doesNotContain("index, index");
        assertThat(occurrences(source, "redis.call('LLEN'")).isEqualTo(1);
        assertThat(occurrences(source, "redis.call('LRANGE'")).isEqualTo(1);
    }

    @Test
    void loadScript_shouldBoundMaximumWorkAndRejectWrongTypeBeforeListCommands() {
        String source = ValkeyScript.LOAD.source();

        int messagesType = source.indexOf("local messagesType = keyType(KEYS[1])");
        int length = source.indexOf("redis.call('LLEN', KEYS[1])");
        int range = source.indexOf("redis.call('LRANGE', KEYS[1], first, -1)");

        assertThat(messagesType).isGreaterThanOrEqualTo(0);
        assertThat(length).isGreaterThan(messagesType);
        assertThat(range).isGreaterThan(length);
        assertThat(source)
                .contains(
                        "maxLoaded > 10000",
                        "maxMessageBytes > 16777216",
                        "maxDocumentBytes > 67108864",
                        "return redis.error_reply('AF_VALKEY_WRONG_TYPE_MESSAGES')");
    }

    @Test
    void deduplicationLimit_shouldRemainStrictlyBounded() {
        assertThat(ValkeyHistoryProvider.deduplicationLimit(1)).isEqualTo(1);
        assertThat(ValkeyHistoryProvider.deduplicationLimit(1000)).isEqualTo(1000);
        assertThat(ValkeyHistoryProvider.deduplicationLimit(100_000)).isEqualTo(100_000);
    }

    private static int occurrences(String value, String expected) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(expected, offset)) >= 0) {
            count++;
            offset += expected.length();
        }
        return count;
    }
}
