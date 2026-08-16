// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmosmemory;

/** Selects the persisted Cosmos vector element type. */
public enum CosmosVectorDataType {
    /** 32-bit floating point values. */
    FLOAT32("float32");

    private final String value;

    CosmosVectorDataType(String value) {
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
