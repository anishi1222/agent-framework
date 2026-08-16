// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmosmemory;

/** Selects the immutable Cosmos vector index type. */
public enum CosmosVectorIndexType {
    /** Exact flat vector index. */
    FLAT("flat"),
    /** Quantized flat vector index. */
    QUANTIZED_FLAT("quantizedFlat"),
    /** DiskANN approximate vector index. */
    DISK_ANN("diskANN");

    private final String value;

    CosmosVectorIndexType(String value) {
        this.value = value;
    }

    /**
     * Returns the stable policy value.
     *
     * @return Cosmos policy value
     */
    public String value() {
        return value;
    }
}
