// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.core.EncodedState;
import com.microsoft.agents.core.SerializationError;
import com.microsoft.agents.core.SerializationException;
import com.microsoft.agents.core.StateValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

final class FunctionalWorkflowSnapshot {
    static final String STATE_KEY = "_functional.runtime";

    private static final String TYPE_ID = "com.microsoft.agents.workflows.functional-runtime";

    private static final int VERSION = 1;

    private static final Set<String> ROOT_FIELDS = Set.of(
            "format",
            "originalInput",
            "stepCache",
            "userState",
            "pendingRequests",
            "resolvedResponses",
            "completedLiveSteps",
            "checkpointOrdinal");

    private final EncodedState originalInput;

    private final Map<FunctionalStepInvocation, FunctionalCachedStep> stepCache;

    private final Map<String, EncodedState> userState;

    private final Map<String, FunctionalInputRequest> pendingRequests;

    private final Map<String, EncodedState> resolvedResponses;

    private final int completedLiveSteps;

    private final int checkpointOrdinal;

    FunctionalWorkflowSnapshot(
            EncodedState originalInput,
            Map<FunctionalStepInvocation, FunctionalCachedStep> stepCache,
            Map<String, EncodedState> userState,
            Map<String, FunctionalInputRequest> pendingRequests,
            Map<String, EncodedState> resolvedResponses,
            int completedLiveSteps,
            int checkpointOrdinal) {
        this.originalInput = Objects.requireNonNull(originalInput, "originalInput");
        TreeMap<FunctionalStepInvocation, FunctionalCachedStep> sortedCache = new TreeMap<>();
        stepCache.forEach((key, value) ->
                sortedCache.put(Objects.requireNonNull(key, "step cache key"), Objects.requireNonNull(value, "step")));
        this.stepCache = Collections.unmodifiableMap(new LinkedHashMap<>(sortedCache));
        TreeMap<String, EncodedState> sortedState = new TreeMap<>();
        userState.forEach((key, value) ->
                sortedState.put(validateUserStateKey(key), Objects.requireNonNull(value, "user state value")));
        this.userState = Collections.unmodifiableMap(new LinkedHashMap<>(sortedState));
        TreeMap<String, FunctionalInputRequest> sortedRequests = new TreeMap<>();
        pendingRequests.forEach((key, value) -> {
            FunctionalInputRequest request = Objects.requireNonNull(value, "pending request");
            if (!request.requestId().equals(key)) {
                throw new WorkflowValidationException("Pending request map key '" + key + "' does not match requestId '"
                        + request.requestId() + "'.");
            }
            sortedRequests.put(key, request);
        });
        this.pendingRequests = Collections.unmodifiableMap(new LinkedHashMap<>(sortedRequests));
        TreeMap<String, EncodedState> sortedResponses = new TreeMap<>();
        resolvedResponses.forEach((key, value) -> sortedResponses.put(
                WorkflowValidation.requireNonBlank(key, "resolved response id"),
                Objects.requireNonNull(value, "resolved response")));
        this.resolvedResponses = Collections.unmodifiableMap(new LinkedHashMap<>(sortedResponses));
        if (completedLiveSteps < 0) {
            throw new WorkflowValidationException("completedLiveSteps must not be negative.");
        }
        this.completedLiveSteps = completedLiveSteps;
        if (checkpointOrdinal < 0) {
            throw new WorkflowValidationException("checkpointOrdinal must not be negative.");
        }
        this.checkpointOrdinal = checkpointOrdinal;
    }

    EncodedState originalInput() {
        return originalInput;
    }

    Map<FunctionalStepInvocation, FunctionalCachedStep> stepCache() {
        return stepCache;
    }

    Map<String, EncodedState> userState() {
        return userState;
    }

    Map<String, FunctionalInputRequest> pendingRequests() {
        return pendingRequests;
    }

    Map<String, EncodedState> resolvedResponses() {
        return resolvedResponses;
    }

    int completedLiveSteps() {
        return completedLiveSteps;
    }

    int checkpointOrdinal() {
        return checkpointOrdinal;
    }

    WorkflowState toWorkflowState() {
        EncodedState encoded = new EncodedState(TYPE_ID, VERSION, toStateValue());
        return new WorkflowState(Map.of(STATE_KEY, encoded));
    }

