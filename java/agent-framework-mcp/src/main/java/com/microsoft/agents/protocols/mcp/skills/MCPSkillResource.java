// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp.skills;

import com.microsoft.agents.agents.skills.SkillResource;
import com.microsoft.agents.agents.skills.SkillResourceContent;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.protocols.mcp.MCPReadResourceResult;
import com.microsoft.agents.protocols.mcp.MCPResourceContents;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Represents one eagerly resolved and immutable MCP skill resource. */
public final class MCPSkillResource implements SkillResource {
    private final String name;
    private final URI uri;
    private final SkillResourceContent content;

    MCPSkillResource(String name, URI uri, SkillResourceContent content) {
        this.name = name;
        this.uri = Objects.requireNonNull(uri, "uri");
        this.content = Objects.requireNonNull(content, "content");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return null;
    }

    /**
     * Returns the resolved MCP resource URI.
     *
     * @return resource URI
     */
    public URI uri() {
        return uri;
    }

    @Override
    public CompletionStage<SkillResourceContent> readAsync(RunCancellation cancellation) {
        Objects.requireNonNull(cancellation, "cancellation");
        if (cancellation.isCancellationRequested()) {
            return CompletableFuture.failedFuture(new RunCancelledException());
        }
        return CompletableFuture.completedFuture(content);
    }

    static SkillResourceContent content(MCPReadResourceResult result) {
        for (MCPResourceContents value : result.contents()) {
            if (value instanceof MCPResourceContents.Binary binary) {
                return new SkillResourceContent.Binary(binary.data());
            }
        }
        String text = result.contents().stream()
                .filter(MCPResourceContents.Text.class::isInstance)
                .map(MCPResourceContents.Text.class::cast)
                .map(MCPResourceContents.Text::text)
                .collect(java.util.stream.Collectors.joining("\n"));
        return text.isEmpty() ? null : new SkillResourceContent.Text(text);
    }
}
