// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

/**
 * Defines stable A2A protocol v1 constants used by the framework transports.
 */
public final class A2AProtocol {
    /** Protocol major/minor identifier carried on the wire. */
    public static final String VERSION = "1.0";

    /** JSON-RPC version required by the JSON-RPC binding. */
    public static final String JSON_RPC_VERSION = "2.0";

    /** Standard public agent-card path. */
    public static final String AGENT_CARD_PATH = "/.well-known/agent-card.json";

    /** JSON-RPC send method. */
    public static final String SEND_MESSAGE = "SendMessage";

    /** JSON-RPC streaming-send method. */
    public static final String SEND_STREAMING_MESSAGE = "SendStreamingMessage";

    /** JSON-RPC get-task method. */
    public static final String GET_TASK = "GetTask";

    /** JSON-RPC list-tasks method. */
    public static final String LIST_TASKS = "ListTasks";

    /** JSON-RPC cancel-task method. */
    public static final String CANCEL_TASK = "CancelTask";

    /** JSON-RPC subscribe method. */
    public static final String SUBSCRIBE_TO_TASK = "SubscribeToTask";

    /** JSON-RPC push-configuration create method. */
    public static final String CREATE_PUSH_CONFIG = "CreateTaskPushNotificationConfig";

    /** JSON-RPC push-configuration get method. */
    public static final String GET_PUSH_CONFIG = "GetTaskPushNotificationConfig";

    /** JSON-RPC push-configuration list method. */
    public static final String LIST_PUSH_CONFIGS = "ListTaskPushNotificationConfigs";

    /** JSON-RPC push-configuration delete method. */
    public static final String DELETE_PUSH_CONFIG = "DeleteTaskPushNotificationConfig";

    /** JSON-RPC authenticated extended-card method. */
    public static final String GET_EXTENDED_AGENT_CARD = "GetExtendedAgentCard";

    private A2AProtocol() {}
}
