// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import com.microsoft.agents.core.StateValue;
import java.time.Instant;
import java.util.Map;

/** Defines immutable request values for task, card, and push-configuration operations. */
public final class A2ARequests {
    private A2ARequests() {}

    /**
     * Requests one task.
     *
     * @param taskId task identifier
     * @param historyLength optional non-negative history length
     * @param tenant optional tenant
     */
    public record GetTask(String taskId, Integer historyLength, String tenant) {
        /** Creates a validated request. */
        public GetTask {
            taskId = A2AValidation.nonBlank(taskId, "taskId");
            if (historyLength != null) {
                A2AValidation.nonNegative(historyLength, "historyLength");
            }
            tenant = A2AValidation.optionalNonBlank(tenant, "tenant");
        }

        /** Creates a request without optional fields. */
        public GetTask(String taskId) {
            this(taskId, null, null);
        }
    }

    /**
     * Lists tasks with filters and cursor pagination.
     *
     * @param contextId optional context filter
     * @param status optional state filter
     * @param pageSize page size from 1 through 100
     * @param pageToken optional opaque cursor
     * @param historyLength non-negative history length
     * @param statusTimestampAfter optional lower timestamp bound
     * @param includeArtifacts whether artifacts are requested
     * @param tenant optional tenant
     */
    public record ListTasks(
            String contextId,
            TaskState status,
            int pageSize,
            String pageToken,
            int historyLength,
            Instant statusTimestampAfter,
            boolean includeArtifacts,
            String tenant) {
        /** Creates a validated list request. */
        public ListTasks {
            contextId = A2AValidation.optionalNonBlank(contextId, "contextId");
            if (pageSize < 1 || pageSize > 100) {
                throw new com.microsoft.agents.core.ValidationException("pageSize must be between 1 and 100.");
            }
            pageToken = A2AValidation.optionalNonBlank(pageToken, "pageToken");
            A2AValidation.nonNegative(historyLength, "historyLength");
            tenant = A2AValidation.optionalNonBlank(tenant, "tenant");
        }

        /** Creates an unfiltered first-page request. */
        public ListTasks() {
            this(null, null, 50, null, 0, null, false, null);
        }

        /**
         * Returns a copy for the next page.
         *
         * @param token next-page token
         * @return next request
         */
        public ListTasks next(String token) {
            return new ListTasks(
                    contextId, status, pageSize, token, historyLength, statusTimestampAfter, includeArtifacts, tenant);
        }
    }

    /**
     * Requests task cancellation.
     *
     * @param taskId task identifier
     * @param metadata request metadata
     * @param tenant optional tenant
     */
    public record CancelTask(String taskId, Map<String, StateValue> metadata, String tenant) {
        /** Creates a validated cancellation request. */
        public CancelTask {
            taskId = A2AValidation.nonBlank(taskId, "taskId");
            metadata = A2AValidation.metadata(metadata, "metadata");
            tenant = A2AValidation.optionalNonBlank(tenant, "tenant");
        }

        /** Creates a cancellation request without metadata. */
        public CancelTask(String taskId) {
            this(taskId, Map.of(), null);
        }
    }

    /**
     * Requests a task event subscription.
     *
     * @param taskId task identifier
     * @param tenant optional tenant
     */
    public record SubscribeToTask(String taskId, String tenant) {
        /** Creates a validated subscription request. */
        public SubscribeToTask {
            taskId = A2AValidation.nonBlank(taskId, "taskId");
            tenant = A2AValidation.optionalNonBlank(tenant, "tenant");
        }

        /** Creates a subscription request without a tenant. */
        public SubscribeToTask(String taskId) {
            this(taskId, null);
        }
    }

    /**
     * Requests one push configuration.
     *
     * @param taskId task identifier
     * @param configId configuration identifier
     * @param tenant optional tenant
     */
    public record GetPushConfig(String taskId, String configId, String tenant) {
        /** Creates a validated request. */
        public GetPushConfig {
            taskId = A2AValidation.nonBlank(taskId, "taskId");
            configId = A2AValidation.nonBlank(configId, "configId");
            tenant = A2AValidation.optionalNonBlank(tenant, "tenant");
        }
    }

    /**
     * Lists push configurations.
     *
     * @param taskId task identifier
     * @param pageSize page size from 1 through 100
     * @param pageToken optional cursor
     * @param tenant optional tenant
     */
    public record ListPushConfigs(String taskId, int pageSize, String pageToken, String tenant) {
        /** Creates a validated request. */
        public ListPushConfigs {
            taskId = A2AValidation.nonBlank(taskId, "taskId");
            if (pageSize < 1 || pageSize > 100) {
                throw new com.microsoft.agents.core.ValidationException("pageSize must be between 1 and 100.");
            }
            pageToken = A2AValidation.optionalNonBlank(pageToken, "pageToken");
            tenant = A2AValidation.optionalNonBlank(tenant, "tenant");
        }

        /** Creates a first-page request. */
        public ListPushConfigs(String taskId) {
            this(taskId, 50, null, null);
        }
    }

    /**
     * Deletes one push configuration.
     *
     * @param taskId task identifier
     * @param configId configuration identifier
     * @param tenant optional tenant
     */
    public record DeletePushConfig(String taskId, String configId, String tenant) {
        /** Creates a validated request. */
        public DeletePushConfig {
            taskId = A2AValidation.nonBlank(taskId, "taskId");
            configId = A2AValidation.nonBlank(configId, "configId");
            tenant = A2AValidation.optionalNonBlank(tenant, "tenant");
        }
    }

    /**
     * Requests the authenticated extended card.
     *
     * @param tenant optional tenant
     */
    public record GetExtendedAgentCard(String tenant) {
        /** Creates a validated request. */
        public GetExtendedAgentCard {
            tenant = A2AValidation.optionalNonBlank(tenant, "tenant");
        }

        /** Creates a request without a tenant. */
        public GetExtendedAgentCard() {
            this(null);
        }
    }
}
