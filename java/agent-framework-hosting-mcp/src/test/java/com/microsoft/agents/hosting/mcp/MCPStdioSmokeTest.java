// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.protocols.mcp.MCPClient;
import com.microsoft.agents.protocols.mcp.MCPStdioTransport;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.SAME_THREAD)
@Tag("stdio-smoke")
class MCPStdioSmokeTest {
    @Test
    void childJvmRoundTripAndCloseTerminatesProcess() throws Exception {
        // Arrange
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        MCPStdioTransport transport = MCPStdioTransport.builder(java.toString())
                .arguments(
                        List.of("-cp", System.getProperty("mcp.test.classpath"), StdioSmokeServerMain.class.getName()))
                .shutdownTimeout(Duration.ofSeconds(2))
                .build();
        long pid;
        long descendantPid;

        // Act
        MCPClient client = MCPClient.create(transport);
        try {
            client.initializeAsync().toCompletableFuture().get(10, TimeUnit.SECONDS);
            var result = client.callToolAsync("process_info", StateValue.object(Map.of()))
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);
            StateValue value = ((StateValue.ObjectValue) result.structuredContent())
                    .values()
                    .get("pid");
            pid = ((StateValue.NumberValue) value).value().longValueExact();
            StateValue descendantValue = ((StateValue.ObjectValue) result.structuredContent())
                    .values()
                    .get("descendantPid");
            descendantPid = ((StateValue.NumberValue) descendantValue).value().longValueExact();
            assertThat(ProcessHandle.of(pid)).isPresent();
            assertThat(ProcessHandle.of(descendantPid)).isPresent();
        } finally {
            client.close();
        }

        // Assert
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
                .isFalse();
        assertThat(ProcessHandle.of(descendantPid).map(ProcessHandle::isAlive).orElse(false))
                .isFalse();
    }
}
