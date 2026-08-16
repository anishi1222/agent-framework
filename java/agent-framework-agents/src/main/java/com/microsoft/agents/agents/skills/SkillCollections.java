// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import com.microsoft.agents.core.RunCancellation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class SkillCollections {
    private SkillCollections() {}

    static List<SkillResource> resources(List<? extends SkillResource> resources) {
        Objects.requireNonNull(resources, "resources");
        LinkedHashMap<String, SkillResource> byName = new LinkedHashMap<>();
        for (SkillResource resource : resources) {
            Objects.requireNonNull(resource, "resource");
            String key = resource.name().toLowerCase(Locale.ROOT);
            if (byName.putIfAbsent(key, resource) != null) {
                throw new IllegalArgumentException("Duplicate skill resource name '" + resource.name() + "'.");
            }
        }
        return List.copyOf(byName.values());
    }

    static List<SkillScript> scripts(List<? extends SkillScript> scripts) {
        Objects.requireNonNull(scripts, "scripts");
        LinkedHashMap<String, SkillScript> byName = new LinkedHashMap<>();
        for (SkillScript script : scripts) {
            Objects.requireNonNull(script, "script");
            String key = script.name().toLowerCase(Locale.ROOT);
            if (byName.putIfAbsent(key, script) != null) {
                throw new IllegalArgumentException("Duplicate skill script name '" + script.name() + "'.");
            }
        }
        return List.copyOf(byName.values());
    }

    static CompletionStage<SkillResource> resource(
            List<SkillResource> resources, String name, RunCancellation cancellation) {
        SkillValidation.requireActive(cancellation);
        if (name == null || name.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        String key = name.toLowerCase(Locale.ROOT);
        return CompletableFuture.completedFuture(resources.stream()
                .filter(resource -> resource.name().toLowerCase(Locale.ROOT).equals(key))
                .findFirst()
                .orElse(null));
    }

    static CompletionStage<SkillScript> script(List<SkillScript> scripts, String name, RunCancellation cancellation) {
        SkillValidation.requireActive(cancellation);
        if (name == null || name.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        String key = name.toLowerCase(Locale.ROOT);
        return CompletableFuture.completedFuture(scripts.stream()
                .filter(script -> script.name().toLowerCase(Locale.ROOT).equals(key))
                .findFirst()
                .orElse(null));
    }
}
