// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.declarative;

import com.microsoft.agents.agents.ChatClient;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Resolves logical provider keys to caller-owned provider-neutral chat clients. */
@FunctionalInterface
public interface ChatClientRegistry {
    /**
     * Finds a chat client by its logical provider key.
     *
     * @param key non-blank provider key
     * @return matching caller-owned chat client, if registered
     */
    Optional<ChatClient> find(String key);

    /**
     * Creates an immutable registry from a map.
     *
     * @param clients logical provider keys to caller-owned chat clients
     * @return immutable registry
     */
    static ChatClientRegistry of(Map<String, ? extends ChatClient> clients) {
        Map<String, ChatClient> copy = RegistrySupport.copy(clients, "chatClients");
        return key -> Optional.ofNullable(copy.get(RegistrySupport.key(key, "chat client key")));
    }

    /**
     * Creates a registry that returns one caller-owned client for every key.
     *
     * @param client caller-owned chat client
     * @return fixed registry
     */
    static ChatClientRegistry fixed(ChatClient client) {
        ChatClient checked = Objects.requireNonNull(client, "client");
        return key -> {
            RegistrySupport.key(key, "chat client key");
            return Optional.of(checked);
        };
    }

    /**
     * Returns an empty registry.
     *
     * @return registry containing no chat clients
     */
    static ChatClientRegistry empty() {
        return of(Map.of());
    }
}
