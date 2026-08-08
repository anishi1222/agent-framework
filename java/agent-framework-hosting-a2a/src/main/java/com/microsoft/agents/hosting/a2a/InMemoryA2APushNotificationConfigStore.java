// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.a2a;

import com.microsoft.agents.protocols.a2a.A2ACursorPage;
import com.microsoft.agents.protocols.a2a.A2AException;
import com.microsoft.agents.protocols.a2a.A2ARequests;
import com.microsoft.agents.protocols.a2a.PushNotificationConfig;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Implements bounded, principal-isolated process-memory push-configuration storage. */
public final class InMemoryA2APushNotificationConfigStore
        implements A2APushNotificationConfigStore {
    private final int maxConfigurations;
    private final Map<ConfigKey, PushNotificationConfig> configurations =
            new LinkedHashMap<>();

    /** Creates a bounded store. */
    public InMemoryA2APushNotificationConfigStore(int maxConfigurations) {
        this.maxConfigurations =
                HostingA2AValidation.positive(maxConfigurations, "maxConfigurations");
    }

    /** Creates a store retaining at most 10,000 configurations. */
    public InMemoryA2APushNotificationConfigStore() {
        this(10_000);
    }

    @Override
    public synchronized CompletionStage<PushNotificationConfig> putAsync(
            A2APrincipal principal, PushNotificationConfig config) {
        if (config.taskId() == null) {
            return CompletableFuture.failedFuture(
                    new A2AException("Stored push configuration requires taskId."));
        }
        ConfigKey key = key(principal, config.taskId(), config.id());
        if (!configurations.containsKey(key)
                && configurations.size() >= maxConfigurations) {
            return CompletableFuture.failedFuture(
                    new A2AException("In-memory push configuration capacity is exhausted."));
        }
        configurations.put(key, config);
        return CompletableFuture.completedFuture(config);
    }

    @Override
    public synchronized CompletionStage<Optional<PushNotificationConfig>> getAsync(
            A2APrincipal principal, A2ARequests.GetPushConfig request) {
        return CompletableFuture.completedFuture(Optional.ofNullable(
                configurations.get(key(
                        principal, request.taskId(), request.configId()))));
    }

    @Override
    public synchronized CompletionStage<A2ACursorPage<PushNotificationConfig>> listAsync(
            A2APrincipal principal, A2ARequests.ListPushConfigs request) {
        ArrayList<PushNotificationConfig> visible = new ArrayList<>();
        configurations.forEach((key, value) -> {
            if (key.matches(principal, request.taskId())) {
                visible.add(value);
            }
        });
        visible.sort(Comparator.comparing(PushNotificationConfig::id));
        int offset = decodeCursor(request.pageToken(), request.taskId());
        if (offset > visible.size()) {
            return CompletableFuture.failedFuture(
                    new A2AException("Push configuration page token is outside the result set."));
        }
        int end = Math.min(visible.size(), offset + request.pageSize());
        String next = end < visible.size()
                ? encodeCursor(end, request.taskId())
                : null;
        return CompletableFuture.completedFuture(new A2ACursorPage<>(
                List.copyOf(visible.subList(offset, end)),
                next,
                request.pageSize(),
                (long) visible.size()));
    }

    @Override
    public synchronized CompletionStage<Boolean> deleteAsync(
            A2APrincipal principal, A2ARequests.DeletePushConfig request) {
        configurations.remove(key(
                principal, request.taskId(), request.configId()));
        return CompletableFuture.completedFuture(Boolean.TRUE);
    }

    private static String encodeCursor(int offset, String taskId) {
        String raw = "v1:" + offset + ":" + Integer.toUnsignedString(taskId.hashCode(), 16);
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static int decodeCursor(String token, String taskId) {
        if (token == null) {
            return 0;
        }
        try {
            String raw = new String(
                    Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = raw.split(":", -1);
            if (parts.length != 3
                    || !"v1".equals(parts[0])
                    || !Integer.toUnsignedString(taskId.hashCode(), 16)
                            .equals(parts[2])) {
                throw new IllegalArgumentException("cursor mismatch");
            }
            int offset = Integer.parseInt(parts[1]);
            if (offset < 0) {
                throw new IllegalArgumentException("negative offset");
            }
            return offset;
        } catch (IllegalArgumentException failure) {
            throw new A2AException("Push configuration page token is invalid.", failure);
        }
    }

    private static ConfigKey key(
            A2APrincipal principal, String taskId, String configId) {
        HostingA2AValidation.required(principal, "principal");
        return new ConfigKey(
                principal.principalId(),
                principal.isolationKey(),
                HostingA2AValidation.nonBlank(taskId, "taskId"),
                HostingA2AValidation.nonBlank(configId, "configId"));
    }

    private record ConfigKey(
            String principalId, String isolationKey, String taskId, String configId) {
        private boolean matches(A2APrincipal principal, String expectedTaskId) {
            return principalId.equals(principal.principalId())
                    && isolationKey.equals(principal.isolationKey())
                    && taskId.equals(expectedTaskId);
        }
    }
}
