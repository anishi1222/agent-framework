// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Child CLI server fixture consumed exclusively through the official Java SDK.
 */
public final class FakeCopilotCliMain {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final List<ObjectNode> EVENTS = new ArrayList<>();

    private static OutputStream output;

    private static String pending;

    private static String pendingSession;

    private FakeCopilotCliMain() {}

    /**
     * Runs the fake CLI.
     *
     * @param arguments first argument is the protocol version
     * @throws Exception on I/O failure
     */
    public static void main(String[] arguments) throws Exception {
        int protocol = Integer.parseInt(arguments[0]);
        output = System.out;
        try (InputStream input = new BufferedInputStream(System.in)) {
            JsonNode message;
            while ((message = readMessage(input)) != null) {
                if (!message.has("method")) {
                    handleClientResponse(message);
                    continue;
                }
                String method = message.path("method").asText();
                JsonNode id = message.get("id");
                JsonNode params = message.path("params");
                switch (method) {
                    case "connect" ->
                        response(
                                id,
                                JSON.createObjectNode()
                                        .put("ok", true)
                                        .put("protocolVersion", protocol)
                                        .put("version", "1.0.79"));
                    case "session.create" -> {
                        String sessionId = params.path("sessionId").asText("fake-session");
                        response(
                                id,
                                JSON.createObjectNode()
                                        .put("sessionId", sessionId)
                                        .put("workspacePath", "/tmp/fake-copilot"));
                        lifecycle(sessionId, "session.created");
                    }
                    case "session.resume" ->
                        response(
                                id,
                                JSON.createObjectNode()
                                        .put(
                                                "sessionId",
                                                params.path("sessionId").asText())
                                        .put("workspacePath", "/tmp/fake-copilot"));
                    case "session.send" -> handleSend(id, params);
                    case "session.getMessages" -> {
                        ArrayNode events = JSON.createArrayNode();
                        EVENTS.forEach(events::add);
                        response(id, JSON.createObjectNode().set("events", events));
                    }
                    case "session.abort" -> {
                        response(id, JSON.nullNode());
                        emit(
                                params.path("sessionId").asText(),
                                event("session.idle")
                                        .set("data", JSON.createObjectNode().put("aborted", true)));
                    }
                    case "session.list" -> {
                        boolean matches = !params.has("filter")
                                || "owner/repo"
                                        .equals(params.path("filter")
                                                .path("repository")
                                                .asText("owner/repo"));
                        response(
                                id,
                                JSON.createObjectNode()
                                        .set(
                                                "sessions",
                                                matches
                                                        ? JSON.createArrayNode().add(sessionMetadata())
                                                        : JSON.createArrayNode()));
                    }
                    case "session.getMetadata" ->
                        response(id, JSON.createObjectNode().set("session", sessionMetadata()));
                    case "session.delete" -> {
                        response(id, JSON.createObjectNode().put("success", true));
                        lifecycle(params.path("sessionId").asText(), "session.deleted");
                    }
                    case "models.list" -> {
                        response(
                                id,
                                JSON.createObjectNode()
                                        .set("models", JSON.createArrayNode().add(model())));
                    }
                    case "session.model.switchTo", "session.log", "session.compaction.compact" ->
                        response(id, JSON.nullNode());
                    case "session.options.update" ->
                        response(
                                id, JSON.createObjectNode().put("success", true).put("pluginHookCount", 0));
                    case "session.destroy" -> {
                        response(id, JSON.nullNode());
                        String sessionId = params.path("sessionId").asText("fake-session");
                        Thread.ofVirtual().start(() -> {
                            try {
                                Thread.sleep(100);
                                emitTurn(sessionId, "late-event", false);
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                            }
                        });
                    }
                    case "runtime.shutdown" -> {
                        response(id, JSON.nullNode());
                        return;
                    }
                    default -> error(id, -32601, "Unsupported fake CLI method: " + method);
                }
            }
        }
    }

