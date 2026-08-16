// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunHandle;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Defines the framework-owned A2A v1 client contract.
 *
 * <p>Finite operations expose an explicit {@link RunHandle}; convenience asynchronous methods return
 * only the terminal stage. Streaming publishers are cold, single-subscriber, demand-aware, bounded,
 * and close their HTTP stream when canceled.
 */
public interface A2AClient extends AutoCloseable {
    /**
     * Creates the secure JDK HTTP JSON-RPC implementation.
     *
     * @param options client options
     * @return client
     */
    static A2AClient create(A2AClientOptions options) {
        return new JdkA2AClient(options);
    }

    /** Starts public agent-card discovery. */
    RunHandle<AgentCard> startFetchAgentCard();

    /** Fetches the public agent card asynchronously. */
    default CompletionStage<AgentCard> fetchAgentCardAsync() {
        return startFetchAgentCard().resultAsync();
    }

    /** Starts authenticated extended-card retrieval. */
    RunHandle<AgentCard> startFetchExtendedAgentCard(A2ARequests.GetExtendedAgentCard request);

    /** Fetches the authenticated extended card asynchronously. */
    default CompletionStage<AgentCard> fetchExtendedAgentCardAsync(A2ARequests.GetExtendedAgentCard request) {
        return startFetchExtendedAgentCard(request).resultAsync();
    }

    /** Starts a finite message send. */
    RunHandle<SendMessageResult> startSendMessage(SendMessageRequest request);

    /** Sends a message asynchronously. */
    default CompletionStage<SendMessageResult> sendMessageAsync(SendMessageRequest request) {
        return startSendMessage(request).resultAsync();
    }

    /** Streams one message send with framework-owned cancellation. */
    default Flow.Publisher<A2AStreamEvent> sendMessageStreaming(SendMessageRequest request) {
        return sendMessageStreaming(request, new DefaultRunCancellation());
    }

    /** Streams one message send with caller-owned cancellation. */
    Flow.Publisher<A2AStreamEvent> sendMessageStreaming(SendMessageRequest request, RunCancellation cancellation);

    /** Starts task retrieval. */
    RunHandle<Task> startGetTask(A2ARequests.GetTask request);

    /** Gets one task asynchronously. */
    default CompletionStage<Task> getTaskAsync(A2ARequests.GetTask request) {
        return startGetTask(request).resultAsync();
    }

    /** Starts one list-tasks page request. */
    RunHandle<A2ACursorPage<Task>> startListTasks(A2ARequests.ListTasks request);

    /** Lists one task page asynchronously. */
    default CompletionStage<A2ACursorPage<Task>> listTasksAsync(A2ARequests.ListTasks request) {
        return startListTasks(request).resultAsync();
    }

    /**
     * Lists every task page while rejecting cursor loops and configured collection overflow.
     *
     * @param request initial request
     * @return all tasks
     */
    CompletionStage<List<Task>> listAllTasksAsync(A2ARequests.ListTasks request);

    /** Starts task cancellation. */
    RunHandle<Task> startCancelTask(A2ARequests.CancelTask request);

    /** Cancels one task asynchronously. */
    default CompletionStage<Task> cancelTaskAsync(A2ARequests.CancelTask request) {
        return startCancelTask(request).resultAsync();
    }

    /** Subscribes to a task with framework-owned cancellation. */
    default Flow.Publisher<A2AStreamEvent> subscribeToTaskStreaming(A2ARequests.SubscribeToTask request) {
        return subscribeToTaskStreaming(request, new DefaultRunCancellation());
    }

    /** Subscribes to a task with caller-owned cancellation. */
    Flow.Publisher<A2AStreamEvent> subscribeToTaskStreaming(
            A2ARequests.SubscribeToTask request, RunCancellation cancellation);

    /** Starts push-configuration creation. */
    RunHandle<PushNotificationConfig> startCreatePushNotificationConfig(PushNotificationConfig config);

    /** Creates push configuration asynchronously. */
    default CompletionStage<PushNotificationConfig> createPushNotificationConfigAsync(PushNotificationConfig config) {
        return startCreatePushNotificationConfig(config).resultAsync();
    }

    /** Starts push-configuration retrieval. */
    RunHandle<PushNotificationConfig> startGetPushNotificationConfig(A2ARequests.GetPushConfig request);

    /** Gets push configuration asynchronously. */
    default CompletionStage<PushNotificationConfig> getPushNotificationConfigAsync(A2ARequests.GetPushConfig request) {
        return startGetPushNotificationConfig(request).resultAsync();
    }

    /** Starts one push-configuration page request. */
    RunHandle<A2ACursorPage<PushNotificationConfig>> startListPushNotificationConfigs(
            A2ARequests.ListPushConfigs request);

    /** Lists one push-configuration page asynchronously. */
    default CompletionStage<A2ACursorPage<PushNotificationConfig>> listPushNotificationConfigsAsync(
            A2ARequests.ListPushConfigs request) {
        return startListPushNotificationConfigs(request).resultAsync();
    }

    /** Lists all push configurations while rejecting cursor loops. */
    CompletionStage<List<PushNotificationConfig>> listAllPushNotificationConfigsAsync(
            A2ARequests.ListPushConfigs request);

    /** Starts idempotent push-configuration deletion. */
    RunHandle<Boolean> startDeletePushNotificationConfig(A2ARequests.DeletePushConfig request);

    /** Deletes push configuration asynchronously. */
    default CompletionStage<Boolean> deletePushNotificationConfigAsync(A2ARequests.DeletePushConfig request) {
        return startDeletePushNotificationConfig(request).resultAsync();
    }

    /** Releases client-owned HTTP resources and cancels active operations. */
    @Override
    void close();
}
