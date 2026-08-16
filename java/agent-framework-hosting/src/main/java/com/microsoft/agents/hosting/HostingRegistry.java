// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting;

import com.microsoft.agents.agents.Agent;
import com.microsoft.agents.agents.ChatAgent;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunHandle;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.orchestrations.Orchestration;
import com.microsoft.agents.orchestrations.OrchestrationContinuation;
import com.microsoft.agents.orchestrations.OrchestrationEvent;
import com.microsoft.agents.orchestrations.OrchestrationResult;
import com.microsoft.agents.orchestrations.OrchestrationRunOptions;
import com.microsoft.agents.workflows.Workflow;
import com.microsoft.agents.workflows.WorkflowEvent;
import com.microsoft.agents.workflows.WorkflowRunOptions;
import com.microsoft.agents.workflows.WorkflowRunResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

/**
 * Registers hosted agents and workflows under deterministic route identifiers.
 *
 * <p>Registration is thread-safe. Duplicate kind/id pairs fail rather than replace an existing
 * target.
 */
public final class HostingRegistry {
    private final Object lock = new Object();

    private final Map<RouteKey, Registration> registrations = new LinkedHashMap<>();

    /**
     * Registers an agent using its stable framework identifier as the route identifier.
     *
     * @param agent agent target
     * @return descriptor
     */
    public HostingRouteDescriptor registerAgent(Agent<?> agent) {
        java.util.Objects.requireNonNull(agent, "agent");
        return registerAgent(agent.id(), agent);
    }

    /**
     * Registers an agent.
     *
     * @param routeId deterministic route identifier
     * @param agent agent target
     * @return descriptor
     */
    public HostingRouteDescriptor registerAgent(String routeId, Agent<?> agent) {
        return registerAgent(routeId, agent, true, agent instanceof ChatAgent, Map.of());
    }

    /**
     * Registers an agent with explicit transport capabilities and public route metadata.
     *
     * @param routeId deterministic route identifier
     * @param agent agent target
     * @param streamingSupported whether the route exposes streaming
     * @param resumeSupported whether the route exposes a production continuation
     * @param metadata immutable public route metadata
     * @return descriptor
     */
    public HostingRouteDescriptor registerAgent(
            String routeId,
            Agent<?> agent,
            boolean streamingSupported,
            boolean resumeSupported,
            Map<String, StateValue> metadata) {
        java.util.Objects.requireNonNull(agent, "agent");
        String id = HostingValidation.routeId(routeId);
        HostingRouteDescriptor descriptor = new HostingRouteDescriptor(
                id,
                HostingRouteKind.AGENT,
                agent.name(),
                agent.description(),
                streamingSupported,
                resumeSupported,
                metadata);
        put(new AgentRegistration(descriptor, agent));
        return descriptor;
    }

    /**
     * Registers a workflow with an explicit hosted value codec.
     *
     * @param routeId deterministic route identifier
     * @param workflow workflow target
     * @param codec input/output codec
     * @param <I> workflow input type
     * @param <O> workflow output type
     * @return descriptor
     */
    public <I, O> HostingRouteDescriptor registerWorkflow(
            String routeId, Workflow<I, O> workflow, HostingWorkflowCodec<I, O> codec) {
        java.util.Objects.requireNonNull(workflow, "workflow");
        java.util.Objects.requireNonNull(codec, "codec");
        String id = HostingValidation.routeId(routeId);
        HostingRouteDescriptor descriptor = new HostingRouteDescriptor(
                id,
                HostingRouteKind.WORKFLOW,
                workflow.id(),
                null,
                true,
                false,
                Map.of("schemaVersion", com.microsoft.agents.core.StateValue.integer(workflow.schemaVersion())));
        put(new WorkflowRegistration<>(descriptor, workflow, codec));
        return descriptor;
    }

    /**
     * Registers a workflow using its stable workflow identifier as the route identifier.
     *
     * @param workflow workflow target
     * @param codec input/output codec
     * @param <I> workflow input type
     * @param <O> workflow output type
     * @return descriptor
     */
    public <I, O> HostingRouteDescriptor registerWorkflow(Workflow<I, O> workflow, HostingWorkflowCodec<I, O> codec) {
        java.util.Objects.requireNonNull(workflow, "workflow");
        return registerWorkflow(workflow.id(), workflow, codec);
    }