    private static void handleSend(JsonNode id, JsonNode params) {
        String sessionId = params.path("sessionId").asText();
        String prompt = params.path("prompt").asText();
        response(id, JSON.createObjectNode().put("messageId", "user-" + EVENTS.size()));
        ObjectNode user = event("user.message");
        user.set("data", JSON.createObjectNode().put("content", prompt).put("source", "user"));
        emit(sessionId, user);
        if (prompt.contains("block")) {
            // Remain active until the official SDK sends session.abort.
        } else if (prompt.contains("permission")) {
            pending = "permission";
            pendingSession = sessionId;
            request(
                    900,
                    "permission.request",
                    JSON.createObjectNode()
                            .put("sessionId", sessionId)
                            .set(
                                    "permissionRequest",
                                    JSON.createObjectNode()
                                            .put("kind", "shell")
                                            .put("toolCallId", "permission-call")
                                            .put("managedApprovalRequired", false)));
        } else if (prompt.contains("input")) {
            pending = "input";
            pendingSession = sessionId;
            request(
                    901,
                    "userInput.request",
                    JSON.createObjectNode()
                            .put("sessionId", sessionId)
                            .put("question", "Continue?")
                            .put("allowFreeform", true)
                            .set("choices", JSON.createArrayNode().add("yes").add("no")));
        } else if (prompt.contains("tool")) {
            pending = "tool";
            pendingSession = sessionId;
            request(
                    902,
                    "tool.call",
                    JSON.createObjectNode()
                            .put("sessionId", sessionId)
                            .put("toolCallId", "tool-call-1")
                            .put("toolName", "echo")
                            .set("arguments", JSON.createObjectNode().put("value", "from-fake")));
        } else if (prompt.contains("hook")) {
            pending = "hook";
            pendingSession = sessionId;
            request(
                    903,
                    "hooks.invoke",
                    JSON.createObjectNode()
                            .put("sessionId", sessionId)
                            .put("hookType", "preToolUse")
                            .set(
                                    "input",
                                    JSON.createObjectNode()
                                            .put("sessionId", sessionId)
                                            .put("timestamp", System.currentTimeMillis())
                                            .put("cwd", "/tmp")
                                            .put("toolName", "shell")
                                            .set(
                                                    "toolArgs",
                                                    JSON.createObjectNode().put("command", "echo"))));
        } else if (prompt.contains("overflow")) {
            for (int index = 0; index < 64; index++) {
                ObjectNode delta = event("assistant.message_delta");
                delta.set(
                        "data",
                        JSON.createObjectNode()
                                .put("messageId", "overflow-message")
                                .put("deltaContent", Integer.toString(index)));
                emit(sessionId, delta);
            }
            emitTurn(sessionId, "overflow-complete", false);
        } else if (prompt.contains("malformed")) {
            emitTurn(sessionId, "x".repeat(4096), false);
        } else {
            emitTurn(sessionId, "answer:" + prompt, false);
        }
    }

    private static void handleClientResponse(JsonNode message) {
        if (pending == null) {
            return;
        }
        String content;
        if ("permission".equals(pending)) {
            content = "permission:"
                    + message.path("result").path("result").path("kind").asText();
        } else if ("input".equals(pending)) {
            content = "input:" + message.path("result").path("answer").asText();
        } else if ("tool".equals(pending)) {
            JsonNode result = message.path("result").path("result");
            content = "tool:" + result.path("content").asText(result.toString());
            ObjectNode start = event("tool.execution_start");
            start.set(
                    "data",
                    JSON.createObjectNode()
                            .put("toolCallId", "tool-call-1")
                            .put("toolName", "echo")
                            .put("model", "fake-model")
                            .set("arguments", JSON.createObjectNode().put("value", "from-fake")));
            emit(pendingSession, start);
            ObjectNode complete = event("tool.execution_complete");
            complete.set(
                    "data",
                    JSON.createObjectNode()
                            .put("toolCallId", "tool-call-1")
                            .put("success", true)
                            .put("model", "fake-model")
                            .set("result", JSON.createObjectNode().put("content", content)));
            emit(pendingSession, complete);
        } else {
            content = "hook:"
                    + message.path("result")
                            .path("output")
                            .path("permissionDecision")
                            .asText("unchanged");
        }
        String sessionId = pendingSession;
        pending = null;
        pendingSession = null;
        emitTurn(sessionId, content, false);
    }

    private static void emitTurn(String sessionId, String content, boolean aborted) {
        String messageId = "assistant-" + EVENTS.size();
        ObjectNode delta = event("assistant.message_delta");
        delta.set("data", JSON.createObjectNode().put("messageId", messageId).put("deltaContent", content));
        emit(sessionId, delta);
        ObjectNode message = event("assistant.message");
        message.set(
                "data",
                JSON.createObjectNode()
                        .put("messageId", messageId)
                        .put("model", "fake-model")
                        .put("content", content));
        emit(sessionId, message);
        ObjectNode usage = event("assistant.usage");
        usage.set(
                "data",
                JSON.createObjectNode()
                        .put("model", "fake-model")
                        .put("inputTokens", 4)
                        .put("outputTokens", 2));
        emit(sessionId, usage);
        ObjectNode idle = event("session.idle");
        idle.set("data", JSON.createObjectNode().put("aborted", aborted));
        emit(sessionId, idle);
    }