    static FunctionalWorkflowSnapshot fromWorkflowState(WorkflowState state) {
        EncodedState encoded = Objects.requireNonNull(state, "state").values().get(STATE_KEY);
        if (encoded == null) {
            throw malformed("Functional workflow checkpoint state is absent.");
        }
        if (!TYPE_ID.equals(encoded.typeId())) {
            throw new SerializationException(
                    SerializationError.UNKNOWN_TYPE_ID,
                    "Functional workflow state expects typeId '" + TYPE_ID + "' but found '" + encoded.typeId() + "'.");
        }
        if (encoded.codecVersion() != VERSION) {
            throw new SerializationException(
                    SerializationError.CODEC_MIGRATION,
                    "Unsupported functional workflow state version " + encoded.codecVersion() + ".");
        }
        return fromStateValue(encoded.value());
    }

    private StateValue.ObjectValue toStateValue() {
        LinkedHashMap<String, StateValue> fields = new LinkedHashMap<>();
        fields.put("format", StateValue.string("agent-framework-java-functional-workflow-state"));
        fields.put("originalInput", originalInput.toStateValue());
        fields.put(
                "stepCache",
                StateValue.array(stepCache.entrySet().stream()
                        .map(entry -> stepValue(entry.getKey(), entry.getValue()))
                        .toList()));
        LinkedHashMap<String, StateValue> stateValues = new LinkedHashMap<>();
        userState.forEach((key, value) -> stateValues.put(key, value.toStateValue()));
        fields.put("userState", StateValue.object(stateValues));
        fields.put(
                "pendingRequests",
                StateValue.array(pendingRequests.values().stream()
                        .map(FunctionalWorkflowSnapshot::requestValue)
                        .toList()));
        LinkedHashMap<String, StateValue> responseValues = new LinkedHashMap<>();
        resolvedResponses.forEach((key, response) -> responseValues.put(key, response.toStateValue()));
        fields.put("resolvedResponses", StateValue.object(responseValues));
        fields.put("completedLiveSteps", StateValue.integer(completedLiveSteps));
        fields.put("checkpointOrdinal", StateValue.integer(checkpointOrdinal));
        return StateValue.object(fields);
    }

    private static FunctionalWorkflowSnapshot fromStateValue(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "Functional workflow state");
        requireExactFields(object, ROOT_FIELDS, "Functional workflow state");
        requireText(object, "format", "agent-framework-java-functional-workflow-state");
        EncodedState originalInput = EncodedState.fromStateValue(object.require("originalInput"));

        TreeMap<FunctionalStepInvocation, FunctionalCachedStep> cache = new TreeMap<>();
        for (StateValue item : requireArray(object, "stepCache").values()) {
            StateValue.ObjectValue step = requireObject(item, "Functional workflow step cache entry");
            requireExactFields(step, Set.of("stepName", "callIndex", "output", "autoRequestCount"), "Step cache entry");
            FunctionalStepInvocation invocation = new FunctionalStepInvocation(
                    requireString(step, "stepName"), requireNonNegativeInt(step, "callIndex"));
            FunctionalCachedStep cached = new FunctionalCachedStep(
                    EncodedState.fromStateValue(step.require("output")),
                    requireNonNegativeInt(step, "autoRequestCount"));
            if (cache.putIfAbsent(invocation, cached) != null) {
                throw malformed("Duplicate functional step cache entry '" + invocation.correlationId() + "'.");
            }
        }

        StateValue.ObjectValue encodedUserState = requireObject(object.require("userState"), "Functional user state");
        TreeMap<String, EncodedState> userState = new TreeMap<>();
        encodedUserState
                .values()
                .forEach((key, encoded) ->
                        userState.put(validateUserStateKey(key), EncodedState.fromStateValue(encoded)));

        TreeMap<String, FunctionalInputRequest> pending = new TreeMap<>();
        for (StateValue item : requireArray(object, "pendingRequests").values()) {
            StateValue.ObjectValue request = requireObject(item, "Functional input request");
            requireExactFields(
                    request,
                    Set.of("requestId", "sourceId", "data", "responseTypeId", "responseVersion"),
                    "Functional input request");
            FunctionalInputRequest parsed = new FunctionalInputRequest(
                    requireString(request, "requestId"),
                    requireString(request, "sourceId"),
                    request.require("data"),
                    requireString(request, "responseTypeId"),
                    requirePositiveInt(request, "responseVersion"));
            if (pending.putIfAbsent(parsed.requestId(), parsed) != null) {
                throw malformed("Duplicate pending functional input request '" + parsed.requestId() + "'.");
            }
        }

