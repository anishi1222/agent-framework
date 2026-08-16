// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmosmemory;

import com.microsoft.agents.agents.memory.EmbeddingVector;
import com.microsoft.agents.core.ValidationException;

/**
 * Defines the immutable vector policy and index contract for a memory container.
 *
 * @param dimensions exact embedding dimensions
 * @param dataType persisted vector data type
 * @param distance distance function
 * @param indexType immutable vector index type
 */
public record CosmosMemoryVectorOptions(
        int dimensions, CosmosVectorDataType dataType, CosmosVectorDistance distance, CosmosVectorIndexType indexType) {
    /** Creates validated vector policy options. */
    public CosmosMemoryVectorOptions {
        if (dimensions <= 0 || dimensions > EmbeddingVector.MAX_DIMENSIONS) {
            throw new ValidationException("dimensions must be between 1 and " + EmbeddingVector.MAX_DIMENSIONS + ".");
        }
        if (dataType == null || distance == null || indexType == null) {
            throw new ValidationException("Vector dataType, distance, and indexType are required.");
        }
    }
}