    private static ObjectNode event(String type) {
        return JSON.createObjectNode()
                .put("id", UUID.randomUUID().toString())
                .put("timestamp", Instant.now().toString())
                .put("type", type);
    }

    private static ObjectNode sessionMetadata() {
        ObjectNode metadata = JSON.createObjectNode()
                .put("sessionId", "fake-session")
                .put("startTime", "2026-08-12T00:00:00Z")
                .put("modifiedTime", "2026-08-12T00:01:00Z")
                .put("summary", "fake");
        metadata.set(
                "context",
                JSON.createObjectNode()
                        .put("cwd", "/tmp")
                        .put("gitRoot", "/tmp")
                        .put("repository", "owner/repo")
                        .put("branch", "main"));
        return metadata;
    }

    private static ObjectNode model() {
        ObjectNode model = JSON.createObjectNode()
                .put("id", "fake-model")
                .put("name", "Fake Model")
                .put("defaultReasoningEffort", "medium");
        model.set("supportedReasoningEfforts", JSON.createArrayNode().add("low").add("medium"));
        ObjectNode limits =
                JSON.createObjectNode().put("max_prompt_tokens", 4096).put("max_context_window_tokens", 8192);
        limits.set(
                "vision",
                JSON.createObjectNode()
                        .set("supported_media_types", JSON.createArrayNode().add("image/png")));
        ((ObjectNode) limits.path("vision")).put("max_prompt_images", 2).put("max_prompt_image_size", 1024);
        ObjectNode capabilities = JSON.createObjectNode();
        capabilities.set("supports", JSON.createObjectNode().put("vision", true).put("reasoningEffort", true));
        capabilities.set("limits", limits);
        model.set("capabilities", capabilities);
        model.set(
                "billing",
                JSON.createObjectNode()
                        .put("multiplier", 1.5)
                        .set(
                                "tokenPrices",
                                JSON.createObjectNode().put("inputPrice", 0.1).put("outputPrice", 0.2)));
        return model;
    }

    private static void emit(String sessionId, ObjectNode event) {
        EVENTS.add(event.deepCopy());
        ObjectNode message = JSON.createObjectNode().put("jsonrpc", "2.0").put("method", "session.event");
        message.set(
                "params", JSON.createObjectNode().put("sessionId", sessionId).set("event", event));
        writeMessage(message);
    }

    private static void request(long id, String method, ObjectNode params) {
        ObjectNode request =
                JSON.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
        request.set("params", params);
        writeMessage(request);
    }

    private static void lifecycle(String sessionId, String type) {
        ObjectNode notification = JSON.createObjectNode().put("jsonrpc", "2.0").put("method", "session.lifecycle");
        notification.set(
                "params",
                JSON.createObjectNode()
                        .put("type", type)
                        .put("sessionId", sessionId)
                        .set(
                                "metadata",
                                JSON.createObjectNode()
                                        .put("startTime", "2026-08-12T00:00:00Z")
                                        .put("modifiedTime", "2026-08-12T00:01:00Z")
                                        .put("summary", "fake")));
        writeMessage(notification);
    }

    private static void response(JsonNode id, JsonNode result) {
        ObjectNode response = JSON.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        writeMessage(response);
    }

    private static void error(JsonNode id, int code, String message) {
        ObjectNode response = JSON.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("error", JSON.createObjectNode().put("code", code).put("message", message));
        writeMessage(response);
    }

    private static JsonNode readMessage(InputStream input) throws IOException {
        int contentLength = -1;
        StringBuilder line = new StringBuilder();
        while (true) {
            int value = input.read();
            if (value < 0) {
                return null;
            }
            if (value == '\r') {
                continue;
            }
            if (value == '\n') {
                if (line.isEmpty()) {
                    break;
                }
                String header = line.toString();
                line.setLength(0);
                if (header.regionMatches(true, 0, "Content-Length:", 0, "Content-Length:".length())) {
                    contentLength = Integer.parseInt(
                            header.substring("Content-Length:".length()).strip());
                }
            } else {
                line.append((char) value);
            }
        }
        if (contentLength < 0) {
            throw new IOException("Missing Content-Length.");
        }
        byte[] body = input.readNBytes(contentLength);
        if (body.length != contentLength) {
            throw new IOException("Truncated JSON-RPC frame.");
        }
        return JSON.readTree(body);
    }

    private static synchronized void writeMessage(JsonNode message) {
        try {
            byte[] body = JSON.writeValueAsBytes(message);
            byte[] header =
                    ("Content-Length: " + body.length + "\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            output.write(header);
            output.write(body);
            output.flush();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
