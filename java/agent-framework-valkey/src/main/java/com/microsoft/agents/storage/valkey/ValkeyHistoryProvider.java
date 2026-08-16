// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.valkey;

import com.microsoft.agents.agents.ContextProviderRequest;
import com.microsoft.agents.agents.HistoryProvider;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.ValidationException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stores bounded chronological history in a standalone Valkey deployment.
 *
 * <p>Tenant, isolation, agent, and session identifiers are length-prefixed and SHA-256 normalized.
 * The message list and bounded deduplication structures share one Valkey hash tag. Appends execute
 * one same-slot Lua operation that binds the hashed run identifier to a stable payload digest,
 * appends in order, trims retained history, bounds deduplication metadata, and refreshes TTL.
 *
 * <p>Cancellation and the configured deadline race command completion without relying on
 * {@link CompletableFuture#cancel(boolean)}. A command already accepted by GLIDE or Valkey may still
 * finish after the returned stage reports {@link RunCancelledException} or timeout; retrying the
 * same run and payload is idempotent.
 */
public final class ValkeyHistoryProvider implements HistoryProvider, AutoCloseable {
    private static final int MAX_APPEND_MESSAGES = 10_000;

    private final ValkeyHistoryOptions options;

    private final ValkeyCommandAdapter commands;

    private final boolean ownsClient;

    private final ValkeyMessageCodec messageCodec;

    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a provider and an owned official GLIDE client.
     *
     * @param options bounded history and client options
     * @return stage producing an owned Valkey history provider
     */
    public static CompletionStage<ValkeyHistoryProvider> createAsync(ValkeyHistoryOptions options) {
        return createAsync(options, new DefaultRunCancellation());
    }

    /**
     * Creates a provider and an owned official GLIDE client with cancellation.
     *
     * <p>If cancellation or timeout wins while the native client is still being created, the
     * factory closes a client that completes later.
     *
     * @param options bounded history and client options
     * @param cancellation creation cancellation signal
     * @return stage producing an owned Valkey history provider
     */
    public static CompletionStage<ValkeyHistoryProvider> createAsync(
            ValkeyHistoryOptions options, RunCancellation cancellation) {
        ValkeyHistoryOptions checked = ValkeyValidation.requireNonNull(options, "options");
        return GlideValkeyClientFactory.createAsync(
                        checked.client(), ValkeyValidation.requireNonNull(cancellation, "cancellation"))
                .thenApply(commands -> new ValkeyHistoryProvider(commands, true, checked));
    }

    ValkeyHistoryProvider(ValkeyCommandAdapter commands, boolean ownsClient, ValkeyHistoryOptions options) {
        this.commands = ValkeyValidation.requireNonNull(commands, "commands");
        this.ownsClient = ownsClient;
        this.options = ValkeyValidation.requireNonNull(options, "options");
        this.messageCodec = new ValkeyMessageCodec(options.maxMessageBytes());
    }

    @Override
    public String id() {
        return options.providerId();
    }

    @Override
    public CompletionStage<List<Message>> loadMessagesAsync(ContextProviderRequest request) {
        RuntimeException preflight = preflight(request);
        if (preflight != null) {
            return CompletableFuture.failedStage(preflight);
        }
        ValkeyHistoryKeys keys = keys(request);
        CompletionStage<Object> upstream = commands.invokeScript(
                ValkeyScript.LOAD,
                List.of(utf8(keys.messages())),
                List.of(
                        ascii(options.maxLoadedMessages()),
                        ascii(options.maxMessageBytes()),
                        ascii(options.maxDocumentBytes())));
        if (upstream == null) {
            return CompletableFuture.failedStage(serviceFailure("Valkey load returned no completion stage."));
        }
        return ValkeyAsyncSupport.race(
                        upstream,
                        options.client().operationTimeout(),
                        request.runContext().cancellation())
                .thenApply(this::decodeLoadResult);
    }

    @Override
    public CompletionStage<Void> appendMessagesAsync(ContextProviderRequest request, List<Message> messages) {
        RuntimeException preflight = preflight(request);
        if (preflight != null) {
            return CompletableFuture.failedStage(preflight);
        }
        ValidationException invalid = validateMessages(messages);
        if (invalid != null) {
            return CompletableFuture.failedStage(invalid);
        }
        if (messages.isEmpty()) {
            return CompletableFuture.completedStage(null);
        }

        List<byte[]> encoded;
        try {
            encoded = encodeMessages(List.copyOf(messages));
        } catch (RuntimeException exception) {
            return CompletableFuture.failedStage(exception);
        }
        ValkeyHistoryKeys keys = keys(request);
        ArrayList<byte[]> arguments = new ArrayList<>(6 + encoded.size());
        arguments.add(
                utf8(ValkeyKeyDerivation.operationToken(request.runContext().runId())));
        arguments.add(utf8(ValkeyKeyDerivation.appendDigest(encoded)));
        arguments.add(ascii(options.maxStoredMessages()));
        arguments.add(ascii(deduplicationLimit(options.maxStoredMessages())));
        arguments.add(ascii(timeToLiveMillis()));
        arguments.add(ascii(encoded.size()));
        encoded.forEach(value -> arguments.add(value.clone()));

        CompletionStage<Object> upstream =
                commands.invokeScript(ValkeyScript.APPEND, scriptKeys(keys), List.copyOf(arguments));
        if (upstream == null) {
            return CompletableFuture.failedStage(serviceFailure("Valkey append returned no completion stage."));
        }
        return ValkeyAsyncSupport.race(
                        upstream,
                        options.client().operationTimeout(),
                        request.runContext().cancellation())
                .thenApply(this::validateAppendResult);
    }

    /**
     * Atomically removes the message list and both deduplication structures for one session.
     *
     * @param request provider request identifying the session and cancellation signal
     * @return completion stage
     */
    public CompletionStage<Void> clearAsync(ContextProviderRequest request) {
        RuntimeException preflight = preflight(request);
        if (preflight != null) {
            return CompletableFuture.failedStage(preflight);
        }
        ValkeyHistoryKeys keys = keys(request);
        CompletionStage<Object> upstream = commands.invokeScript(ValkeyScript.CLEAR, scriptKeys(keys), List.of());
        if (upstream == null) {
            return CompletableFuture.failedStage(serviceFailure("Valkey clear returned no completion stage."));
        }
        return ValkeyAsyncSupport.race(
                        upstream,
                        options.client().operationTimeout(),
                        request.runContext().cancellation())
                .thenApply(this::validateClearResult);
    }

    /**
     * Returns the Valkey list length for one session.
     *
     * @param request provider request identifying the session and cancellation signal
     * @return stage producing the stored message count
     */
    public CompletionStage<Long> countAsync(ContextProviderRequest request) {
        RuntimeException preflight = preflight(request);
        if (preflight != null) {
            return CompletableFuture.failedStage(preflight);
        }
        ValkeyHistoryKeys keys = keys(request);
        CompletionStage<Long> upstream = commands.listLength(utf8(keys.messages()));
        if (upstream == null) {
            return CompletableFuture.failedStage(serviceFailure("Valkey list length returned no completion stage."));
        }
        return ValkeyAsyncSupport.race(
                        upstream,
                        options.client().operationTimeout(),
                        request.runContext().cancellation())
                .thenApply(value -> {
                    if (value == null || value < 0) {
                        throw serviceFailure("Valkey returned an invalid list length.");
                    }
                    return value;
                });
    }

    /**
     * Closes an owned client exactly once.
     *
     * <p>A package-internal externally owned adapter remains caller owned.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true) && ownsClient) {
            commands.close();
        }
    }

    static int deduplicationLimit(int maxStoredMessages) {
        return maxStoredMessages;
    }

    private RuntimeException preflight(ContextProviderRequest request) {
        if (request == null) {
            return new ValidationException("request must not be null.");
        }
        if (request.runContext().cancellation().isCancellationRequested()) {
            return new RunCancelledException();
        }
        if (closed.get()) {
            return new ValkeyStorageException(
                    "The Valkey history provider is closed.", null, ValkeyStorageException.Kind.CLOSED);
        }
        return null;
    }

    private ValidationException validateMessages(List<Message> messages) {
        if (messages == null) {
            return new ValidationException("messages must not be null.");
        }
        if (messages.size() > MAX_APPEND_MESSAGES) {
            return new ValidationException("messages must contain at most 10000 entries.");
        }
        if (messages.stream().anyMatch(Objects::isNull)) {
            return new ValidationException("messages must not contain null.");
        }
        return null;
    }

    private List<byte[]> encodeMessages(List<Message> messages) {
        ArrayList<byte[]> encoded = new ArrayList<>(messages.size());
        long totalBytes = 0;
        for (Message message : messages) {
            byte[] value = messageCodec.encode(message);
            totalBytes += value.length;
            if (totalBytes > options.maxDocumentBytes()) {
                throw new ValkeyStorageException(
                        "The append payload exceeds configured document limits.",
                        null,
                        ValkeyStorageException.Kind.INCOMPATIBLE_DATA);
            }
            encoded.add(value);
        }
        return List.copyOf(encoded);
    }

    private List<Message> decodeMessages(List<byte[]> values) {
        if (values == null || values.size() > options.maxLoadedMessages()) {
            throw new ValkeyStorageException(
                    "Valkey returned an invalid number of history messages.",
                    null,
                    ValkeyStorageException.Kind.INCOMPATIBLE_DATA);
        }
        ArrayList<Message> messages = new ArrayList<>(values.size());
        long totalBytes = 0;
        for (byte[] value : values) {
            if (value == null) {
                throw new ValkeyStorageException(
                        "Valkey returned a null history message.", null, ValkeyStorageException.Kind.INCOMPATIBLE_DATA);
            }
            totalBytes += value.length;
            if (totalBytes > options.maxDocumentBytes()) {
                throw new ValkeyStorageException(
                        "Loaded history exceeds configured document limits.",
                        null,
                        ValkeyStorageException.Kind.INCOMPATIBLE_DATA);
            }
            messages.add(messageCodec.decode(value.clone()));
        }
        return List.copyOf(messages);
    }

    private List<Message> decodeLoadResult(Object raw) {
        if (!(raw instanceof List<?> values)) {
            throw serviceFailure("Valkey returned an invalid history load result.");
        }
        ArrayList<byte[]> encoded = new ArrayList<>(values.size());
        for (Object value : values) {
            if (!(value instanceof byte[] bytes)) {
                throw serviceFailure("Valkey returned an invalid history load entry.");
            }
            encoded.add(bytes.clone());
        }
        return decodeMessages(List.copyOf(encoded));
    }

    private Void validateAppendResult(Object raw) {
        if (!(raw instanceof Object[] values)
                || values.length != 2
                || !(values[0] instanceof Long status)
                || !(values[1] instanceof Long length)
                || length < 0) {
            throw serviceFailure("Valkey returned an invalid append result.");
        }
        if (status == 0 || status == 1) {
            return null;
        }
        if (status == 2) {
            throw new ValkeyStorageException(
                    "The append operation identifier is already bound to another payload.",
                    null,
                    ValkeyStorageException.Kind.CONFLICT);
        }
        throw serviceFailure("Valkey returned an unknown append result.");
    }

    private Void validateClearResult(Object raw) {
        if (!(raw instanceof Long deleted) || deleted < 0 || deleted > 3) {
            throw serviceFailure("Valkey returned an invalid clear result.");
        }
        return null;
    }

    private ValkeyHistoryKeys keys(ContextProviderRequest request) {
        return ValkeyKeyDerivation.historyKeys(options, request.session().sessionId());
    }

    private List<byte[]> scriptKeys(ValkeyHistoryKeys keys) {
        return List.of(utf8(keys.messages()), utf8(keys.deduplication()), utf8(keys.deduplicationOrder()));
    }

    private long timeToLiveMillis() {
        return options.timeToLive() == null ? 0 : options.timeToLive().toMillis();
    }

    private static byte[] ascii(long value) {
        return Long.toString(value).getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] utf8(String value) {
        return ValkeyValidation.utf8(value, "value");
    }

    private static ValkeyStorageException serviceFailure(String message) {
        return new ValkeyStorageException(message, null, ValkeyStorageException.Kind.SERVICE);
    }
}
