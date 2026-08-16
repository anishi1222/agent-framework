// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.valkey;

import glide.api.GlideClient;
import glide.api.models.GlideString;
import glide.api.models.Script;
import glide.api.models.commands.ScriptOptionsGlideString;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

final class GlideValkeyCommandAdapter implements ValkeyCommandAdapter {
    private final GlideClient client;

    private final Map<ValkeyScript, Script> scripts = new EnumMap<>(ValkeyScript.class);

    private final AtomicBoolean closed = new AtomicBoolean();

    GlideValkeyCommandAdapter(GlideClient client) {
        this.client = ValkeyValidation.requireNonNull(client, "client");
        try {
            for (ValkeyScript script : ValkeyScript.values()) {
                scripts.put(script, new Script(script.source(), true));
            }
        } catch (RuntimeException | Error failure) {
            scripts.values().forEach(value -> closeAfterInitializationFailure(value, failure));
            throw failure;
        }
    }

    @Override
    public CompletionStage<Object> invokeScript(ValkeyScript script, List<byte[]> keys, List<byte[]> arguments) {
        try {
            ScriptOptionsGlideString options = ScriptOptionsGlideString.builder()
                    .keys(toGlideStrings(keys))
                    .args(toGlideStrings(arguments))
                    .build();
            return GlideValkeyFailureMapper.mapStage(client.invokeScript(requireScript(script), options))
                    .thenApply(value -> normalizeScriptResult(script, value));
        } catch (glide.api.models.exceptions.GlideException exception) {
            return java.util.concurrent.CompletableFuture.failedStage(GlideValkeyFailureMapper.map(exception));
        }
    }

    @Override
    public CompletionStage<Long> listLength(byte[] key) {
        try {
            return GlideValkeyFailureMapper.mapStage(client.llen(GlideString.of(key)));
        } catch (glide.api.models.exceptions.GlideException exception) {
            return java.util.concurrent.CompletableFuture.failedStage(GlideValkeyFailureMapper.map(exception));
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ValkeyStorageException failure = null;
        for (Script script : scripts.values()) {
            try {
                script.close();
            } catch (Exception exception) {
                failure = combine(failure, GlideValkeyFailureMapper.map(exception));
            }
        }
        try {
            client.close();
        } catch (RuntimeException | java.util.concurrent.ExecutionException exception) {
            failure = combine(failure, GlideValkeyFailureMapper.map(exception));
        }
        if (failure != null) {
            throw failure;
        }
    }

    private Script requireScript(ValkeyScript script) {
        Script value = scripts.get(ValkeyValidation.requireNonNull(script, "script"));
        if (value == null) {
            throw new ValkeyStorageException(
                    "The requested Valkey script is unavailable.", null, ValkeyStorageException.Kind.CLOSED);
        }
        return value;
    }

    private static List<GlideString> toGlideStrings(List<byte[]> values) {
        ArrayList<GlideString> result = new ArrayList<>(values.size());
        for (byte[] value : values) {
            result.add(GlideString.of(ValkeyValidation.requireNonNull(value, "value")));
        }
        return result;
    }

    private static Object normalizeScriptResult(ValkeyScript script, Object value) {
        if (script != ValkeyScript.LOAD) {
            return value;
        }
        if (!(value instanceof Object[] values)) {
            throw new ValkeyStorageException(
                    "Valkey returned an invalid history load result.", null, ValkeyStorageException.Kind.SERVICE);
        }
        ArrayList<byte[]> result = new ArrayList<>(values.length);
        for (Object item : values) {
            if (!(item instanceof GlideString string)) {
                throw new ValkeyStorageException(
                        "Valkey returned an invalid history load entry.", null, ValkeyStorageException.Kind.SERVICE);
            }
            result.add(string.getBytes());
        }
        return List.copyOf(result);
    }

    private static ValkeyStorageException combine(ValkeyStorageException current, RuntimeException next) {
        ValkeyStorageException mapped = next instanceof ValkeyStorageException valkey
                ? valkey
                : new ValkeyStorageException(
                        "The Valkey client could not be closed.", next, ValkeyStorageException.Kind.SERVICE);
        if (current == null) {
            return mapped;
        }
        current.addSuppressed(mapped);
        return current;
    }

    private static void closeAfterInitializationFailure(Script script, Throwable failure) {
        try {
            script.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
