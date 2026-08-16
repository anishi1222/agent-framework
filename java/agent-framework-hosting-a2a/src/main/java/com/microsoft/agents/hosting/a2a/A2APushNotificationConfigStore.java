// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.a2a;

import com.microsoft.agents.protocols.a2a.A2ACursorPage;
import com.microsoft.agents.protocols.a2a.A2ARequests;
import com.microsoft.agents.protocols.a2a.PushNotificationConfig;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Stores push configuration without performing outbound webhook delivery.
 */
public interface A2APushNotificationConfigStore {
    /** Creates or replaces one configuration owned by a visible task. */
    CompletionStage<PushNotificationConfig> putAsync(A2APrincipal principal, PushNotificationConfig config);

    /** Loads one visible configuration. */
    CompletionStage<Optional<PushNotificationConfig>> getAsync(
            A2APrincipal principal, A2ARequests.GetPushConfig request);

    /** Lists visible configurations for one task. */
    CompletionStage<A2ACursorPage<PushNotificationConfig>> listAsync(
            A2APrincipal principal, A2ARequests.ListPushConfigs request);

    /** Deletes one configuration idempotently. */
    CompletionStage<Boolean> deleteAsync(A2APrincipal principal, A2ARequests.DeletePushConfig request);
}
