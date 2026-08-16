// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

import com.azure.cosmos.CosmosItemSerializer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Delegates SDK serialization while omitting null top-level properties such as Cosmos item TTL.
 *
 * <p>Nested null values remain intact, so framework metadata retains its JSON semantics.
 */
final class CosmosNullOmittingItemSerializer extends CosmosItemSerializer {
    @Override
    public <T> Map<String, Object> serialize(T item) {
        Map<String, Object> serialized = DEFAULT_SERIALIZER.serialize(item);
        LinkedHashMap<String, Object> withoutTopLevelNulls = new LinkedHashMap<>();
        serialized.forEach((key, value) -> {
            if (value != null) {
                withoutTopLevelNulls.put(key, value);
            }
        });
        return withoutTopLevelNulls;
    }

    @Override
    public <T> T deserialize(Map<String, Object> jsonNodeMap, Class<T> classType) {
        return DEFAULT_SERIALIZER.deserialize(jsonNodeMap, classType);
    }
}
