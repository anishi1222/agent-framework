// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.mcp;

import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.tools.FunctionTool;
import com.microsoft.agents.tools.ToolApprovalMode;
import com.microsoft.agents.tools.ToolCapability;
import com.microsoft.agents.tools.ToolMetadata;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Runs a real official-SDK stdio server for child-JVM smoke testing. */
public final class StdioSmokeServerMain {
    private StdioSmokeServerMain() {}

    /**
     * Starts the stdio server.
     *
     * @param arguments ignored
     * @throws Exception when the descendant process cannot start
     */
    public static void main(String[] arguments) throws Exception {
        Process descendant = new ProcessBuilder(
                        java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java")
                                .toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        StubbornChildMain.class.getName())
                .start();
        StateValue.ObjectValue output = StateValue.object(Map.of(
                "type",
                StateValue.string("object"),
                "properties",
                StateValue.object(Map.of(
                        "pid",
                        StateValue.object(Map.of("type", StateValue.string("integer"))),
                        "descendantPid",
                        StateValue.object(Map.of("type", StateValue.string("integer"))))),
                "required",
                StateValue.array(List.of(StateValue.string("pid"), StateValue.string("descendantPid"))),
                "additionalProperties",
                StateValue.bool(false)));
        FunctionTool processInfo = FunctionTool.create(
                new ToolMetadata(
                        "process_info",
                        "Returns the child server process identifier.",
                        Set.of(ToolCapability.FUNCTION),
                        ToolApprovalMode.NEVER_REQUIRE,
                        StateValue.object(Map.of(
                                "type", StateValue.string("object"), "additionalProperties", StateValue.bool(false))),
                        output),
                (context, toolArguments) -> CompletableFuture.completedFuture(StateValue.object(Map.of(
                        "pid",
                        StateValue.integer(ProcessHandle.current().pid()),
                        "descendantPid",
                        StateValue.integer(descendant.pid())))));
        MCPServerHandle handle = MCPServer.builder("stdio-smoke", "1.0.0")
                .tool(processInfo)
                .build()
                .startStdio();
        Runtime.getRuntime().addShutdownHook(new Thread(handle::close));
        System.err.println("stdio smoke server ready");
        new java.util.concurrent.CountDownLatch(1).await();
    }
}
