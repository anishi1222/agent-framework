// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.githubcopilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Child-JVM loopback CLI used to validate the hardened process launcher.
 */
public final class FakeCopilotTcpCliMain {
    private static final ObjectMapper JSON = new ObjectMapper();

    private FakeCopilotTcpCliMain() {}

    /**
     * Runs the loopback fake.
     *
     * @param arguments protocol version followed by managed CLI arguments
     * @throws Exception on transport failure
     */
    public static void main(String[] arguments) throws Exception {
        if ("descendant".equals(arguments[0])) {
            Thread.sleep(Duration.ofMinutes(5));
            return;
        }
        int protocol = Integer.parseInt(arguments[0]);
        int port = argument(arguments, "--port");
        String token = System.getenv("COPILOT_SDK_AUTH_TOKEN");
        System.err.println("token=" + token);
        System.err.println("diagnostic=" + "x".repeat(512));
        Process descendant = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        FakeCopilotTcpCliMain.class.getName(),
                        "descendant")
                .start();
        System.err.println("descendantPid=" + descendant.pid());
        try (ServerSocket server = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
            System.out.println("listening on port " + server.getLocalPort());
            System.out.flush();
            try (Socket socket = server.accept();
                    InputStream input = new BufferedInputStream(socket.getInputStream());
                    OutputStream output = socket.getOutputStream()) {
                JsonNode request;
                while ((request = readMessage(input)) != null) {
                    ObjectNode response = JSON.createObjectNode().put("jsonrpc", "2.0");
                    response.set("id", request.get("id"));
                    if ("connect".equals(request.path("method").asText())) {
                        response.set(
                                "result",
                                JSON.createObjectNode()
                                        .put("ok", true)
                                        .put("protocolVersion", protocol)
                                        .put("version", "fake"));
                    } else {
                        response.set("result", JSON.nullNode());
                    }
                    writeMessage(output, response);
                }
            }
        }
    }

    private static int argument(String[] arguments, String name) {
        for (int index = 0; index + 1 < arguments.length; index++) {
            if (name.equals(arguments[index])) {
                return Integer.parseInt(arguments[index + 1]);
            }
        }
        throw new IllegalArgumentException("Missing " + name);
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
        return body.length == contentLength ? JSON.readTree(body) : null;
    }

    private static void writeMessage(OutputStream output, JsonNode message) throws IOException {
        byte[] body = JSON.writeValueAsBytes(message);
        output.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(body);
        output.flush();
    }
}
