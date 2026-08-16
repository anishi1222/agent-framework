// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import com.microsoft.agents.core.StateValue;

/**
 * Represents one explicit hosted execution outcome.
 *
 * @param status terminal status
 * @param runId host-generated active-run identifier
 * @param result optional successful result
 * @param continuation optional process-local continuation
 * @param error optional sanitized failure
 */
public record HostingOutcome(
        HostingOutcomeStatus status,
        String runId,
        StateValue result,
        HostingContinuationDescriptor continuation,
        HostingError error) {
    /** Creates a structurally valid outcome. */
    public HostingOutcome {
        java.util.Objects.requireNonNull(status, "status");
        runId = HostingValidation.nonBlank(runId, "runId");
        boolean completed = status == HostingOutcomeStatus.COMPLETED;
        boolean suspended =
                status == HostingOutcomeStatus.INPUT_REQUIRED || status == HostingOutcomeStatus.APPROVAL_REQUIRED;
        boolean failed = status == HostingOutcomeStatus.FAILED
                || status == HostingOutcomeStatus.CANCELLED
                || status == HostingOutcomeStatus.OVERFLOW;
        if (completed != (result != null)
                || suspended != (continuation != null)
                || failed != (error != null)
                || (completed && (continuation != null || error != null))
                || (suspended && (result != null || error != null))
                || (failed && (result != null || continuation != null))) {
            throw new com.microsoft.agents.core.ValidationException(
                    "Outcome payload does not match its terminal status.");
        }
        if (status == HostingOutcomeStatus.APPROVAL_REQUIRED
                && continuation.type() != HostingContinuationType.APPROVAL) {
            throw new com.microsoft.agents.core.ValidationException(
                    "Approval-required outcome must carry an approval continuation.");
        }
    }

    /**
     * Creates a successful outcome.
     *
     * @param runId run identifier
     * @param result successful result
     * @return completed outcome
     */
    public static HostingOutcome completed(String runId, StateValue result) {
        return new HostingOutcome(
                HostingOutcomeStatus.COMPLETED, runId, java.util.Objects.requireNonNull(result, "result"), null, null);
    }

    /**
     * Creates an approval-required outcome.
     *
     * @param runId run identifier
     * @param continuation approval continuation
     * @return suspended outcome
     */
    public static HostingOutcome approvalRequired(String runId, HostingContinuationDescriptor continuation) {
        return new HostingOutcome(
                HostingOutcomeStatus.APPROVAL_REQUIRED,
                runId,
                null,
                java.util.Objects.requireNonNull(continuation, "continuation"),
                null);
    }

    /**
     * Creates a failed outcome.
     *
     * @param runId run identifier
     * @param error sanitized error
     * @return failed outcome
     */
    public static HostingOutcome failed(String runId, HostingError error) {
        return new HostingOutcome(
                HostingOutcomeStatus.FAILED, runId, null, null, java.util.Objects.requireNonNull(error, "error"));
    }

    /**
     * Creates a cancelled outcome.
     *
     * @param runId run identifier
     * @param error cancellation error
     * @return cancelled outcome
     */
    public static HostingOutcome cancelled(String runId, HostingError error) {
        return new HostingOutcome(
                HostingOutcomeStatus.CANCELLED, runId, null, null, java.util.Objects.requireNonNull(error, "error"));
    }

    /**
     * Creates an overflow outcome.
     *
     * @param runId run identifier
     * @param error overflow error
     * @return overflow outcome
     */
    public static HostingOutcome overflow(String runId, HostingError error) {
        return new HostingOutcome(
                HostingOutcomeStatus.OVERFLOW, runId, null, null, java.util.Objects.requireNonNull(error, "error"));
    }
}