        StateValue.ObjectValue encodedResponses =
                requireObject(object.require("resolvedResponses"), "Resolved functional responses");
        TreeMap<String, EncodedState> resolvedResponses = new TreeMap<>();
        encodedResponses
                .values()
                .forEach((key, encoded) -> resolvedResponses.put(
                        WorkflowValidation.requireNonBlank(key, "resolved response id"),
                        EncodedState.fromStateValue(encoded)));

        return new FunctionalWorkflowSnapshot(
                originalInput,
                cache,
                userState,
                pending,
                resolvedResponses,
                requireNonNegativeInt(object, "completedLiveSteps"),
                requireNonNegativeInt(object, "checkpointOrdinal"));
    }

    private static StateValue.ObjectValue stepValue(FunctionalStepInvocation invocation, FunctionalCachedStep cached) {
        return StateValue.object(Map.of(
                "stepName",
                StateValue.string(invocation.stepName()),
                "callIndex",
                StateValue.integer(invocation.callIndex()),
                "output",
                cached.output().toStateValue(),
                "autoRequestCount",
                StateValue.integer(cached.autoRequestCount())));
    }

    private static StateValue.ObjectValue requestValue(FunctionalInputRequest request) {
        return StateValue.object(Map.of(
                "requestId",
                StateValue.string(request.requestId()),
                "sourceId",
                StateValue.string(request.sourceId()),
                "data",
                request.data(),
                "responseTypeId",
                StateValue.string(request.responseTypeId()),
                "responseVersion",
                StateValue.integer(request.responseVersion())));
    }

    private static String validateUserStateKey(String key) {
        String checked = WorkflowValidation.requireNonBlank(key, "user state key");
        if (checked.startsWith("_")) {
            throw new WorkflowValidationException(
                    "Functional workflow user state keys must not start with reserved prefix '_'.");
        }
        return checked;
    }

    private static StateValue.ObjectValue requireObject(StateValue value, String subject) {
        if (value instanceof StateValue.ObjectValue object) {
            return object;
        }
        throw malformed(subject + " must be an object.");
    }

    private static StateValue.ArrayValue requireArray(StateValue.ObjectValue object, String name) {
        StateValue value = object.require(name);
        if (value instanceof StateValue.ArrayValue array) {
            return array;
        }
        throw malformed("Functional workflow field '" + name + "' must be an array.");
    }

    private static String requireString(StateValue.ObjectValue object, String name) {
        StateValue value = object.require(name);
        if (value instanceof StateValue.StringValue string) {
            return string.value();
        }
        throw malformed("Functional workflow field '" + name + "' must be a string.");
    }

    private static void requireText(StateValue.ObjectValue object, String name, String expected) {
        String actual = requireString(object, name);
        if (!expected.equals(actual)) {
            throw malformed("Functional workflow field '" + name + "' must equal '" + expected + "'.");
        }
    }

    private static int requirePositiveInt(StateValue.ObjectValue object, String name) {
        int value = requireInt(object, name);
        if (value <= 0) {
            throw malformed("Functional workflow field '" + name + "' must be positive.");
        }
        return value;
    }

    private static int requireNonNegativeInt(StateValue.ObjectValue object, String name) {
        int value = requireInt(object, name);
        if (value < 0) {
            throw malformed("Functional workflow field '" + name + "' must not be negative.");
        }
        return value;
    }

    private static int requireInt(StateValue.ObjectValue object, String name) {
        StateValue value = object.require(name);
        if (value instanceof StateValue.NumberValue number && number.value().scale() <= 0) {
            try {
                return number.value().intValueExact();
            } catch (ArithmeticException exception) {
                throw malformed("Functional workflow field '" + name + "' must be an integer.");
            }
        }
        throw malformed("Functional workflow field '" + name + "' must be an integer.");
    }

    private static void requireExactFields(StateValue.ObjectValue object, Set<String> expected, String subject) {
        Set<String> actual = new LinkedHashSet<>(object.values().keySet());
        if (!actual.equals(expected)) {
            ArrayList<String> missing = new ArrayList<>(expected);
            missing.removeAll(actual);
            ArrayList<String> unexpected = new ArrayList<>(actual);
            unexpected.removeAll(expected);
            missing.sort(Comparator.naturalOrder());
            unexpected.sort(Comparator.naturalOrder());
            throw malformed(subject + " fields do not match; missing=" + missing + ", unexpected=" + unexpected + ".");
        }
    }

    private static SerializationException malformed(String message) {
        return new SerializationException(SerializationError.MALFORMED_DOCUMENT, message);
    }
}
