// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.declarative;

import com.microsoft.agents.tools.Tool;
import java.util.Map;
import java.util.Optional;

/** Resolves declarative tool references to caller-owned provider-neutral tools. */
@FunctionalInterface
public interface ToolRegistry {
    /**
     * Finds a tool by its logical name.
     *
     * @param name non-blank tool name
     * @return matching caller-owned tool, if registered
     */
    Optional<Tool> find(String name);

    /**
     * Creates an immutable registry from a map.
     *
     * @param tools logical names to caller-owned tools
     * @return immutable registry
     */
    static ToolRegistry of(Map<String, ? extends Tool> tools) {
        Map<String, Tool> copy = RegistrySupport.copy(tools, "tools");
        return name -> Optional.ofNullable(copy.get(RegistrySupport.key(name, "tool name")));
    }

    /**
     * Returns an empty registry.
     *
     * @return registry containing no tools
     */
    static ToolRegistry empty() {
        return of(Map.of());
    }
}
