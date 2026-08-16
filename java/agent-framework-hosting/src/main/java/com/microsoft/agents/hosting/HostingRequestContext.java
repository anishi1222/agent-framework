// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.StateValue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Carries immutable trusted identity, correlation, metadata, and cancellation for one hosted call.
 *
 * <p>Only a transport after authentication and header validation should construct this type. Caller
 * supplied run, thread, response, or session identifiers are deliberately absent from authorization
 * identity.
 */
public final class HostingRequestContext {
    private final String requestId;

    private final String correlationId;

    private final HostingPrincipal principal;

    private final Map<String, List<String>> trustedHeaders;

    private final Map<String, StateValue> metadata;

    private final RunCancellation cancellation;

    /**
     * Creates one immutable context.
     *
     * @param requestId transport request identifier
     * @param correlationId end-to-end correlation identifier
     * @param principal authenticated principal and isolation
     * @param trustedHeaders allowlisted trusted headers
     * @param metadata immutable hosting metadata
     * @param cancellation caller-owned cancellation
     */
    public HostingRequestContext(
            String requestId,
            String correlationId,
            HostingPrincipal principal,
            Map<String, ? extends List<String>> trustedHeaders,
            Map<String, StateValue> metadata,
            RunCancellation cancellation) {
        this.requestId = HostingValidation.nonBlank(requestId, "requestId");
        this.correlationId = HostingValidation.nonBlank(correlationId, "correlationId");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.trustedHeaders = HostingValidation.copyHeaders(trustedHeaders);
        Objects.requireNonNull(metadata, "metadata");
        LinkedHashMap<String, StateValue> metadataCopy = new LinkedHashMap<>();
        metadata.forEach((key, value) -> metadataCopy.put(
                HostingValidation.nonBlank(key, "metadata key"), Objects.requireNonNull(value, "metadata value")));
        this.metadata = Map.copyOf(metadataCopy);
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
    }

    /**
     * Returns the request identifier.
     *
     * @return request identifier
     */
    public String requestId() {
        return requestId;
    }

    /**
     * Returns the correlation identifier.
     *
     * @return correlation identifier
     */
    public String correlationId() {
        return correlationId;
    }

    /**
     * Returns trusted principal details.
     *
     * @return principal
     */
    public HostingPrincipal principal() {
        return principal;
    }

    /**
     * Returns the authenticated principal identifier.
     *
     * @return principal identifier
     */
    public String principalId() {
        return principal.principalId();
    }

    /**
     * Returns the independently derived isolation identifier.
     *
     * @return isolation identifier
     */
    public String isolationId() {
        return principal.isolationId();
    }

    /**
     * Returns immutable trusted headers.
     *
     * @return trusted headers
     */
    public Map<String, List<String>> trustedHeaders() {
        return trustedHeaders;
    }

    /**
     * Returns immutable hosting metadata.
     *
     * @return metadata
     */
    public Map<String, StateValue> metadata() {
        return metadata;
    }

    /**
     * Returns the request cancellation signal.
     *
     * @return cancellation signal
     */
    public RunCancellation cancellation() {
        return cancellation;
    }
}
