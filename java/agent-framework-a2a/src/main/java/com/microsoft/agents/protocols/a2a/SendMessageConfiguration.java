// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import java.util.List;

/**
 * Configures finite or streaming message delivery.
 *
 * @param acceptedOutputModes accepted output media types
 * @param historyLength optional non-negative history length
 * @param taskPushNotificationConfig optional stored push configuration
 * @param returnImmediately whether the server may return before terminal state
 */
public record SendMessageConfiguration(
        List<String> acceptedOutputModes,
        Integer historyLength,
        PushNotificationConfig taskPushNotificationConfig,
        boolean returnImmediately) {
    /** Creates immutable validated configuration. */
    public SendMessageConfiguration {
        acceptedOutputModes = A2AValidation.strings(acceptedOutputModes, "acceptedOutputModes", true);
        if (historyLength != null) {
            A2AValidation.nonNegative(historyLength, "historyLength");
        }
        if (taskPushNotificationConfig != null && taskPushNotificationConfig.taskId() != null) {
            throw new com.microsoft.agents.core.ValidationException(
                    "Inline taskPushNotificationConfig must not contain taskId.");
        }
    }

    /**
     * Returns default configuration.
     *
     * @return defaults
     */
    public static SendMessageConfiguration defaults() {
        return new SendMessageConfiguration(List.of(), null, null, false);
    }
}
