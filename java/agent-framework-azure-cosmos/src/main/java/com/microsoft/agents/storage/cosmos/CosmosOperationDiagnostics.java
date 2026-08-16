// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

import java.time.Duration;

/**
 * Contains sanitized Cosmos diagnostics without document or credential content.
 *
 * @param statusCode HTTP status, or {@code null}
 * @param activityId service request identifier, or {@code null}
 * @param requestCharge request-unit charge, or {@code null}
 * @param retryAfter service retry delay, or {@code null}
 */
public record CosmosOperationDiagnostics(
        Integer statusCode, String activityId, Double requestCharge, Duration retryAfter)
        implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
}
