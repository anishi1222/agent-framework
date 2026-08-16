// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.http;

import com.microsoft.agents.hosting.HostingOutcome;
import com.microsoft.agents.hosting.HostingRun;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Represents one finite JSON, empty, or SSE hosting response.
 */
public final class HostingHttpResponse {
    private final int status;

    private final Map<String, List<String>> headers;

    private final byte[] body;

    private final HostingRun streamingRun;

    private final OutcomeDelivery outcomeDelivery;

    private HostingHttpResponse(
            int status,
            Map<String, List<String>> headers,
            byte[] body,
            HostingRun streamingRun,
            OutcomeDelivery outcomeDelivery) {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("status must be a valid HTTP status.");
        }
        this.status = status;
        Objects.requireNonNull(headers, "headers");
        LinkedHashMap<String, List<String>> copy = new LinkedHashMap<>();
        headers.forEach((name, values) -> copy.put(
                Objects.requireNonNull(name, "header name"),
                List.copyOf(Objects.requireNonNull(values, "header values"))));
        this.headers = Map.copyOf(copy);
        this.body = Objects.requireNonNull(body, "body").clone();
        this.streamingRun = streamingRun;
        this.outcomeDelivery = Objects.requireNonNull(outcomeDelivery, "outcomeDelivery");
        if (streamingRun != null && body.length != 0) {
            throw new IllegalArgumentException("An SSE response must not include a finite body.");
        }
    }

    /**
     * Creates a finite response.
     *
     * @param status HTTP status
     * @param headers response headers
     * @param body body bytes
     * @return response
     */
    public static HostingHttpResponse finite(int status, Map<String, List<String>> headers, byte[] body) {
        return new HostingHttpResponse(status, headers, body, null, OutcomeDelivery.untracked());
    }

    static HostingHttpResponse finiteOutcome(
            int status,
            Map<String, List<String>> headers,
            byte[] body,
            HostingOutcome outcome,
            Consumer<HostingOutcome> discard) {
        OutcomeDelivery delivery = OutcomeDelivery.tracked(discard);
        delivery.track(Objects.requireNonNull(outcome, "outcome"));
        return new HostingHttpResponse(status, headers, body, null, delivery);
    }

    /**
     * Creates an SSE response.
     *
     * @param headers response headers
     * @param run streaming run
     * @return response
     */
    public static HostingHttpResponse sse(Map<String, List<String>> headers, HostingRun run) {
        return new HostingHttpResponse(
                200, headers, new byte[0], Objects.requireNonNull(run, "run"), OutcomeDelivery.untracked());
    }

    static HostingHttpResponse trackedSse(
            Map<String, List<String>> headers, HostingRun run, Consumer<HostingOutcome> discard) {
        HostingRun streamingRun = Objects.requireNonNull(run, "run");
        OutcomeDelivery delivery = OutcomeDelivery.tracked(discard);
        streamingRun.terminalAsync().whenComplete((outcome, failure) -> {
            if (outcome != null) {
                delivery.track(outcome);
            }
        });
        return new HostingHttpResponse(200, headers, new byte[0], streamingRun, delivery);
    }

    /**
     * Returns the HTTP status.
     *
     * @return status
     */
    public int status() {
        return status;
    }

    /**
     * Returns immutable response headers.
     *
     * @return headers
     */
    public Map<String, List<String>> headers() {
        return headers;
    }

    /**
     * Returns a defensive body copy.
     *
     * @return body bytes
     */
    public byte[] body() {
        return body.clone();
    }

    /**
     * Returns the SSE run, or {@code null} for a finite response.
     *
     * @return streaming run or {@code null}
     */
    public HostingRun streamingRun() {
        return streamingRun;
    }

    /**
     * Reports whether this is an SSE response.
     *
     * @return {@code true} for SSE
     */
    public boolean isStreaming() {
        return streamingRun != null;
    }

    /**
     * Confirms that the transport delivered this response completely.
     *
     * <p>Transport adapters call this only after the response body or terminal SSE frame has been
     * written successfully.
     */
    public void confirmDelivery() {
        outcomeDelivery.confirm();
    }

    /**
     * Discards any process-local continuation whose terminal outcome was not delivered.
     *
     * <p>The operation is idempotent and remains effective when an SSE outcome arrives after the
     * transport has already failed.
     */
    public void discardUndeliveredOutcome() {
        outcomeDelivery.discard();
    }

    HostingHttpResponse withHeaders(Map<String, List<String>> replacement) {
        return new HostingHttpResponse(status, replacement, body, streamingRun, outcomeDelivery);
    }

    private static final class OutcomeDelivery {
        private final Consumer<HostingOutcome> discard;

        private HostingOutcome pending;

        private Resolution resolution = Resolution.OPEN;

        private OutcomeDelivery(Consumer<HostingOutcome> discard) {
            this.discard = Objects.requireNonNull(discard, "discard");
        }

        private static OutcomeDelivery untracked() {
            return new OutcomeDelivery(ignored -> {});
        }

        private static OutcomeDelivery tracked(Consumer<HostingOutcome> discard) {
            return new OutcomeDelivery(discard);
        }

        private void track(HostingOutcome outcome) {
            Objects.requireNonNull(outcome, "outcome");
            if (outcome.continuation() == null) {
                return;
            }
            HostingOutcome toDiscard = null;
            synchronized (this) {
                if (resolution == Resolution.DELIVERED) {
                    return;
                }
                if (resolution == Resolution.UNDELIVERED) {
                    toDiscard = outcome;
                } else if (pending == null) {
                    pending = outcome;
                }
            }
            discard(toDiscard);
        }

        private synchronized void confirm() {
            if (resolution != Resolution.OPEN) {
                return;
            }
            resolution = Resolution.DELIVERED;
            pending = null;
        }

        private void discard() {
            HostingOutcome toDiscard;
            synchronized (this) {
                if (resolution == Resolution.DELIVERED) {
                    return;
                }
                resolution = Resolution.UNDELIVERED;
                toDiscard = pending;
                pending = null;
            }
            discard(toDiscard);
        }

        private void discard(HostingOutcome outcome) {
            if (outcome == null) {
                return;
            }
            try {
                discard.accept(outcome);
            } catch (RuntimeException ignored) {
                // Transport cleanup is best effort after delivery has already failed.
            }
        }
    }

    private enum Resolution {
        OPEN,
        DELIVERED,
        UNDELIVERED
    }
}
