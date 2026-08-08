// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.a2a;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.microsoft.agents.core.StateValue;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Encodes and decodes bounded, deterministic A2A v1 JSON without polymorphic Java typing.
 *
 * <p>Object keys are written in lexical order. Arrays retain protocol order. Readers reject duplicate
 * keys, trailing content, overlong strings or numbers, oversized collections, and excessive nesting.
 */
public final class A2AJsonCodec {
    private final A2ALimits limits;
    private final JsonFactory factory;

    /**
     * Creates a codec with mandatory limits.
     *
     * @param limits parser and writer limits
     */
    public A2AJsonCodec(A2ALimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        factory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxDocumentLength(limits.maxResponseBytes())
                        .maxNestingDepth(limits.maxNestingDepth())
                        .maxStringLength(limits.maxStringLength())
                        .maxNameLength(limits.maxStringLength())
                        .maxNumberLength(Math.min(limits.maxStringLength(), 10_000))
                        .build())
                .build();
    }

    /**
     * Parses one bounded JSON value.
     *
     * @param utf8Json encoded JSON
     * @return immutable JSON-shaped value
     */
    public StateValue parse(byte[] utf8Json) {
        Objects.requireNonNull(utf8Json, "utf8Json");
        if (utf8Json.length > limits.maxResponseBytes()) {
            throw new A2ATransportException("A2A JSON exceeds maxResponseBytes.");
        }
        try (JsonParser parser = factory.createParser(utf8Json)) {
            JsonToken first = parser.nextToken();
            if (first == null) {
                throw protocol(A2AErrorCode.PARSE_ERROR, "A2A JSON is empty.");
            }
            StateValue value = readValue(parser, first);
            if (parser.nextToken() != null) {
                throw protocol(A2AErrorCode.PARSE_ERROR, "A2A JSON contains trailing content.");
            }
            return value;
        } catch (A2AException exception) {
            throw exception;
        } catch (StreamConstraintsException exception) {
            throw new A2ATransportException("A2A JSON exceeds configured parser limits.", exception);
        } catch (JsonParseException exception) {
            throw protocol(A2AErrorCode.PARSE_ERROR, "A2A JSON is malformed.", exception);
        } catch (IOException exception) {
            throw new A2ATransportException("Unable to read A2A JSON.", exception);
        }
    }

    /**
     * Parses UTF-8 JSON text.
     *
     * @param json JSON text
     * @return immutable JSON-shaped value
     */
    public StateValue parse(String json) {
        Objects.requireNonNull(json, "json");
        return parse(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Writes canonical compact JSON.
     *
     * @param value JSON-shaped value
     * @return UTF-8 JSON
     */
    public byte[] write(StateValue value) {
        Objects.requireNonNull(value, "value");
        validateValue(value, 1);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (JsonGenerator generator = factory.createGenerator(output)) {
                writeValue(generator, value);
            }
            byte[] bytes = output.toByteArray();
            if (bytes.length > limits.maxRequestBytes()) {
                throw new A2ATransportException("Encoded A2A JSON exceeds maxRequestBytes.");
            }
            return bytes;
        } catch (IOException exception) {
            throw new A2ATransportException("Unable to encode A2A JSON.", exception);
        }
    }

    /**
     * Writes canonical compact JSON text.
     *
     * @param value JSON-shaped value
     * @return JSON text
     */
    public String writeString(StateValue value) {
        return new String(write(value), StandardCharsets.UTF_8);
    }

    /**
     * Converts an agent card to its A2A JSON value.
     *
     * @param card agent card
     * @return JSON object
     */
    public StateValue.ObjectValue agentCardToValue(AgentCard card) {
        Objects.requireNonNull(card, "card");
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>(card.additionalProperties());
        put(values, "name", card.name());
        put(values, "description", card.description());
        if (card.provider() != null) {
            values.put(
                    "provider",
                    object(
                            "organization",
                            string(card.provider().organization()),
                            "url",
                            string(card.provider().url().toString())));
        }
        put(values, "version", card.version());
        putUri(values, "documentationUrl", card.documentationUrl());
        values.put("capabilities", capabilitiesToValue(card.capabilities()));
        values.put("defaultInputModes", strings(card.defaultInputModes()));
        values.put("defaultOutputModes", strings(card.defaultOutputModes()));
        values.put(
                "skills", array(card.skills().stream().map(this::skillToValue).toList()));
        if (!card.securitySchemes().isEmpty()) {
            LinkedHashMap<String, StateValue> schemes = new LinkedHashMap<>();
            card.securitySchemes().forEach((name, scheme) -> schemes.put(name, securitySchemeToValue(scheme)));
            values.put("securitySchemes", StateValue.object(schemes));
        }
        if (!card.securityRequirements().isEmpty()) {
            values.put(
                    "securityRequirements",
                    array(card.securityRequirements().stream()
                            .map(this::securityRequirementToValue)
                            .toList()));
        }
        putUri(values, "iconUrl", card.iconUrl());
        values.put(
                "supportedInterfaces",
                array(card.supportedInterfaces().stream()
                        .map(this::agentInterfaceToValue)
                        .toList()));
        if (!card.signatures().isEmpty()) {
            values.put(
                    "signatures",
                    array(card.signatures().stream().map(this::signatureToValue).toList()));
        }
        return StateValue.object(values);
    }

    /**
     * Decodes an agent card.
     *
     * @param value agent-card JSON object
     * @return immutable card
     */
    public AgentCard agentCardFromValue(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "AgentCard");
        Map<String, StateValue> values = object.values();
        AgentCard.Builder builder = AgentCard.builder(
                        requireString(object, "name"),
                        requireString(object, "description"),
                        requireString(object, "version"))
                .capabilities(capabilitiesFromValue(require(object, "capabilities")))
                .defaultInputModes(stringList(require(object, "defaultInputModes"), "defaultInputModes"))
                .defaultOutputModes(stringList(require(object, "defaultOutputModes"), "defaultOutputModes"))
                .skills(list(require(object, "skills"), "skills").stream()
                        .map(this::skillFromValue)
                        .toList())
                .supportedInterfaces(list(require(object, "supportedInterfaces"), "supportedInterfaces").stream()
                        .map(this::agentInterfaceFromValue)
                        .toList());
        optionalObject(values, "provider")
                .ifPresent(provider -> builder.provider(new AgentProvider(
                        requireString(provider, "organization"), URI.create(requireString(provider, "url")))));
        optionalString(values, "documentationUrl").map(URI::create).ifPresent(builder::documentationUrl);
        optionalString(values, "iconUrl").map(URI::create).ifPresent(builder::iconUrl);
        optionalObject(values, "securitySchemes").ifPresent(schemes -> {
            LinkedHashMap<String, SecurityScheme> decoded = new LinkedHashMap<>();
            schemes.values().forEach((name, scheme) -> decoded.put(name, securitySchemeFromValue(scheme)));
            builder.securitySchemes(decoded);
        });
        optionalArray(values, "securityRequirements")
                .ifPresent(array -> builder.securityRequirements(array.values().stream()
                        .map(this::securityRequirementFromValue)
                        .toList()));
        optionalArray(values, "signatures")
                .ifPresent(array -> builder.signatures(
                        array.values().stream().map(this::signatureFromValue).toList()));
        Set<String> known = Set.of(
                "name",
                "description",
                "provider",
                "version",
                "documentationUrl",
                "capabilities",
                "defaultInputModes",
                "defaultOutputModes",
                "skills",
                "securitySchemes",
                "securityRequirements",
                "iconUrl",
                "supportedInterfaces",
                "signatures");
        LinkedHashMap<String, StateValue> additional = new LinkedHashMap<>();
        values.forEach((name, member) -> {
            if (!known.contains(name)) {
                additional.put(name, member);
            }
        });
        return builder.additionalProperties(additional).build();
    }

    /**
     * Converts a message to its wire object.
     *
     * @param message message
     * @return JSON object
     */
    public StateValue.ObjectValue messageToValue(Message message) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        put(values, "role", message.role().name());
        values.put(
                "parts", array(message.parts().stream().map(this::partToValue).toList()));
        put(values, "messageId", message.messageId());
        put(values, "contextId", message.contextId());
        put(values, "taskId", message.taskId());
        if (!message.referenceTaskIds().isEmpty()) {
            values.put("referenceTaskIds", strings(message.referenceTaskIds()));
        }
        putMetadata(values, "metadata", message.metadata());
        if (!message.extensions().isEmpty()) {
            values.put(
                    "extensions",
                    strings(message.extensions().stream().map(URI::toString).toList()));
        }
        return StateValue.object(values);
    }

    /**
     * Decodes a message.
     *
     * @param value message JSON object
     * @return immutable message
     */
    public Message messageFromValue(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "Message");
        Message.Builder builder = Message.builder(parseEnum(Role.class, requireString(object, "role"), "role"))
                .parts(list(require(object, "parts"), "parts").stream()
                        .map(this::partFromValue)
                        .toList())
                .messageId(requireString(object, "messageId"));
        optionalString(object.values(), "contextId").ifPresent(builder::contextId);
        optionalString(object.values(), "taskId").ifPresent(builder::taskId);
        optionalArray(object.values(), "referenceTaskIds")
                .ifPresent(array -> builder.referenceTaskIds(stringList(array, "referenceTaskIds")));
        optionalObject(object.values(), "metadata").ifPresent(metadata -> builder.metadata(metadata.values()));
        optionalArray(object.values(), "extensions")
                .ifPresent(array -> builder.extensions(stringList(array, "extensions").stream()
                        .map(URI::create)
                        .toList()));
        return builder.build();
    }

    /**
     * Converts an artifact to its wire object.
     *
     * @param artifact artifact
     * @return JSON object
     */
    public StateValue.ObjectValue artifactToValue(Artifact artifact) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        put(values, "artifactId", artifact.artifactId());
        put(values, "name", artifact.name());
        put(values, "description", artifact.description());
        values.put(
                "parts", array(artifact.parts().stream().map(this::partToValue).toList()));
        putMetadata(values, "metadata", artifact.metadata());
        if (!artifact.extensions().isEmpty()) {
            values.put(
                    "extensions",
                    strings(artifact.extensions().stream().map(URI::toString).toList()));
        }
        return StateValue.object(values);
    }

    /**
     * Decodes an artifact.
     *
     * @param value artifact JSON object
     * @return immutable artifact
     */
    public Artifact artifactFromValue(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "Artifact");
        Artifact.Builder builder = Artifact.builder(requireString(object, "artifactId"))
                .parts(list(require(object, "parts"), "parts").stream()
                        .map(this::partFromValue)
                        .toList());
        optionalString(object.values(), "name").ifPresent(builder::name);
        optionalString(object.values(), "description").ifPresent(builder::description);
        optionalObject(object.values(), "metadata").ifPresent(metadata -> builder.metadata(metadata.values()));
        optionalArray(object.values(), "extensions")
                .ifPresent(array -> builder.extensions(stringList(array, "extensions").stream()
                        .map(URI::create)
                        .toList()));
        return builder.build();
    }

    /**
     * Converts a task to its wire object.
     *
     * @param task task snapshot
     * @return JSON object
     */
    public StateValue.ObjectValue taskToValue(Task task) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        put(values, "id", task.id());
        put(values, "contextId", task.contextId());
        values.put("status", taskStatusToValue(task.status()));
        if (!task.artifacts().isEmpty()) {
            values.put(
                    "artifacts",
                    array(task.artifacts().stream().map(this::artifactToValue).toList()));
        }
        if (!task.history().isEmpty()) {
            values.put(
                    "history",
                    array(task.history().stream().map(this::messageToValue).toList()));
        }
        putMetadata(values, "metadata", task.metadata());
        return StateValue.object(values);
    }

    /**
     * Decodes a task snapshot.
     *
     * @param value task JSON object
     * @return immutable task
     */
    public Task taskFromValue(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "Task");
        Task.Builder builder = Task.builder(
                requireString(object, "id"),
                requireString(object, "contextId"),
                taskStatusFromValue(require(object, "status")));
        optionalArray(object.values(), "artifacts")
                .ifPresent(array -> builder.artifacts(
                        array.values().stream().map(this::artifactFromValue).toList()));
        optionalArray(object.values(), "history")
                .ifPresent(array -> builder.history(
                        array.values().stream().map(this::messageFromValue).toList()));
        optionalObject(object.values(), "metadata").ifPresent(metadata -> builder.metadata(metadata.values()));
        return builder.build();
    }

    /**
     * Converts a task status to its wire object.
     *
     * @param status status
     * @return JSON object
     */
    public StateValue.ObjectValue taskStatusToValue(TaskStatus status) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        put(values, "state", status.state().name());
        if (status.message() != null) {
            values.put("message", messageToValue(status.message()));
        }
        put(values, "timestamp", status.timestamp().toString());
        return StateValue.object(values);
    }

    /**
     * Decodes a task status.
     *
     * @param value status JSON object
     * @return status
     */
    public TaskStatus taskStatusFromValue(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "TaskStatus");
        Message statusMessage =
                optional(object.values(), "message").map(this::messageFromValue).orElse(null);
        return new TaskStatus(
                parseEnum(TaskState.class, requireString(object, "state"), "state"),
                statusMessage,
                Instant.parse(requireString(object, "timestamp")));
    }

    /**
     * Converts one stream event to the v1 one-of wrapper.
     *
     * @param event event
     * @return stream-response JSON object
     */
    public StateValue.ObjectValue streamEventToValue(A2AStreamEvent event) {
        Objects.requireNonNull(event, "event");
        StateValue payload =
                switch (event) {
                    case Message message -> messageToValue(message);
                    case Task task -> taskToValue(task);
                    case TaskArtifactUpdateEvent update -> artifactUpdateToValue(update);
                    case TaskStatusUpdateEvent update -> statusUpdateToValue(update);
                };
        return StateValue.object(Map.of(event.kind(), payload));
    }

    /**
     * Decodes one stream event and rejects ambiguous wrappers.
     *
     * @param value stream-response value
     * @return event
     */
    public A2AStreamEvent streamEventFromValue(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "StreamResponse");
        if (object.values().size() != 1) {
            throw protocol(A2AErrorCode.INVALID_AGENT_RESPONSE, "StreamResponse must contain exactly one event kind.");
        }
        Map.Entry<String, StateValue> entry =
                object.values().entrySet().iterator().next();
        return switch (entry.getKey()) {
            case "message" -> messageFromValue(entry.getValue());
            case "task" -> taskFromValue(entry.getValue());
            case "statusUpdate" -> statusUpdateFromValue(entry.getValue());
            case "artifactUpdate" -> artifactUpdateFromValue(entry.getValue());
            default ->
                throw protocol(
                        A2AErrorCode.INVALID_AGENT_RESPONSE,
                        "Unknown StreamResponse event kind '" + entry.getKey() + "'.");
        };
    }

    /**
     * Converts a finite send result to its one-of wrapper.
     *
     * @param result send result
     * @return JSON object
     */
    public StateValue.ObjectValue sendMessageResultToValue(SendMessageResult result) {
        if (result instanceof Message message) {
            return StateValue.object(Map.of("message", messageToValue(message)));
        }
        if (result instanceof Task task) {
            return StateValue.object(Map.of("task", taskToValue(task)));
        }
        throw new IllegalArgumentException("Unsupported send result " + result);
    }

    /**
     * Decodes a finite send result.
     *
     * @param value result wrapper
     * @return message or task
     */
    public SendMessageResult sendMessageResultFromValue(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "SendMessageResult");
        if (object.values().size() != 1) {
            throw protocol(
                    A2AErrorCode.INVALID_AGENT_RESPONSE,
                    "SendMessage result must contain exactly one of message or task.");
        }
        if (object.values().containsKey("message")) {
            return messageFromValue(object.values().get("message"));
        }
        if (object.values().containsKey("task")) {
            return taskFromValue(object.values().get("task"));
        }
        throw protocol(A2AErrorCode.INVALID_AGENT_RESPONSE, "SendMessage result must contain message or task.");
    }

    /**
     * Converts push configuration to its wire object.
     *
     * @param config configuration
     * @return JSON object
     */
    public StateValue.ObjectValue pushConfigToValue(PushNotificationConfig config) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        put(values, "id", config.id());
        put(values, "taskId", config.taskId());
        put(values, "url", config.url().toString());
        put(values, "token", config.token());
        if (config.authentication() != null) {
            LinkedHashMap<String, StateValue> authentication = new LinkedHashMap<>();
            put(authentication, "scheme", config.authentication().scheme());
            put(authentication, "credentials", config.authentication().credentials());
            values.put("authentication", StateValue.object(authentication));
        }
        put(values, "tenant", config.tenant());
        return StateValue.object(values);
    }

    /**
     * Decodes push configuration.
     *
     * @param value configuration value
     * @return configuration
     */
    public PushNotificationConfig pushConfigFromValue(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "PushNotificationConfig");
        AuthenticationInfo authentication = optionalObject(object.values(), "authentication")
                .map(auth -> new AuthenticationInfo(
                        requireString(auth, "scheme"),
                        optionalString(auth.values(), "credentials").orElse(null)))
                .orElse(null);
        return new PushNotificationConfig(
                requireString(object, "id"),
                optionalString(object.values(), "taskId").orElse(null),
                URI.create(requireString(object, "url")),
                optionalString(object.values(), "token").orElse(null),
                authentication,
                optionalString(object.values(), "tenant").orElse(null));
    }

    /**
     * Converts a send request to JSON-RPC params.
     *
     * @param request request
     * @return params object
     */
    public StateValue.ObjectValue sendMessageRequestToValue(SendMessageRequest request) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        values.put("message", messageToValue(request.message()));
        values.put("configuration", sendConfigurationToValue(request.configuration()));
        putMetadata(values, "metadata", request.metadata());
        put(values, "tenant", request.tenant());
        return StateValue.object(values);
    }

    /**
     * Decodes JSON-RPC send params.
     *
     * @param value params
     * @return request
     */
    public SendMessageRequest sendMessageRequestFromValue(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "SendMessageRequest");
        SendMessageConfiguration configuration = optional(object.values(), "configuration")
                .map(this::sendConfigurationFromValue)
                .orElseGet(SendMessageConfiguration::defaults);
        return new SendMessageRequest(
                messageFromValue(require(object, "message")),
                configuration,
                optionalObject(object.values(), "metadata")
                        .map(StateValue.ObjectValue::values)
                        .orElse(Map.of()),
                optionalString(object.values(), "tenant").orElse(null));
    }

    /**
     * Converts a task-get request to params.
     *
     * @param request request
     * @return params
     */
    public StateValue.ObjectValue getTaskRequestToValue(A2ARequests.GetTask request) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        put(values, "id", request.taskId());
        putNumber(values, "historyLength", request.historyLength());
        put(values, "tenant", request.tenant());
        return StateValue.object(values);
    }

    /**
     * Decodes task-get params.
     *
     * @param value params
     * @return request
     */
    public A2ARequests.GetTask getTaskRequestFromValue(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "GetTaskRequest");
        return new A2ARequests.GetTask(
                requireString(object, "id"),
                optionalInt(object.values(), "historyLength").orElse(null),
                optionalString(object.values(), "tenant").orElse(null));
    }

    /**
     * Converts list-task filters to params.
     *
     * @param request request
     * @return params
     */
    public StateValue.ObjectValue listTasksRequestToValue(A2ARequests.ListTasks request) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        put(values, "contextId", request.contextId());
        if (request.status() != null) {
            put(values, "status", request.status().name());
        }
        putNumber(values, "pageSize", request.pageSize());
        put(values, "pageToken", request.pageToken());
        putNumber(values, "historyLength", request.historyLength());
        if (request.statusTimestampAfter() != null) {
            put(values, "statusTimestampAfter", request.statusTimestampAfter().toString());
        }
        values.put("includeArtifacts", StateValue.bool(request.includeArtifacts()));
        put(values, "tenant", request.tenant());
        return StateValue.object(values);
    }

    /**
     * Decodes list-task params.
     *
     * @param value params
     * @return request
     */
    public A2ARequests.ListTasks listTasksRequestFromValue(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "ListTasksRequest");
        return new A2ARequests.ListTasks(
                optionalString(object.values(), "contextId").orElse(null),
                optionalString(object.values(), "status")
                        .map(name -> parseEnum(TaskState.class, name, "status"))
                        .orElse(null),
                optionalInt(object.values(), "pageSize").orElse(50),
                optionalString(object.values(), "pageToken").orElse(null),
                optionalInt(object.values(), "historyLength").orElse(0),
                optionalString(object.values(), "statusTimestampAfter")
                        .map(Instant::parse)
                        .orElse(null),
                optionalBoolean(object.values(), "includeArtifacts").orElse(false),
                optionalString(object.values(), "tenant").orElse(null));
    }

    /**
     * Converts task cancellation params.
     *
     * @param request request
     * @return params
     */
    public StateValue.ObjectValue cancelTaskRequestToValue(A2ARequests.CancelTask request) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        put(values, "id", request.taskId());
        putMetadata(values, "metadata", request.metadata());
        put(values, "tenant", request.tenant());
        return StateValue.object(values);
    }

    /**
     * Decodes task cancellation params.
     *
     * @param value params
     * @return request
     */
    public A2ARequests.CancelTask cancelTaskRequestFromValue(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "CancelTaskRequest");
        return new A2ARequests.CancelTask(
                requireString(object, "id"),
                optionalObject(object.values(), "metadata")
                        .map(StateValue.ObjectValue::values)
                        .orElse(Map.of()),
                optionalString(object.values(), "tenant").orElse(null));
    }

    /**
     * Converts subscription params.
     *
     * @param request request
     * @return params
     */
    public StateValue.ObjectValue subscribeRequestToValue(A2ARequests.SubscribeToTask request) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        put(values, "id", request.taskId());
        put(values, "tenant", request.tenant());
        return StateValue.object(values);
    }

    /**
     * Decodes subscription params.
     *
     * @param value params
     * @return request
     */
    public A2ARequests.SubscribeToTask subscribeRequestFromValue(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "SubscribeToTaskRequest");
        return new A2ARequests.SubscribeToTask(
                requireString(object, "id"),
                optionalString(object.values(), "tenant").orElse(null));
    }

    /**
     * Converts push-get params.
     *
     * @param request request
     * @return params
     */
    public StateValue.ObjectValue getPushConfigRequestToValue(A2ARequests.GetPushConfig request) {
        return idPair(request.taskId(), request.configId(), request.tenant());
    }

    /**
     * Decodes push-get params.
     *
     * @param value params
     * @return request
     */
    public A2ARequests.GetPushConfig getPushConfigRequestFromValue(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "GetPushConfigRequest");
        return new A2ARequests.GetPushConfig(
                requireString(object, "taskId"),
                requireString(object, "id"),
                optionalString(object.values(), "tenant").orElse(null));
    }

    /**
     * Converts push-list params.
     *
     * @param request request
     * @return params
     */
    public StateValue.ObjectValue listPushConfigsRequestToValue(A2ARequests.ListPushConfigs request) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        put(values, "taskId", request.taskId());
        putNumber(values, "pageSize", request.pageSize());
        put(values, "pageToken", request.pageToken());
        put(values, "tenant", request.tenant());
        return StateValue.object(values);
    }

    /**
     * Decodes push-list params.
     *
     * @param value params
     * @return request
     */
    public A2ARequests.ListPushConfigs listPushConfigsRequestFromValue(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "ListPushConfigsRequest");
        return new A2ARequests.ListPushConfigs(
                requireString(object, "taskId"),
                optionalInt(object.values(), "pageSize").orElse(50),
                optionalString(object.values(), "pageToken").orElse(null),
                optionalString(object.values(), "tenant").orElse(null));
    }

    /**
     * Converts push-delete params.
     *
     * @param request request
     * @return params
     */
    public StateValue.ObjectValue deletePushConfigRequestToValue(A2ARequests.DeletePushConfig request) {
        return idPair(request.taskId(), request.configId(), request.tenant());
    }

    /**
     * Decodes push-delete params.
     *
     * @param value params
     * @return request
     */
    public A2ARequests.DeletePushConfig deletePushConfigRequestFromValue(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "DeletePushConfigRequest");
        return new A2ARequests.DeletePushConfig(
                requireString(object, "taskId"),
                requireString(object, "id"),
                optionalString(object.values(), "tenant").orElse(null));
    }

    /**
     * Converts extended-card params.
     *
     * @param request request
     * @return params
     */
    public StateValue.ObjectValue extendedCardRequestToValue(A2ARequests.GetExtendedAgentCard request) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        put(values, "tenant", request.tenant());
        return StateValue.object(values);
    }

    /**
     * Decodes extended-card params.
     *
     * @param value params
     * @return request
     */
    public A2ARequests.GetExtendedAgentCard extendedCardRequestFromValue(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "GetExtendedAgentCardRequest");
        return new A2ARequests.GetExtendedAgentCard(
                optionalString(object.values(), "tenant").orElse(null));
    }

    /**
     * Converts a task page to the ListTasks result object.
     *
     * @param page task page
     * @return JSON object
     */
    public StateValue.ObjectValue taskPageToValue(A2ACursorPage<Task> page) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        values.put("tasks", array(page.items().stream().map(this::taskToValue).toList()));
        put(values, "nextPageToken", page.nextPageToken());
        putNumber(values, "pageSize", page.pageSize());
        putNumber(values, "totalSize", page.totalSize());
        return StateValue.object(values);
    }

    /**
     * Decodes a task page.
     *
     * @param value result object
     * @return task page
     */
    public A2ACursorPage<Task> taskPageFromValue(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "ListTasksResult");
        List<Task> tasks = list(require(object, "tasks"), "tasks").stream()
                .map(this::taskFromValue)
                .toList();
        return new A2ACursorPage<>(
                tasks,
                optionalString(object.values(), "nextPageToken").orElse(null),
                optionalInt(object.values(), "pageSize").orElse(Math.max(1, tasks.size())),
                optionalLong(object.values(), "totalSize").orElse(null));
    }

    /**
     * Converts a push-configuration page.
     *
     * @param page configuration page
     * @return JSON object
     */
    public StateValue.ObjectValue pushConfigPageToValue(A2ACursorPage<PushNotificationConfig> page) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        values.put(
                "configs",
                array(page.items().stream().map(this::pushConfigToValue).toList()));
        put(values, "nextPageToken", page.nextPageToken());
        putNumber(values, "pageSize", page.pageSize());
        putNumber(values, "totalSize", page.totalSize());
        return StateValue.object(values);
    }

    /**
     * Decodes a push-configuration page.
     *
     * @param value result object
     * @return configuration page
     */
    public A2ACursorPage<PushNotificationConfig> pushConfigPageFromValue(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "ListPushConfigsResult");
        List<PushNotificationConfig> configs = list(require(object, "configs"), "configs").stream()
                .map(this::pushConfigFromValue)
                .toList();
        return new A2ACursorPage<>(
                configs,
                optionalString(object.values(), "nextPageToken").orElse(null),
                optionalInt(object.values(), "pageSize").orElse(Math.max(1, configs.size())),
                optionalLong(object.values(), "totalSize").orElse(null));
    }

    private StateValue.ObjectValue sendConfigurationToValue(SendMessageConfiguration configuration) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        if (!configuration.acceptedOutputModes().isEmpty()) {
            values.put("acceptedOutputModes", strings(configuration.acceptedOutputModes()));
        }
        putNumber(values, "historyLength", configuration.historyLength());
        if (configuration.taskPushNotificationConfig() != null) {
            values.put("taskPushNotificationConfig", pushConfigToValue(configuration.taskPushNotificationConfig()));
        }
        values.put("returnImmediately", StateValue.bool(configuration.returnImmediately()));
        return StateValue.object(values);
    }

    private SendMessageConfiguration sendConfigurationFromValue(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "SendMessageConfiguration");
        return new SendMessageConfiguration(
                optionalArray(object.values(), "acceptedOutputModes")
                        .map(array -> stringList(array, "acceptedOutputModes"))
                        .orElse(List.of()),
                optionalInt(object.values(), "historyLength").orElse(null),
                optional(object.values(), "taskPushNotificationConfig")
                        .map(this::pushConfigFromValue)
                        .orElse(null),
                optionalBoolean(object.values(), "returnImmediately").orElse(false));
    }

    private StateValue.ObjectValue statusUpdateToValue(TaskStatusUpdateEvent update) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        put(values, "taskId", update.taskId());
        put(values, "contextId", update.contextId());
        values.put("status", taskStatusToValue(update.status()));
        putMetadata(values, "metadata", update.metadata());
        return StateValue.object(values);
    }

    private TaskStatusUpdateEvent statusUpdateFromValue(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "TaskStatusUpdateEvent");
        return new TaskStatusUpdateEvent(
                requireString(object, "taskId"),
                requireString(object, "contextId"),
                taskStatusFromValue(require(object, "status")),
                optionalObject(object.values(), "metadata")
                        .map(StateValue.ObjectValue::values)
                        .orElse(Map.of()));
    }

    private StateValue.ObjectValue artifactUpdateToValue(TaskArtifactUpdateEvent update) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        put(values, "taskId", update.taskId());
        put(values, "contextId", update.contextId());
        values.put("artifact", artifactToValue(update.artifact()));
        values.put("append", StateValue.bool(update.append()));
        values.put("lastChunk", StateValue.bool(update.lastChunk()));
        putMetadata(values, "metadata", update.metadata());
        return StateValue.object(values);
    }

    private TaskArtifactUpdateEvent artifactUpdateFromValue(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "TaskArtifactUpdateEvent");
        return new TaskArtifactUpdateEvent(
                requireString(object, "taskId"),
                requireString(object, "contextId"),
                artifactFromValue(require(object, "artifact")),
                optionalBoolean(object.values(), "append").orElse(false),
                optionalBoolean(object.values(), "lastChunk").orElse(false),
                optionalObject(object.values(), "metadata")
                        .map(StateValue.ObjectValue::values)
                        .orElse(Map.of()));
    }

    private StateValue.ObjectValue partToValue(Part part) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        switch (part) {
            case TextPart text -> {
                put(values, "text", text.text());
                if (!"text/plain".equals(text.mediaType())) {
                    put(values, "mediaType", text.mediaType());
                }
            }
            case DataPart data -> {
                values.put("data", data.data());
                if (!"application/json".equals(data.mediaType())) {
                    put(values, "mediaType", data.mediaType());
                }
            }
            case FilePart file -> {
                LinkedHashMap<String, StateValue> fileValue = new LinkedHashMap<>();
                put(fileValue, "mimeType", file.mediaType());
                put(fileValue, "name", file.filename());
                if (file.inline()) {
                    put(fileValue, "bytes", Base64.getEncoder().encodeToString(file.bytes()));
                } else {
                    put(fileValue, "uri", file.uri().toString());
                }
                values.put("file", StateValue.object(fileValue));
            }
        }
        putMetadata(values, "metadata", part.metadata());
        return StateValue.object(values);
    }

    private Part partFromValue(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "Part");
        int variants = (object.values().containsKey("text") ? 1 : 0)
                + (object.values().containsKey("file") ? 1 : 0)
                + (object.values().containsKey("data") ? 1 : 0);
        if (variants != 1) {
            throw protocol(A2AErrorCode.INVALID_PARAMS, "Part must contain exactly one of text, file, or data.");
        }
        Map<String, StateValue> metadata = optionalObject(object.values(), "metadata")
                .map(StateValue.ObjectValue::values)
                .orElse(Map.of());
        String mediaType = optionalString(object.values(), "mediaType").orElse(null);
        if (object.values().containsKey("text")) {
            return new TextPart(requireString(object, "text"), mediaType == null ? "text/plain" : mediaType, metadata);
        }
        if (object.values().containsKey("data")) {
            return new DataPart(require(object, "data"), mediaType == null ? "application/json" : mediaType, metadata);
        }
        StateValue.ObjectValue file = requireObject(require(object, "file"), "FileContent");
        String mimeType = requireString(file, "mimeType");
        String filename = requireString(file, "name");
        boolean hasBytes = file.values().containsKey("bytes");
        boolean hasUri = file.values().containsKey("uri");
        if (hasBytes == hasUri) {
            throw protocol(A2AErrorCode.INVALID_PARAMS, "File content must contain exactly one of bytes or uri.");
        }
        if (hasBytes) {
            try {
                return FilePart.bytes(
                        Base64.getDecoder().decode(requireString(file, "bytes")), filename, mimeType, metadata);
            } catch (IllegalArgumentException exception) {
                throw protocol(A2AErrorCode.INVALID_PARAMS, "File bytes contain invalid base64.", exception);
            }
        }
        return FilePart.uri(URI.create(requireString(file, "uri")), filename, mimeType, metadata);
    }

    private StateValue.ObjectValue capabilitiesToValue(AgentCapabilities capabilities) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        values.put("streaming", StateValue.bool(capabilities.streaming()));
        values.put("pushNotifications", StateValue.bool(capabilities.pushNotifications()));
        values.put("extendedAgentCard", StateValue.bool(capabilities.extendedAgentCard()));
        if (!capabilities.extensions().isEmpty()) {
            values.put(
                    "extensions",
                    array(capabilities.extensions().stream()
                            .map(this::extensionToValue)
                            .toList()));
        }
        return StateValue.object(values);
    }

    private AgentCapabilities capabilitiesFromValue(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "AgentCapabilities");
        return new AgentCapabilities(
                optionalBoolean(object.values(), "streaming").orElse(false),
                optionalBoolean(object.values(), "pushNotifications").orElse(false),
                optionalBoolean(object.values(), "extendedAgentCard").orElse(false),
                optionalArray(object.values(), "extensions")
                        .map(array -> array.values().stream()
                                .map(this::extensionFromValue)
                                .toList())
                        .orElse(List.of()));
    }

    private StateValue.ObjectValue extensionToValue(AgentExtension extension) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        put(values, "uri", extension.uri().toString());
        put(values, "description", extension.description());
        values.put("required", StateValue.bool(extension.required()));
        putMetadata(values, "params", extension.params());
        return StateValue.object(values);
    }

    private AgentExtension extensionFromValue(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "AgentExtension");
        return new AgentExtension(
                URI.create(requireString(object, "uri")),
                optionalString(object.values(), "description").orElse(null),
                optionalBoolean(object.values(), "required").orElse(false),
                optionalObject(object.values(), "params")
                        .map(StateValue.ObjectValue::values)
                        .orElse(Map.of()));
    }

    private StateValue.ObjectValue skillToValue(AgentSkill skill) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        put(values, "id", skill.id());
        put(values, "name", skill.name());
        put(values, "description", skill.description());
        values.put("tags", strings(skill.tags()));
        if (!skill.examples().isEmpty()) {
            values.put("examples", strings(skill.examples()));
        }
        if (!skill.inputModes().isEmpty()) {
            values.put("inputModes", strings(skill.inputModes()));
        }
        if (!skill.outputModes().isEmpty()) {
            values.put("outputModes", strings(skill.outputModes()));
        }
        if (!skill.securityRequirements().isEmpty()) {
            values.put(
                    "securityRequirements",
                    array(skill.securityRequirements().stream()
                            .map(this::securityRequirementToValue)
                            .toList()));
        }
        putMetadata(values, "metadata", skill.metadata());
        return StateValue.object(values);
    }

    private AgentSkill skillFromValue(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "AgentSkill");
        AgentSkill.Builder builder = AgentSkill.builder(
                        requireString(object, "id"),
                        requireString(object, "name"),
                        requireString(object, "description"))
                .tags(optionalArray(object.values(), "tags")
                        .map(array -> stringList(array, "tags"))
                        .orElse(List.of()))
                .examples(optionalArray(object.values(), "examples")
                        .map(array -> stringList(array, "examples"))
                        .orElse(List.of()))
                .inputModes(optionalArray(object.values(), "inputModes")
                        .map(array -> stringList(array, "inputModes"))
                        .orElse(List.of()))
                .outputModes(optionalArray(object.values(), "outputModes")
                        .map(array -> stringList(array, "outputModes"))
                        .orElse(List.of()))
                .securityRequirements(optionalArray(object.values(), "securityRequirements")
                        .map(array -> array.values().stream()
                                .map(this::securityRequirementFromValue)
                                .toList())
                        .orElse(List.of()));
        optionalObject(object.values(), "metadata").ifPresent(metadata -> builder.metadata(metadata.values()));
        return builder.build();
    }

    private StateValue.ObjectValue agentInterfaceToValue(AgentInterface agentInterface) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        put(values, "protocolBinding", agentInterface.protocolBinding());
        put(values, "url", agentInterface.url().toString());
        put(values, "tenant", agentInterface.tenant());
        put(values, "protocolVersion", agentInterface.protocolVersion());
        return StateValue.object(values);
    }

    private AgentInterface agentInterfaceFromValue(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "AgentInterface");
        return new AgentInterface(
                requireString(object, "protocolBinding"),
                URI.create(requireString(object, "url")),
                optionalString(object.values(), "protocolVersion").orElse(A2AProtocol.VERSION),
                optionalString(object.values(), "tenant").orElse(null));
    }

    private StateValue.ObjectValue signatureToValue(AgentCardSignature signature) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        put(values, "protected", signature.protectedHeader());
        put(values, "signature", signature.signature());
        putMetadata(values, "header", signature.header());
        return StateValue.object(values);
    }

    private AgentCardSignature signatureFromValue(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "AgentCardSignature");
        return new AgentCardSignature(
                requireString(object, "protected"),
                requireString(object, "signature"),
                optionalObject(object.values(), "header")
                        .map(StateValue.ObjectValue::values)
                        .orElse(Map.of()));
    }

    private StateValue.ObjectValue securityRequirementToValue(SecurityRequirement requirement) {
        LinkedHashMap<String, StateValue> schemes = new LinkedHashMap<>();
        requirement.schemes().forEach((name, scopes) -> schemes.put(name, strings(scopes)));
        return StateValue.object(Map.of("schemes", StateValue.object(schemes)));
    }

    private SecurityRequirement securityRequirementFromValue(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "SecurityRequirement");
        StateValue.ObjectValue schemes = requireObject(require(object, "schemes"), "schemes");
        LinkedHashMap<String, List<String>> decoded = new LinkedHashMap<>();
        schemes.values().forEach((name, scopes) -> decoded.put(name, stringList(scopes, "scopes")));
        return new SecurityRequirement(decoded);
    }

    private StateValue.ObjectValue securitySchemeToValue(SecurityScheme scheme) {
        LinkedHashMap<String, StateValue> payload = new LinkedHashMap<>();
        String discriminator;
        switch (scheme) {
            case SecurityScheme.ApiKey apiKey -> {
                discriminator = "apiKeySecurityScheme";
                put(payload, "name", apiKey.name());
                put(payload, "location", apiKey.location());
                put(payload, "description", apiKey.description());
            }
            case SecurityScheme.Http http -> {
                discriminator = "httpAuthSecurityScheme";
                put(payload, "scheme", http.scheme());
                put(payload, "bearerFormat", http.bearerFormat());
                put(payload, "description", http.description());
            }
            case SecurityScheme.MutualTls mutualTls -> {
                discriminator = "mtlsSecurityScheme";
                put(payload, "description", mutualTls.description());
            }
            case SecurityScheme.OpenIdConnect openId -> {
                discriminator = "openIdConnectSecurityScheme";
                put(payload, "openIdConnectUrl", openId.openIdConnectUrl().toString());
                put(payload, "description", openId.description());
            }
            case SecurityScheme.OAuth2 oauth2 -> {
                discriminator = "oauth2SecurityScheme";
                LinkedHashMap<String, StateValue> flows = new LinkedHashMap<>();
                oauth2.flows().forEach((name, flow) -> flows.put(name, oauthFlowToValue(flow)));
                payload.put("flows", StateValue.object(flows));
                putUri(payload, "oauth2MetadataUrl", oauth2.metadataUrl());
                put(payload, "description", oauth2.description());
            }
        }
        return StateValue.object(Map.of(discriminator, StateValue.object(payload)));
    }

    private SecurityScheme securitySchemeFromValue(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "SecurityScheme");
        if (object.values().size() != 1) {
            throw protocol(A2AErrorCode.INVALID_PARAMS, "SecurityScheme must contain exactly one variant.");
        }
        Map.Entry<String, StateValue> variant =
                object.values().entrySet().iterator().next();
        StateValue.ObjectValue payload = requireObject(variant.getValue(), variant.getKey());
        return switch (variant.getKey()) {
            case "apiKeySecurityScheme" ->
                new SecurityScheme.ApiKey(
                        requireString(payload, "name"),
                        requireString(payload, "location"),
                        optionalString(payload.values(), "description").orElse(null));
            case "httpAuthSecurityScheme" ->
                new SecurityScheme.Http(
                        requireString(payload, "scheme"),
                        optionalString(payload.values(), "bearerFormat").orElse(null),
                        optionalString(payload.values(), "description").orElse(null));
            case "mtlsSecurityScheme" ->
                new SecurityScheme.MutualTls(
                        optionalString(payload.values(), "description").orElse(null));
            case "openIdConnectSecurityScheme" ->
                new SecurityScheme.OpenIdConnect(
                        URI.create(requireString(payload, "openIdConnectUrl")),
                        optionalString(payload.values(), "description").orElse(null));
            case "oauth2SecurityScheme" -> {
                StateValue.ObjectValue flows = requireObject(require(payload, "flows"), "flows");
                LinkedHashMap<String, SecurityScheme.OAuthFlow> decoded = new LinkedHashMap<>();
                flows.values().forEach((name, flow) -> decoded.put(name, oauthFlowFromValue(flow)));
                yield new SecurityScheme.OAuth2(
                        decoded,
                        optionalString(payload.values(), "oauth2MetadataUrl")
                                .map(URI::create)
                                .orElse(null),
                        optionalString(payload.values(), "description").orElse(null));
            }
            default ->
                throw protocol(
                        A2AErrorCode.INVALID_PARAMS, "Unknown SecurityScheme variant '" + variant.getKey() + "'.");
        };
    }

    private StateValue.ObjectValue oauthFlowToValue(SecurityScheme.OAuthFlow flow) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        putUri(values, "authorizationUrl", flow.authorizationUrl());
        putUri(values, "tokenUrl", flow.tokenUrl());
        putUri(values, "refreshUrl", flow.refreshUrl());
        LinkedHashMap<String, StateValue> scopes = new LinkedHashMap<>();
        flow.scopes().forEach((name, description) -> scopes.put(name, string(description)));
        values.put("scopes", StateValue.object(scopes));
        return StateValue.object(values);
    }

    private SecurityScheme.OAuthFlow oauthFlowFromValue(StateValue value) {
        StateValue.ObjectValue object = requireObject(value, "OAuthFlow");
        StateValue.ObjectValue scopes = requireObject(require(object, "scopes"), "scopes");
        LinkedHashMap<String, String> decoded = new LinkedHashMap<>();
        scopes.values()
                .forEach(
                        (name, description) -> decoded.put(name, requireStringValue(description, "scope description")));
        return new SecurityScheme.OAuthFlow(
                optionalString(object.values(), "authorizationUrl")
                        .map(URI::create)
                        .orElse(null),
                optionalString(object.values(), "tokenUrl").map(URI::create).orElse(null),
                optionalString(object.values(), "refreshUrl").map(URI::create).orElse(null),
                decoded);
    }

    private StateValue.ObjectValue idPair(String taskId, String configId, String tenant) {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        put(values, "taskId", taskId);
        put(values, "id", configId);
        put(values, "tenant", tenant);
        return StateValue.object(values);
    }

    private StateValue readValue(JsonParser parser, JsonToken token) throws IOException {
        return switch (token) {
            case START_OBJECT -> readObject(parser);
            case START_ARRAY -> readArray(parser);
            case VALUE_STRING -> StateValue.string(parser.getText());
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> {
                if (parser.isNaN()) {
                    throw protocol(A2AErrorCode.PARSE_ERROR, "A2A JSON contains a non-finite number.");
                }
                yield StateValue.number(parser.getDecimalValue());
            }
            case VALUE_TRUE -> StateValue.bool(true);
            case VALUE_FALSE -> StateValue.bool(false);
            case VALUE_NULL -> StateValue.nullValue();
            default -> throw protocol(A2AErrorCode.PARSE_ERROR, "Unexpected A2A JSON token " + token + ".");
        };
    }

    private StateValue.ObjectValue readObject(JsonParser parser) throws IOException {
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            if (token != JsonToken.FIELD_NAME) {
                throw protocol(A2AErrorCode.PARSE_ERROR, "Expected an A2A JSON object member name.");
            }
            if (values.size() >= limits.maxCollectionEntries()) {
                throw new A2ATransportException("A2A JSON object exceeds maxCollectionEntries.");
            }
            String name = parser.currentName();
            A2AValidation.nonBlank(name, "JSON object member");
            if (values.containsKey(name)) {
                throw protocol(A2AErrorCode.PARSE_ERROR, "A2A JSON contains duplicate key '" + name + "'.");
            }
            JsonToken valueToken = parser.nextToken();
            if (valueToken == null) {
                throw protocol(A2AErrorCode.PARSE_ERROR, "A2A JSON object member '" + name + "' has no value.");
            }
            values.put(name, readValue(parser, valueToken));
        }
        return StateValue.object(values);
    }

    private StateValue.ArrayValue readArray(JsonParser parser) throws IOException {
        ArrayList<StateValue> values = new ArrayList<>();
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
            if (token == null) {
                throw protocol(A2AErrorCode.PARSE_ERROR, "A2A JSON array is not closed.");
            }
            if (values.size() >= limits.maxCollectionEntries()) {
                throw new A2ATransportException("A2A JSON array exceeds maxCollectionEntries.");
            }
            values.add(readValue(parser, token));
        }
        return StateValue.array(values);
    }

    private void writeValue(JsonGenerator generator, StateValue value) throws IOException {
        switch (value) {
            case StateValue.ObjectValue object -> {
                generator.writeStartObject();
                for (Map.Entry<String, StateValue> entry : new TreeMap<>(object.values()).entrySet()) {
                    generator.writeFieldName(entry.getKey());
                    writeValue(generator, entry.getValue());
                }
                generator.writeEndObject();
            }
            case StateValue.ArrayValue array -> {
                generator.writeStartArray();
                for (StateValue item : array.values()) {
                    writeValue(generator, item);
                }
                generator.writeEndArray();
            }
            case StateValue.StringValue string -> generator.writeString(string.value());
            case StateValue.NumberValue number -> generator.writeNumber(number.value());
            case StateValue.BooleanValue bool -> generator.writeBoolean(bool.value());
            case StateValue.NullValue ignored -> generator.writeNull();
        }
    }

    private void validateValue(StateValue value, int depth) {
        if (depth > limits.maxNestingDepth()) {
            throw new A2ATransportException("A2A JSON exceeds maxNestingDepth.");
        }
        switch (value) {
            case StateValue.ObjectValue object -> {
                requireCollectionSize(object.values().size());
                object.values().forEach((name, member) -> {
                    requireStringSize(name);
                    validateValue(member, depth + 1);
                });
            }
            case StateValue.ArrayValue array -> {
                requireCollectionSize(array.values().size());
                array.values().forEach(member -> validateValue(member, depth + 1));
            }
            case StateValue.StringValue string -> requireStringSize(string.value());
            case StateValue.NumberValue number ->
                requireStringSize(number.value().toPlainString());
            case StateValue.BooleanValue ignored -> {}
            case StateValue.NullValue ignored -> {}
        }
    }

    private void requireCollectionSize(int size) {
        if (size > limits.maxCollectionEntries()) {
            throw new A2ATransportException("A2A JSON exceeds maxCollectionEntries.");
        }
    }

    private void requireStringSize(String value) {
        if (value.length() > limits.maxStringLength()) {
            throw new A2ATransportException("A2A JSON string exceeds maxStringLength.");
        }
    }

    private static StateValue.ObjectValue requireObject(StateValue value, String name) {
        if (value instanceof StateValue.ObjectValue object) {
            return object;
        }
        throw protocol(A2AErrorCode.INVALID_PARAMS, name + " must be an object.");
    }

    private static StateValue require(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value == null) {
            throw protocol(A2AErrorCode.INVALID_PARAMS, "Required A2A member '" + name + "' is absent.");
        }
        return value;
    }

    private static String requireString(StateValue.ObjectValue object, String name) {
        return requireStringValue(require(object, name), name);
    }

    private static String requireStringValue(StateValue value, String name) {
        if (value instanceof StateValue.StringValue string) {
            return string.value();
        }
        throw protocol(A2AErrorCode.INVALID_PARAMS, name + " must be a string.");
    }

    private static List<StateValue> list(StateValue value, String name) {
        if (value instanceof StateValue.ArrayValue array) {
            return array.values();
        }
        throw protocol(A2AErrorCode.INVALID_PARAMS, name + " must be an array.");
    }

    private static List<String> stringList(StateValue value, String name) {
        return list(value, name).stream()
                .map(member -> requireStringValue(member, name + " entry"))
                .toList();
    }

    private static Optional<StateValue> optional(Map<String, StateValue> values, String name) {
        StateValue value = values.get(name);
        return value == null || value instanceof StateValue.NullValue ? Optional.empty() : Optional.of(value);
    }

    private static Optional<String> optionalString(Map<String, StateValue> values, String name) {
        return optional(values, name).map(value -> requireStringValue(value, name));
    }

    private static Optional<StateValue.ObjectValue> optionalObject(Map<String, StateValue> values, String name) {
        return optional(values, name).map(value -> requireObject(value, name));
    }

    private static Optional<StateValue.ArrayValue> optionalArray(Map<String, StateValue> values, String name) {
        return optional(values, name).map(value -> {
            if (value instanceof StateValue.ArrayValue array) {
                return array;
            }
            throw protocol(A2AErrorCode.INVALID_PARAMS, name + " must be an array.");
        });
    }

    private static Optional<Boolean> optionalBoolean(Map<String, StateValue> values, String name) {
        return optional(values, name).map(value -> {
            if (value instanceof StateValue.BooleanValue bool) {
                return bool.value();
            }
            throw protocol(A2AErrorCode.INVALID_PARAMS, name + " must be a Boolean.");
        });
    }

    private static Optional<Integer> optionalInt(Map<String, StateValue> values, String name) {
        return optional(values, name).map(value -> {
            if (value instanceof StateValue.NumberValue number) {
                try {
                    return number.value().intValueExact();
                } catch (ArithmeticException exception) {
                    throw protocol(A2AErrorCode.INVALID_PARAMS, name + " must be an integer in range.", exception);
                }
            }
            throw protocol(A2AErrorCode.INVALID_PARAMS, name + " must be a number.");
        });
    }

    private static Optional<Long> optionalLong(Map<String, StateValue> values, String name) {
        return optional(values, name).map(value -> {
            if (value instanceof StateValue.NumberValue number) {
                try {
                    return number.value().longValueExact();
                } catch (ArithmeticException exception) {
                    throw protocol(A2AErrorCode.INVALID_PARAMS, name + " must be an integer in range.", exception);
                }
            }
            throw protocol(A2AErrorCode.INVALID_PARAMS, name + " must be a number.");
        });
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String name) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw protocol(A2AErrorCode.INVALID_PARAMS, "Unknown " + name + " value '" + value + "'.", exception);
        }
    }

    private static StateValue.ArrayValue strings(List<String> values) {
        return array(values.stream().map(A2AJsonCodec::string).toList());
    }

    private static StateValue.ArrayValue array(List<? extends StateValue> values) {
        return StateValue.array(values);
    }

    private static StateValue.StringValue string(String value) {
        return StateValue.string(value);
    }

    private static StateValue.ObjectValue object(Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("Object pairs must contain names and values.");
        }
        LinkedHashMap<String, StateValue> values = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put((String) pairs[index], (StateValue) pairs[index + 1]);
        }
        return StateValue.object(values);
    }

    private static void put(Map<String, StateValue> values, String name, String value) {
        if (value != null) {
            values.put(name, string(value));
        }
    }

    private static void putUri(Map<String, StateValue> values, String name, URI value) {
        if (value != null) {
            put(values, name, value.toString());
        }
    }

    private static void putNumber(Map<String, StateValue> values, String name, Number value) {
        if (value != null) {
            if (value instanceof BigDecimal decimal) {
                values.put(name, StateValue.number(decimal));
            } else {
                values.put(name, StateValue.integer(value.longValue()));
            }
        }
    }

    private static void putMetadata(Map<String, StateValue> values, String name, Map<String, StateValue> metadata) {
        if (!metadata.isEmpty()) {
            values.put(name, StateValue.object(metadata));
        }
    }

    private static A2AProtocolException protocol(A2AErrorCode code, String message) {
        return new A2AProtocolException(code, message);
    }

    private static A2AProtocolException protocol(A2AErrorCode code, String message, Throwable cause) {
        return new A2AProtocolException(code, message, null, cause);
    }
}
