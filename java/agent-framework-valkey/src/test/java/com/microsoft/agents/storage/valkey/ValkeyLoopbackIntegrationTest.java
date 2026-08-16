// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.valkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import glide.api.GlideClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Opt-in loopback Valkey coverage using the official GLIDE client.
 *
 * <p>Set {@code VALKEY_INTEGRATION_TESTS=true}, {@code VALKEY_HOST}, and {@code VALKEY_PORT}. Optional
 * settings are {@code VALKEY_TLS=true}, {@code VALKEY_USERNAME}, and {@code VALKEY_PASSWORD}.
 * Non-loopback hosts are always skipped. TLS is exercised only when the supplied loopback server is
 * configured for TLS.
 */
class ValkeyLoopbackIntegrationTest {
    @Test
    void loopback_shouldExecuteAppendReplayConflictTrimTtlConcurrencyAndClear() throws Exception {
        LoopbackEnvironment environment = LoopbackEnvironment.require();
        String isolation = UUID.randomUUID().toString();
        ValkeyPassword password = environment.password() == null ? null : ValkeyPassword.of(environment.password());
        ValkeyAuthentication authentication = password == null
                ? ValkeyAuthentication.none()
                : ValkeyAuthentication.acl(environment.username(), password);
        ValkeyClientOptions client = new ValkeyClientOptions(
                new ValkeyEndpoint(environment.host(), environment.port()),
                authentication,
                environment.tls(),
                "agent-framework-valkey-integration",
                Duration.ofSeconds(10));
        ValkeyHistoryOptions options = new ValkeyHistoryOptions(
                client,
                new ValkeyPartitionContext("integration", isolation, "valkey-history"),
                "integration-history",
                "agent-framework:integration",
                64,
                64,
                Duration.ofMinutes(5),
                1024 * 1024,
                8 * 1024 * 1024);
        ValkeyHistoryProvider provider = null;
        List<String> sessions = List.of("idempotency", "trim", "concurrency", "ttl");
        try {
            provider = ValkeyHistoryProvider.createAsync(options)
                    .toCompletableFuture()
                    .join();
            ValkeyHistoryProvider activeProvider = provider;

            var idempotencyRequest = ValkeyTestSupport.request("idempotency", "operation-1");
            List<Message> pair = List.of(Message.text(Role.USER, "first"), Message.text(Role.ASSISTANT, "second"));
            provider.appendMessagesAsync(idempotencyRequest, pair)
                    .toCompletableFuture()
                    .join();
            provider.appendMessagesAsync(idempotencyRequest, pair)
                    .toCompletableFuture()
                    .join();
            assertThat(provider.countAsync(idempotencyRequest)
                            .toCompletableFuture()
                            .join())
                    .isEqualTo(2);
            assertThat(provider.loadMessagesAsync(idempotencyRequest)
                            .toCompletableFuture()
                            .join())
                    .extracting(Message::text)
                    .containsExactly("first", "second");

            Throwable conflict = catchThrowable(() -> activeProvider
                    .appendMessagesAsync(idempotencyRequest, List.of(Message.text(Role.USER, "different")))
                    .toCompletableFuture()
                    .join());
            assertThat(conflict).isInstanceOf(CompletionException.class);
            assertThat(conflict.getCause())
                    .isInstanceOfSatisfying(
                            ValkeyStorageException.class,
                            failure -> assertThat(failure.kind()).isEqualTo(ValkeyStorageException.Kind.CONFLICT));

            for (int index = 0; index < 70; index++) {
                provider.appendMessagesAsync(
                                ValkeyTestSupport.request("trim", "trim-" + index),
                                List.of(Message.text(Role.USER, "message-" + index)))
                        .toCompletableFuture()
                        .join();
            }
            List<Message> trimmed = provider.loadMessagesAsync(ValkeyTestSupport.request("trim", "load"))
                    .toCompletableFuture()
                    .join();
            assertThat(trimmed).hasSize(64);
            assertThat(trimmed.getFirst().text()).isEqualTo("message-6");
            assertThat(trimmed.getLast().text()).isEqualTo("message-69");

            ArrayList<CompletableFuture<Void>> concurrent = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                concurrent.add(provider.appendMessagesAsync(
                                ValkeyTestSupport.request("concurrency", "concurrent-" + index),
                                List.of(Message.text(Role.USER, "value-" + index)))
                        .toCompletableFuture());
            }
            CompletableFuture.allOf(concurrent.toArray(CompletableFuture[]::new))
                    .join();
            List<Message> concurrentMessages = provider.loadMessagesAsync(
                            ValkeyTestSupport.request("concurrency", "load"))
                    .toCompletableFuture()
                    .join();
            assertThat(concurrentMessages).hasSize(32);
            assertThat(new HashSet<>(
                            concurrentMessages.stream().map(Message::text).toList()))
                    .isEqualTo(expectedValues(32));

            ValkeyHistoryOptions ttlOptions = new ValkeyHistoryOptions(
                    client,
                    options.partition(),
                    "integration-ttl",
                    options.keyPrefix(),
                    10,
                    10,
                    Duration.ofMillis(500),
                    options.maxMessageBytes(),
                    options.maxDocumentBytes());
            try (ValkeyHistoryProvider ttlProvider = ValkeyHistoryProvider.createAsync(ttlOptions)
                    .toCompletableFuture()
                    .join()) {
                var ttlRequest = ValkeyTestSupport.request("ttl", "ttl-1");
                ttlProvider
                        .appendMessagesAsync(ttlRequest, List.of(Message.text(Role.USER, "expires")))
                        .toCompletableFuture()
                        .join();
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (ttlProvider.countAsync(ttlRequest).toCompletableFuture().join() != 0
                        && System.nanoTime() < deadline) {
                    Thread.sleep(50);
                }
                assertThat(ttlProvider
                                .countAsync(ttlRequest)
                                .toCompletableFuture()
                                .join())
                        .isZero();
            }

            for (String session : sessions) {
                var request = ValkeyTestSupport.request(session, "cleanup");
                provider.clearAsync(request).toCompletableFuture().join();
                assertThat(provider.countAsync(request).toCompletableFuture().join())
                        .isZero();
            }
        } finally {
            if (provider != null) {
                for (String session : sessions) {
                    try {
                        provider.clearAsync(ValkeyTestSupport.request(session, "cleanup-final"))
                                .toCompletableFuture()
                                .join();
                    } catch (RuntimeException ignored) {
                        // Preserve the original integration failure; keys also expire by TTL.
                    }
                }
                provider.close();
            }
            if (password != null) {
                password.close();
            }
        }
    }

    @Test
    void loopback_shouldRejectWrongKeyTypesBeforeAppendOrLoadEffects() throws Exception {
        LoopbackEnvironment environment = LoopbackEnvironment.require();
        String isolation = UUID.randomUUID().toString();
        ValkeyPassword password = environment.password() == null ? null : ValkeyPassword.of(environment.password());
        ValkeyAuthentication authentication = password == null
                ? ValkeyAuthentication.none()
                : ValkeyAuthentication.acl(environment.username(), password);
        ValkeyClientOptions clientOptions = new ValkeyClientOptions(
                new ValkeyEndpoint(environment.host(), environment.port()),
                authentication,
                environment.tls(),
                "agent-framework-valkey-wrong-type-integration",
                Duration.ofSeconds(10));
        ValkeyHistoryOptions options = new ValkeyHistoryOptions(
                clientOptions,
                new ValkeyPartitionContext("integration", isolation, "valkey-history"),
                "integration-wrong-types",
                "agent-framework:integration",
                16,
                16,
                Duration.ofMinutes(5),
                1024 * 1024,
                8 * 1024 * 1024);
        List<String> sessions = List.of("wrong-messages", "wrong-dedup", "wrong-order");
        List<ValkeyHistoryKeys> keys = sessions.stream()
                .map(session -> ValkeyKeyDerivation.historyKeys(options, session))
                .toList();
        GlideClient rawClient = null;
        ValkeyHistoryProvider provider = null;
        try {
            rawClient = GlideValkeyClientFactory.createGlideClientAsync(clientOptions)
                    .toCompletableFuture()
                    .join();
            provider = ValkeyHistoryProvider.createAsync(options)
                    .toCompletableFuture()
                    .join();

            assertWrongMessagesType(rawClient, provider, options, sessions.get(0));
            assertWrongDedupType(rawClient, provider, options, sessions.get(1));
            assertWrongDedupOrderType(rawClient, provider, options, sessions.get(2));
        } finally {
            if (rawClient != null) {
                for (ValkeyHistoryKeys sessionKeys : keys) {
                    command(
                            rawClient,
                            "DEL",
                            sessionKeys.messages(),
                            sessionKeys.deduplication(),
                            sessionKeys.deduplicationOrder());
                }
            }
            if (provider != null) {
                provider.close();
            }
            if (rawClient != null) {
                rawClient.close();
            }
            if (password != null) {
                password.close();
            }
        }
    }

    private static void assertWrongMessagesType(
            GlideClient client, ValkeyHistoryProvider provider, ValkeyHistoryOptions options, String session) {
        ValkeyHistoryKeys keys = ValkeyKeyDerivation.historyKeys(options, session);
        command(client, "SET", keys.messages(), "messages-before");
        command(client, "HSET", keys.deduplication(), "existing-operation", "existing-digest");
        command(client, "RPUSH", keys.deduplicationOrder(), "existing-operation");

        assertWrongType(() -> provider.appendMessagesAsync(
                        ValkeyTestSupport.request(session, "new-operation"),
                        List.of(Message.text(Role.USER, "must-not-append")))
                .toCompletableFuture()
                .join());
        assertWrongType(() -> provider.loadMessagesAsync(ValkeyTestSupport.request(session, "load"))
                .toCompletableFuture()
                .join());

        assertThat(command(client, "GET", keys.messages())).isEqualTo("messages-before");
        assertThat(command(client, "HGET", keys.deduplication(), "existing-operation"))
                .isEqualTo("existing-digest");
        assertThat((Object[]) command(client, "LRANGE", keys.deduplicationOrder(), "0", "-1"))
                .containsExactly("existing-operation");
    }

    private static void assertWrongDedupType(
            GlideClient client, ValkeyHistoryProvider provider, ValkeyHistoryOptions options, String session) {
        ValkeyHistoryKeys keys = ValkeyKeyDerivation.historyKeys(options, session);
        command(client, "RPUSH", keys.messages(), "history-before");
        command(client, "SET", keys.deduplication(), "dedup-before");
        command(client, "RPUSH", keys.deduplicationOrder(), "existing-operation");

        assertWrongType(() -> provider.appendMessagesAsync(
                        ValkeyTestSupport.request(session, "new-operation"),
                        List.of(Message.text(Role.USER, "must-not-append")))
                .toCompletableFuture()
                .join());

        assertThat((Object[]) command(client, "LRANGE", keys.messages(), "0", "-1"))
                .containsExactly("history-before");
        assertThat(command(client, "GET", keys.deduplication())).isEqualTo("dedup-before");
        assertThat((Object[]) command(client, "LRANGE", keys.deduplicationOrder(), "0", "-1"))
                .containsExactly("existing-operation");
    }

    private static void assertWrongDedupOrderType(
            GlideClient client, ValkeyHistoryProvider provider, ValkeyHistoryOptions options, String session) {
        ValkeyHistoryKeys keys = ValkeyKeyDerivation.historyKeys(options, session);
        command(client, "RPUSH", keys.messages(), "history-before");
        command(client, "HSET", keys.deduplication(), "existing-operation", "existing-digest");
        command(client, "SET", keys.deduplicationOrder(), "order-before");

        assertWrongType(() -> provider.appendMessagesAsync(
                        ValkeyTestSupport.request(session, "new-operation"),
                        List.of(Message.text(Role.USER, "must-not-append")))
                .toCompletableFuture()
                .join());

        assertThat((Object[]) command(client, "LRANGE", keys.messages(), "0", "-1"))
                .containsExactly("history-before");
        assertThat(command(client, "HGET", keys.deduplication(), "existing-operation"))
                .isEqualTo("existing-digest");
        assertThat(command(client, "GET", keys.deduplicationOrder())).isEqualTo("order-before");
    }

    private static void assertWrongType(Runnable operation) {
        Throwable thrown = catchThrowable(operation::run);

        assertThat(thrown).isInstanceOf(CompletionException.class);
        assertThat(thrown.getCause())
                .isInstanceOfSatisfying(
                        ValkeyStorageException.class,
                        failure -> assertThat(failure.kind()).isEqualTo(ValkeyStorageException.Kind.INCOMPATIBLE_DATA));
    }

    private static Object command(GlideClient client, String... arguments) {
        return client.customCommand(arguments).join();
    }

    private static Set<String> expectedValues(int count) {
        HashSet<String> values = new HashSet<>();
        for (int index = 0; index < count; index++) {
            values.add("value-" + index);
        }
        return Set.copyOf(values);
    }

    private record LoopbackEnvironment(String host, int port, boolean tls, String username, String password) {
        private static LoopbackEnvironment require() {
            String enabled = System.getenv("VALKEY_INTEGRATION_TESTS");
            String host = System.getenv("VALKEY_HOST");
            String portText = System.getenv("VALKEY_PORT");
            Assumptions.assumeTrue(
                    "true".equalsIgnoreCase(enabled),
                    "Set VALKEY_INTEGRATION_TESTS=true to run loopback Valkey integration tests.");
            Assumptions.assumeTrue(
                    host != null && !host.isBlank() && portText != null && !portText.isBlank(),
                    "VALKEY_HOST and VALKEY_PORT are required.");
            String normalized = host.toLowerCase(java.util.Locale.ROOT);
            Assumptions.assumeTrue(
                    normalized.equals("localhost") || normalized.equals("127.0.0.1") || normalized.equals("::1"),
                    "Valkey integration tests only accept a loopback host.");
            int port;
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException exception) {
                Assumptions.abort("VALKEY_PORT must be an integer.");
                throw exception;
            }
            String username = System.getenv("VALKEY_USERNAME");
            String password = System.getenv("VALKEY_PASSWORD");
            Assumptions.assumeTrue(
                    (username == null || username.isBlank()) == (password == null || password.isBlank()),
                    "VALKEY_USERNAME and VALKEY_PASSWORD must be provided together.");
            return new LoopbackEnvironment(
                    host,
                    port,
                    Boolean.parseBoolean(System.getenv("VALKEY_TLS")),
                    username == null || username.isBlank() ? null : username,
                    password == null || password.isBlank() ? null : password);
        }
    }
}
