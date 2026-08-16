// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp.skills;

import com.microsoft.agents.agents.skills.Skill;
import com.microsoft.agents.agents.skills.SkillFrontmatter;
import com.microsoft.agents.agents.skills.SkillResource;
import com.microsoft.agents.agents.skills.SkillScript;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunHandles;
import com.microsoft.agents.protocols.mcp.MCPReadResourceResult;
import com.microsoft.agents.protocols.mcp.MCPResourceContents;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Represents one lazily loaded {@code skill-md} MCP index entry. */
public final class MCPSkill implements Skill {
    private final SkillFrontmatter frontmatter;
    private final URI skillMdUri;
    private final URI rootUri;
    private final MCPResourceReader reader;
    private volatile String cachedContent;

    /**
     * Creates an MCP skill.
     *
     * @param frontmatter index discovery metadata
     * @param skillMdUri absolute {@code SKILL.md} resource URI
     * @param reader MCP resource reader
     */
    public MCPSkill(SkillFrontmatter frontmatter, URI skillMdUri, MCPResourceReader reader) {
        this.frontmatter = Objects.requireNonNull(frontmatter, "frontmatter");
        this.skillMdUri = requireAbsolute(skillMdUri);
        this.rootUri = root(this.skillMdUri);
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    @Override
    public SkillFrontmatter frontmatter() {
        return frontmatter;
    }

    /**
     * Returns the indexed skill document URI.
     *
     * @return absolute skill document URI
     */
    public URI skillMdUri() {
        return skillMdUri;
    }

    @Override
    public CompletionStage<String> contentAsync(RunCancellation cancellation) {
        Objects.requireNonNull(cancellation, "cancellation");
        String content = cachedContent;
        if (content != null) {
            return CompletableFuture.completedFuture(content);
        }
        return reader.readAsync(skillMdUri, cancellation).thenApply(result -> {
            String loaded = text(result);
            synchronized (this) {
                if (cachedContent == null) {
                    cachedContent = loaded;
                }
                return cachedContent;
            }
        });
    }

    @Override
    public CompletionStage<SkillResource> resourceAsync(String name, RunCancellation cancellation) {
        Objects.requireNonNull(cancellation, "cancellation");
        if (!safeRelative(name)) {
            return CompletableFuture.completedFuture(null);
        }
        String normalized = name.replace('\\', '/');
        URI uri = resolve(normalized);
        return reader.readAsync(uri, cancellation).handle((result, failure) -> {
            if (failure != null) {
                if (MCPSkillErrors.isNotFound(failure)) {
                    return null;
                }
                Throwable cause = RunHandles.unwrap(failure);
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new java.util.concurrent.CompletionException(cause);
            }
            com.microsoft.agents.agents.skills.SkillResourceContent resourceContent = MCPSkillResource.content(result);
            return resourceContent == null ? null : new MCPSkillResource(normalized, uri, resourceContent);
        });
    }

    @Override
    public CompletionStage<SkillScript> scriptAsync(String name, RunCancellation cancellation) {
        Objects.requireNonNull(cancellation, "cancellation");
        return CompletableFuture.completedFuture(null);
    }

    private URI resolve(String name) {
        String normalized = name.replace('\\', '/');
        try {
            return new URI(rootUri.getScheme(), rootUri.getAuthority(), rootUri.getPath() + normalized, null, null);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid MCP skill resource name.", exception);
        }
    }

    private static String text(MCPReadResourceResult result) {
        String value = result.contents().stream()
                .filter(MCPResourceContents.Text.class::isInstance)
                .map(MCPResourceContents.Text.class::cast)
                .map(MCPResourceContents.Text::text)
                .collect(java.util.stream.Collectors.joining("\n"));
        if (value.isEmpty()) {
            throw new IllegalStateException("MCP skill content is empty.");
        }
        return value;
    }

    private static boolean safeRelative(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String normalized = name.replace('\\', '/');
        return !normalized.startsWith("/")
                && !normalized.contains("://")
                && Arrays.stream(normalized.split("/"))
                        .noneMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."));
    }

    private static URI root(URI uri) {
        String value = uri.toString();
        int separator = value.lastIndexOf('/');
        return URI.create(separator < 0 ? value + "/" : value.substring(0, separator + 1));
    }

    private static URI requireAbsolute(URI uri) {
        Objects.requireNonNull(uri, "skillMdUri");
        if (!uri.isAbsolute()) {
            throw new IllegalArgumentException("skillMdUri must be absolute.");
        }
        return uri;
    }
}