    /**
     * Registers an orchestration with an explicit hosted output and resume codec.
     *
     * @param routeId deterministic route identifier
     * @param orchestration orchestration target
     * @param codec output and resume codec
     * @param <O> orchestration output type
     * @return descriptor
     */
    public <O> HostingRouteDescriptor registerOrchestration(
            String routeId, Orchestration<O> orchestration, HostingOrchestrationCodec<O> codec) {
        java.util.Objects.requireNonNull(orchestration, "orchestration");
        java.util.Objects.requireNonNull(codec, "codec");
        String id = HostingValidation.routeId(routeId);
        HostingRouteDescriptor descriptor = new HostingRouteDescriptor(
                id,
                HostingRouteKind.ORCHESTRATION,
                orchestration.id(),
                null,
                true,
                true,
                Map.of(
                        "pattern",
                        com.microsoft.agents.core.StateValue.string(
                                orchestration.pattern().name())));
        put(new OrchestrationRegistration<>(descriptor, orchestration, codec));
        return descriptor;
    }

    /**
     * Registers an orchestration using its stable identifier.
     *
     * @param orchestration orchestration target
     * @param codec output and resume codec
     * @param <O> orchestration output type
     * @return descriptor
     */
    public <O> HostingRouteDescriptor registerOrchestration(
            Orchestration<O> orchestration, HostingOrchestrationCodec<O> codec) {
        java.util.Objects.requireNonNull(orchestration, "orchestration");
        return registerOrchestration(orchestration.id(), orchestration, codec);
    }

    /**
     * Lists agent descriptors in lexical identifier order.
     *
     * @return immutable descriptors
     */
    public List<HostingRouteDescriptor> agents() {
        return descriptors(HostingRouteKind.AGENT);
    }

    /**
     * Lists workflow descriptors in lexical identifier order.
     *
     * @return immutable descriptors
     */
    public List<HostingRouteDescriptor> workflows() {
        return descriptors(HostingRouteKind.WORKFLOW);
    }

    /**
     * Lists orchestration descriptors in lexical identifier order.
     *
     * @return immutable descriptors
     */
    public List<HostingRouteDescriptor> orchestrations() {
        return descriptors(HostingRouteKind.ORCHESTRATION);
    }

    /**
     * Looks up a route descriptor.
     *
     * @param kind route kind
     * @param routeId route identifier
     * @return optional descriptor
     */
    public Optional<HostingRouteDescriptor> find(HostingRouteKind kind, String routeId) {
        RouteKey key = new RouteKey(kind, HostingValidation.routeId(routeId));
        synchronized (lock) {
            Registration registration = registrations.get(key);
            return registration == null ? Optional.empty() : Optional.of(registration.descriptor());
        }
    }

    Registration requireRegistration(HostingRouteKind kind, String routeId) {
        RouteKey key = new RouteKey(kind, HostingValidation.routeId(routeId));
        synchronized (lock) {
            Registration registration = registrations.get(key);
            if (registration == null) {
                throw new HostingException(HostingErrorCode.NOT_FOUND, "Hosted route was not found.");
            }
            return registration;
        }
    }

    private void put(Registration registration) {
        RouteKey key = new RouteKey(
                registration.descriptor().kind(), registration.descriptor().id());
        synchronized (lock) {
            if (registrations.putIfAbsent(key, registration) != null) {
                throw new HostingException(
                        HostingErrorCode.CONFLICT,
                        "Hosted route '" + key.kind().pathSegment() + "/" + key.routeId() + "' is already registered.");
            }
        }
    }

    private List<HostingRouteDescriptor> descriptors(HostingRouteKind kind) {
        synchronized (lock) {
            ArrayList<HostingRouteDescriptor> result = new ArrayList<>();
            registrations.forEach((key, value) -> {
                if (key.kind() == kind) {
                    result.add(value.descriptor());
                }
            });
            result.sort(Comparator.comparing(HostingRouteDescriptor::id));
            return List.copyOf(result);
        }
    }

    sealed interface Registration permits AgentRegistration, WorkflowRegistration, OrchestrationRegistration {
        HostingRouteDescriptor descriptor();
    }

    record AgentRegistration(HostingRouteDescriptor descriptor, Agent<?> agent) implements Registration {
        AgentRegistration {
            java.util.Objects.requireNonNull(descriptor, "descriptor");
            java.util.Objects.requireNonNull(agent, "agent");
        }
    }

