// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.agui;

import com.microsoft.agents.hosting.HostingContinuationType;
import com.microsoft.agents.hosting.HostingRouteKind;
import com.microsoft.agents.protocols.agui.AGUIInterrupt;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Binds official AG-UI interrupt identifiers to one generic process-local hosting continuation.
 *
 * @param clientRunId interrupted AG-UI run identifier
 * @param hostRunId owning generic hosting run identifier
 * @param routeKind generic route kind
 * @param token opaque generic continuation token
 * @param type generic continuation type
 * @param interrupts complete official interrupts
 * @param approvalIdsByInterruptId approval mapping, empty for input continuations
 * @param expiresAt expiry instant
 */
public record AGUIPendingContinuation(
        String clientRunId,
        String hostRunId,
        HostingRouteKind routeKind,
        String token,
        HostingContinuationType type,
        List<AGUIInterrupt> interrupts,
        Map<String, String> approvalIdsByInterruptId,
        Instant expiresAt) {
    /** Creates a validated immutable pending continuation. */
    public AGUIPendingContinuation {
        clientRunId = require(clientRunId, "clientRunId");
        hostRunId = require(hostRunId, "hostRunId");
        java.util.Objects.requireNonNull(routeKind, "routeKind");
        token = require(token, "token");
        java.util.Objects.requireNonNull(type, "type");
        interrupts = List.copyOf(java.util.Objects.requireNonNull(interrupts, "interrupts"));
        approvalIdsByInterruptId =
                Map.copyOf(java.util.Objects.requireNonNull(approvalIdsByInterruptId, "approvalIdsByInterruptId"));
        java.util.Objects.requireNonNull(expiresAt, "expiresAt");
        if (interrupts.isEmpty()) {
            throw new IllegalArgumentException("interrupts must not be empty.");
        }
        java.util.Set<String> ids =
                interrupts.stream().map(AGUIInterrupt::id).collect(java.util.stream.Collectors.toSet());
        if (ids.size() != interrupts.size() || !ids.containsAll(approvalIdsByInterruptId.keySet())) {
            throw new IllegalArgumentException("Interrupt and approval mappings are inconsistent.");
        }
    }

    private static String require(String value, String name) {
        java.util.Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value;
    }
}
