// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.valkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.microsoft.agents.agents.ContextProviderRequest;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.RunCancelledException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ValkeyHistoryProviderTest {
    @Test
    void append_shouldInvokeOneAtomicScriptWithExactKeysArgumentsAndCanonicalMessages() {
        // Arrange
        RecordingAdapter adapter = new RecordingAdapter();
        ValkeyHistoryOptions options = ValkeyTestSupport.options(
                Duration.ofSeconds(5), Duration.ofMinutes(2), 7, 5, 1024 * 1024, 4 * 1024 * 1024);
        ValkeyHistoryProvider provider = new ValkeyHistoryProvider(adapter, false, options);
        ContextProviderRequest request = ValkeyTestSupport.request("session-private", "run-private");
        List<Message> messages = List.of(Message.text(Role.USER, "first"), Message.text(Role.ASSISTANT, "second"));

        // Act
        provider.appendMessagesAsync(request, messages).toCompletableFuture().join();

        // Assert
        assertThat(adapter.invocations).singleElement().satisfies(invocation -> {
            assertThat(invocation.script()).isEqualTo(ValkeyScript.APPEND);
            assertThat(invocation.keys()).hasSize(3);
            List<String> keys = invocation.keys().stream()
                    .map(ValkeyHistoryProviderTest::utf8)
                    .toList();
            assertThat(keys).element(0).asString().endsWith(":messages").doesNotContain("session-private");
            assertThat(keys.get(1)).endsWith(":dedup");
            assertThat(keys.get(2)).endsWith(":dedup-order");
            assertThat(hashTag(keys.get(0))).isEqualTo(hashTag(keys.get(1))).isEqualTo(hashTag(keys.get(2)));

            assertThat(invocation.arguments()).hasSize(8);
            assertThat(utf8(invocation.arguments().get(0))).hasSize(43).doesNotContain("run-private");
            assertThat(utf8(invocation.arguments().get(1))).hasSize(43);
            assertThat(utf8(invocation.arguments().get(2))).isEqualTo("7");
            assertThat(utf8(invocation.arguments().get(3)))
                    .isEqualTo(Integer.toString(ValkeyHistoryProvider.deduplicationLimit(7)));
            assertThat(utf8(invocation.arguments().get(4))).isEqualTo("120000");
            assertThat(utf8(invocation.arguments().get(5))).isEqualTo("2");
            assertThat(utf8(invocation.arguments().get(6)))
                    .isEqualTo("{\"documentKind\":\"history-message\",\"format\":\"agent-framework-java-state\","
                            + "\"payload\":{\"contents\":[{\"kind\":\"text\",\"text\":\"first\"}],"
                            + "\"role\":\"user\"},\"payloadVersion\":1}");
            assertThat(utf8(invocation.arguments().get(7))).contains("\"text\":\"second\"");
        });
    }

    @Test
    void append_shouldBeNoopForEmptyMessages() {
        // Arrange
        RecordingAdapter adapter = new RecordingAdapter();
        ValkeyHistoryProvider provider = new ValkeyHistoryProvider(adapter, false, ValkeyTestSupport.options());

        // Act
        provider.appendMessagesAsync(ValkeyTestSupport.request("session", "run"), List.of())
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(adapter.invocations).isEmpty();
    }

    @Test
    void append_shouldAcceptIdempotentReplayAndRejectConflictingDigest() {
        // Arrange
        RecordingAdapter replay = new RecordingAdapter();
        replay.scriptHandler = ignored -> CompletableFuture.completedFuture(new Object[] {1L, 2L});
        ValkeyHistoryProvider replayProvider = new ValkeyHistoryProvider(replay, false, ValkeyTestSupport.options());
        RecordingAdapter conflict = new RecordingAdapter();
        conflict.scriptHandler = ignored -> CompletableFuture.completedFuture(new Object[] {2L, 2L});
        ValkeyHistoryProvider conflictProvider =
                new ValkeyHistoryProvider(conflict, false, ValkeyTestSupport.options());
        ContextProviderRequest request = ValkeyTestSupport.request("session", "same-operation");

        // Act
        replayProvider
                .appendMessagesAsync(request, List.of(Message.text(Role.USER, "same")))
                .toCompletableFuture()
                .join();
        Throwable thrown = catchThrowable(() -> conflictProvider
                .appendMessagesAsync(request, List.of(Message.text(Role.USER, "different")))
                .toCompletableFuture()
                .join());

        // Assert
        assertThat(thrown).isInstanceOf(CompletionException.class);
        assertThat(thrown.getCause())
                .isInstanceOfSatisfying(
                        ValkeyStorageException.class,
                        failure -> assertThat(failure.kind()).isEqualTo(ValkeyStorageException.Kind.CONFLICT));
    }

    @Test
    void append_shouldRejectMalformedScriptResult() {
        // Arrange
        RecordingAdapter adapter = new RecordingAdapter();
        adapter.scriptHandler = ignored -> CompletableFuture.completedFuture("unexpected");
        ValkeyHistoryProvider provider = new ValkeyHistoryProvider(adapter, false, ValkeyTestSupport.options());

        // Act
        Throwable thrown = catchThrowable(() -> provider.appendMessagesAsync(
                        ValkeyTestSupport.request("session", "run"), List.of(Message.text(Role.USER, "message")))
                .toCompletableFuture()
                .join());

        // Assert
        assertThat(thrown.getCause())
                .isInstanceOfSatisfying(
                        ValkeyStorageException.class,
                        failure -> assertThat(failure.kind()).isEqualTo(ValkeyStorageException.Kind.SERVICE));
    }

    @Test
    void load_shouldUseBoundedTailRangePreserveOrderAndReturnDetachedMessages() {
        // Arrange
        RecordingAdapter adapter = new RecordingAdapter();
        ValkeyMessageCodec codec = new ValkeyMessageCodec(1024 * 1024);
        Message first = Message.text(Role.USER, "oldest");
        Message second = Message.text(Role.ASSISTANT, "newest");
        adapter.rangeResult = CompletableFuture.completedFuture(List.of(codec.encode(first), codec.encode(second)));
        ValkeyHistoryProvider provider = new ValkeyHistoryProvider(adapter, false, ValkeyTestSupport.options());

        // Act
        List<Message> loaded = provider.loadMessagesAsync(ValkeyTestSupport.request("session", "run"))
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(adapter.invocations).singleElement().satisfies(invocation -> {
            assertThat(invocation.script()).isEqualTo(ValkeyScript.LOAD);
            assertThat(invocation.keys())
                    .singleElement()
                    .satisfies(key -> assertThat(utf8(key)).endsWith(":messages"));
            assertThat(invocation.arguments().stream()
                            .map(ValkeyHistoryProviderTest::utf8)
                            .toList())
                    .containsExactly("5", "1048576", "4194304");
        });
        assertThat(loaded).extracting(Message::text).containsExactly("oldest", "newest");
        assertThat(loaded.get(0)).isEqualTo(first).isNotSameAs(first);
        assertThat(loaded.get(1)).isEqualTo(second).isNotSameAs(second);
    }

    @Test
    void load_shouldKeepMaximumConfiguredLimitsToOneScriptInvocation() {
        // Arrange
        RecordingAdapter adapter = new RecordingAdapter();
        ValkeyHistoryOptions options = ValkeyTestSupport.options(
                Duration.ofSeconds(5), null, 100_000, 10_000, 16 * 1024 * 1024, 64 * 1024 * 1024);
        ValkeyHistoryProvider provider = new ValkeyHistoryProvider(adapter, false, options);

        // Act
        List<Message> loaded = provider.loadMessagesAsync(ValkeyTestSupport.request("session", "run"))
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(loaded).isEmpty();
        assertThat(adapter.invocations).singleElement().satisfies(invocation -> {
            assertThat(invocation.script()).isEqualTo(ValkeyScript.LOAD);
            assertThat(invocation.arguments().stream()
                            .map(ValkeyHistoryProviderTest::utf8)
                            .toList())
                    .containsExactly("10000", "16777216", "67108864");
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corruptEntries")
    void load_shouldFailClosedForCorruptUnsupportedOrOversizedEntries(
            String name, byte[] entry, ValkeyHistoryOptions options) {
        // Arrange
        RecordingAdapter adapter = new RecordingAdapter();
        adapter.rangeResult = CompletableFuture.completedFuture(List.of(entry));
        ValkeyHistoryProvider provider = new ValkeyHistoryProvider(adapter, false, options);

        // Act
        Throwable thrown = catchThrowable(() -> provider.loadMessagesAsync(ValkeyTestSupport.request("session", "run"))
                .toCompletableFuture()
                .join());

        // Assert
        assertThat(name).isNotBlank();
        assertThat(thrown.getCause()).isInstanceOfSatisfying(ValkeyStorageException.class, failure -> {
            assertThat(failure.kind()).isEqualTo(ValkeyStorageException.Kind.INCOMPATIBLE_DATA);
            assertThat(failure.getCause()).isNull();
        });
    }

    @Test
    void load_shouldRejectExcessEntryCountAndAggregateBytes() {
        // Arrange
        ValkeyHistoryOptions countOptions = ValkeyTestSupport.options(Duration.ofSeconds(5), null, 2, 1, 1024, 4096);
        ValkeyMessageCodec codec = new ValkeyMessageCodec(1024);
        RecordingAdapter countAdapter = new RecordingAdapter();
        countAdapter.rangeResult = CompletableFuture.completedFuture(List.of(
                codec.encode(Message.text(Role.USER, "first")), codec.encode(Message.text(Role.USER, "second"))));
        ValkeyHistoryProvider countProvider = new ValkeyHistoryProvider(countAdapter, false, countOptions);

        ValkeyHistoryOptions byteOptions = ValkeyTestSupport.options(Duration.ofSeconds(5), null, 2, 2, 1024, 1024);
        RecordingAdapter byteAdapter = new RecordingAdapter();
        byteAdapter.rangeResult = CompletableFuture.completedFuture(List.of(
                codec.encode(Message.text(Role.USER, "x".repeat(450))),
                codec.encode(Message.text(Role.USER, "y".repeat(450)))));
        ValkeyHistoryProvider byteProvider = new ValkeyHistoryProvider(byteAdapter, false, byteOptions);

        // Act
        Throwable countFailure = catchThrowable(() -> countProvider
                .loadMessagesAsync(ValkeyTestSupport.request("session", "run"))
                .toCompletableFuture()
                .join());
        Throwable byteFailure = catchThrowable(() -> byteProvider
                .loadMessagesAsync(ValkeyTestSupport.request("session", "run"))
                .toCompletableFuture()
                .join());

        // Assert
        assertIncompatible(countFailure);
        assertIncompatible(byteFailure);
    }

    @Test
    void append_shouldRejectAggregateDocumentBytesBeforeCallingValkey() {
        // Arrange
        RecordingAdapter adapter = new RecordingAdapter();
        ValkeyHistoryOptions options = ValkeyTestSupport.options(Duration.ofSeconds(5), null, 10, 5, 1024, 1024);
        ValkeyHistoryProvider provider = new ValkeyHistoryProvider(adapter, false, options);

        // Act
        Throwable thrown = catchThrowable(() -> provider.appendMessagesAsync(
                        ValkeyTestSupport.request("session", "run"),
                        List.of(
                                Message.text(Role.USER, "x".repeat(450)),
                                Message.text(Role.ASSISTANT, "y".repeat(450))))
                .toCompletableFuture()
                .join());

        // Assert
        assertIncompatible(thrown);
        assertThat(adapter.invocations).isEmpty();
    }

    @Test
    void clearAndCount_shouldUseAllRelatedKeysAndMessageListLength() {
        // Arrange
        RecordingAdapter adapter = new RecordingAdapter();
        adapter.lengthResult = CompletableFuture.completedFuture(17L);
        ValkeyHistoryProvider provider = new ValkeyHistoryProvider(adapter, false, ValkeyTestSupport.options());
        ContextProviderRequest request = ValkeyTestSupport.request("session", "run");

        // Act
        long count = provider.countAsync(request).toCompletableFuture().join();
        provider.clearAsync(request).toCompletableFuture().join();

        // Assert
        assertThat(count).isEqualTo(17);
        assertThat(utf8(adapter.lengthKey)).endsWith(":messages");
        assertThat(adapter.invocations).singleElement().satisfies(invocation -> {
            assertThat(invocation.script()).isEqualTo(ValkeyScript.CLEAR);
            assertThat(invocation.keys()).hasSize(3);
            assertThat(invocation.arguments()).isEmpty();
        });
    }

    @Test
    void cancellation_shouldFailBeforeEffectsAndRaceAnInFlightCommandWithoutCancellingIt() {
        // Arrange
        DefaultRunCancellation alreadyCancelled = new DefaultRunCancellation();
        alreadyCancelled.cancel();
        RecordingAdapter untouched = new RecordingAdapter();
        ValkeyHistoryProvider untouchedProvider =
                new ValkeyHistoryProvider(untouched, false, ValkeyTestSupport.options());

        DefaultRunCancellation inFlightCancellation = new DefaultRunCancellation();
        RecordingAdapter inFlight = new RecordingAdapter();
        CompletableFuture<Object> upstream = new CompletableFuture<>();
        inFlight.scriptHandler = ignored -> upstream;
        ValkeyHistoryProvider inFlightProvider =
                new ValkeyHistoryProvider(inFlight, false, ValkeyTestSupport.options());
        CompletionStage<Void> stage = inFlightProvider.appendMessagesAsync(
                ValkeyTestSupport.request("session", "run", inFlightCancellation),
                List.of(Message.text(Role.USER, "message")));

        // Act
        Throwable beforeEffects = catchThrowable(() -> untouchedProvider
                .appendMessagesAsync(
                        ValkeyTestSupport.request("session", "run", alreadyCancelled),
                        List.of(Message.text(Role.USER, "message")))
                .toCompletableFuture()
                .join());
        inFlightCancellation.cancel();
        Throwable raced = catchThrowable(() -> stage.toCompletableFuture().join());
        upstream.complete(new Object[] {0L, 1L});

        // Assert
        assertThat(beforeEffects.getCause()).isInstanceOf(RunCancelledException.class);
        assertThat(untouched.invocations).isEmpty();
        assertThat(raced.getCause()).isInstanceOf(RunCancelledException.class);
        assertThat(upstream).isCompleted().isNotCancelled();
        assertThat(stage.toCompletableFuture()).isCompletedExceptionally();
    }

    @Test
    void timeout_shouldBoundAnUnfinishedCommandWithoutCancellingTheSdkFuture() {
        // Arrange
        RecordingAdapter adapter = new RecordingAdapter();
        CompletableFuture<Long> upstream = new CompletableFuture<>();
        adapter.lengthResult = upstream;
        ValkeyHistoryOptions options = ValkeyTestSupport.options(Duration.ofMillis(25), null, 10, 5, 1024, 4096);
        ValkeyHistoryProvider provider = new ValkeyHistoryProvider(adapter, false, options);

        // Act
        Throwable thrown = catchThrowable(() -> provider.countAsync(ValkeyTestSupport.request("session", "run"))
                .toCompletableFuture()
                .join());

        // Assert
        assertThat(thrown.getCause())
                .isInstanceOfSatisfying(
                        ValkeyStorageException.class,
                        failure -> assertThat(failure.kind()).isEqualTo(ValkeyStorageException.Kind.TIMEOUT));
        assertThat(upstream).isNotCancelled();
    }

    @Test
    void close_shouldCloseOwnedAdapterExactlyOnceAndNeverCloseExternalAdapter() {
        // Arrange
        RecordingAdapter owned = new RecordingAdapter();
        ValkeyHistoryProvider ownedProvider = new ValkeyHistoryProvider(owned, true, ValkeyTestSupport.options());
        RecordingAdapter external = new RecordingAdapter();
        ValkeyHistoryProvider externalProvider =
                new ValkeyHistoryProvider(external, false, ValkeyTestSupport.options());

        // Act
        ownedProvider.close();
        ownedProvider.close();
        externalProvider.close();
        externalProvider.close();
        Throwable afterClose = catchThrowable(() -> ownedProvider
                .countAsync(ValkeyTestSupport.request("session", "run"))
                .toCompletableFuture()
                .join());

        // Assert
        assertThat(owned.closeCount).hasValue(1);
        assertThat(external.closeCount).hasValue(0);
        assertThat(afterClose.getCause())
                .isInstanceOfSatisfying(
                        ValkeyStorageException.class,
                        failure -> assertThat(failure.kind()).isEqualTo(ValkeyStorageException.Kind.CLOSED));
        assertThat(owned.lengthCalls).hasValue(0);
    }

    private static Stream<Arguments> corruptEntries() {
        ValkeyHistoryOptions normal = ValkeyTestSupport.options();
        ValkeyHistoryOptions small = ValkeyTestSupport.options(Duration.ofSeconds(5), null, 10, 5, 256, 1024);
        return Stream.of(
                Arguments.of("malformed", "{".getBytes(StandardCharsets.UTF_8), normal),
                Arguments.of(
                        "wrong-kind",
                        ("{\"documentKind\":\"agent-session\",\"format\":\"agent-framework-java-state\","
                                        + "\"payload\":{},\"payloadVersion\":1}")
                                .getBytes(StandardCharsets.UTF_8),
                        normal),
                Arguments.of(
                        "future-version",
                        ("{\"documentKind\":\"history-message\",\"format\":\"agent-framework-java-state\","
                                        + "\"payload\":{},\"payloadVersion\":2}")
                                .getBytes(StandardCharsets.UTF_8),
                        normal),
                Arguments.of(
                        "unknown-content",
                        ("{\"documentKind\":\"history-message\",\"format\":\"agent-framework-java-state\","
                                        + "\"payload\":{\"contents\":[{\"kind\":\"future\"}],\"role\":\"user\"},"
                                        + "\"payloadVersion\":1}")
                                .getBytes(StandardCharsets.UTF_8),
                        normal),
                Arguments.of("oversized", new byte[257], small));
    }

    private static void assertIncompatible(Throwable thrown) {
        assertThat(thrown).isInstanceOf(CompletionException.class);
        assertThat(thrown.getCause())
                .isInstanceOfSatisfying(
                        ValkeyStorageException.class,
                        failure -> assertThat(failure.kind()).isEqualTo(ValkeyStorageException.Kind.INCOMPATIBLE_DATA));
    }

    private static String hashTag(String key) {
        return key.substring(key.indexOf('{') + 1, key.indexOf('}'));
    }

    private static String utf8(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    private record Invocation(ValkeyScript script, List<byte[]> keys, List<byte[]> arguments) {}

    private static final class RecordingAdapter implements ValkeyCommandAdapter {
        private final List<Invocation> invocations = new ArrayList<>();

        private final AtomicInteger closeCount = new AtomicInteger();

        private final AtomicInteger lengthCalls = new AtomicInteger();

        private CompletionStage<List<byte[]>> rangeResult = CompletableFuture.completedFuture(List.of());

        private Function<Invocation, CompletionStage<Object>> scriptHandler =
                invocation -> switch (invocation.script()) {
                    case APPEND -> CompletableFuture.completedFuture(new Object[] {0L, 0L});
                    case LOAD -> rangeResult.thenApply(value -> value);
                    case CLEAR -> CompletableFuture.completedFuture(0L);
                };

        private CompletionStage<Long> lengthResult = CompletableFuture.completedFuture(0L);

        private byte[] lengthKey;

        @Override
        public CompletionStage<Object> invokeScript(ValkeyScript script, List<byte[]> keys, List<byte[]> arguments) {
            Invocation invocation = new Invocation(script, copy(keys), copy(arguments));
            invocations.add(invocation);
            return scriptHandler.apply(invocation);
        }

        @Override
        public CompletionStage<Long> listLength(byte[] key) {
            lengthCalls.incrementAndGet();
            lengthKey = key.clone();
            return lengthResult;
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }

        private static List<byte[]> copy(List<byte[]> values) {
            return values.stream().map(byte[]::clone).toList();
        }
    }
}