    static final class WorkflowRegistration<I, O> implements Registration {
        private final HostingRouteDescriptor descriptor;

        private final Workflow<I, O> workflow;

        private final HostingWorkflowCodec<I, O> codec;

        WorkflowRegistration(
                HostingRouteDescriptor descriptor, Workflow<I, O> workflow, HostingWorkflowCodec<I, O> codec) {
            this.descriptor = java.util.Objects.requireNonNull(descriptor, "descriptor");
            this.workflow = java.util.Objects.requireNonNull(workflow, "workflow");
            this.codec = java.util.Objects.requireNonNull(codec, "codec");
        }

        @Override
        public HostingRouteDescriptor descriptor() {
            return descriptor;
        }

        RunHandle<WorkflowRunResult<O>> start(HostingRunRequest request, String runId, RunCancellation cancellation) {
            I input = codec.decodeInput(request);
            WorkflowRunOptions options = workflowOptions(request, runId);
            return workflow.startRun(input, options, cancellation);
        }

        Flow.Publisher<WorkflowEvent> stream(HostingRunRequest request, String runId, RunCancellation cancellation) {
            I input = codec.decodeInput(request);
            WorkflowRunOptions options = workflowOptions(request, runId);
            return workflow.runStreaming(input, options, cancellation);
        }

        com.microsoft.agents.core.StateValue encodeOutput(O output) {
            return HostingRedactor.redact(codec.encodeOutput(output));
        }

        private WorkflowRunOptions workflowOptions(HostingRunRequest request, String runId) {
            WorkflowRunOptions.Builder builder =
                    WorkflowRunOptions.builder().runId(runId).metadata(request.metadata());
            if (request.options().maxIterations() != null) {
                builder.maxSupersteps(request.options().maxIterations());
            }
            return codec.configureOptions(request, builder).build();
        }
    }

    static final class OrchestrationRegistration<O> implements Registration {
        private final HostingRouteDescriptor descriptor;

        private final Orchestration<O> orchestration;

        private final HostingOrchestrationCodec<O> codec;

        OrchestrationRegistration(
                HostingRouteDescriptor descriptor, Orchestration<O> orchestration, HostingOrchestrationCodec<O> codec) {
            this.descriptor = java.util.Objects.requireNonNull(descriptor, "descriptor");
            this.orchestration = java.util.Objects.requireNonNull(orchestration, "orchestration");
            this.codec = java.util.Objects.requireNonNull(codec, "codec");
        }

        @Override
        public HostingRouteDescriptor descriptor() {
            return descriptor;
        }

        RunHandle<OrchestrationResult<O>> start(HostingRunRequest request, String runId, RunCancellation cancellation) {
            return start(request, runId, cancellation, ignored -> {});
        }

        RunHandle<OrchestrationResult<O>> start(
                HostingRunRequest request,
                String runId,
                RunCancellation cancellation,
                Consumer<OrchestrationEvent> eventListener) {
            return orchestration.startRun(request.messages(), options(request, runId, eventListener), cancellation);
        }

        RunHandle<OrchestrationResult<O>> resume(
                OrchestrationContinuation continuation,
                HostingResumeRequest request,
                String runId,
                RunCancellation cancellation) {
            return orchestration.startResume(
                    continuation,
                    codec.decodeResumeInput(continuation, request),
                    options(
                            new HostingRunRequest(
                                    continuation.transcript(),
                                    request.input(),
                                    com.microsoft.agents.core.RunOptions.empty(),
                                    Map.of()),
                            runId,
                            ignored -> {}),
                    cancellation);
        }

        StateValue encodeOutput(O output) {
            return HostingRedactor.redact(codec.encodeOutput(output));
        }

        private static OrchestrationRunOptions options(
                HostingRunRequest request, String runId, Consumer<OrchestrationEvent> eventListener) {
            return OrchestrationRunOptions.builder()
                    .runId(runId)
                    .agentRunOptions(request.options())
                    .metadata(request.metadata())
                    .eventListener(eventListener::accept)
                    .build();
        }
    }

    private record RouteKey(HostingRouteKind kind, String routeId) {
        private RouteKey {
            java.util.Objects.requireNonNull(kind, "kind");
            HostingValidation.routeId(routeId);
        }
    }
}
