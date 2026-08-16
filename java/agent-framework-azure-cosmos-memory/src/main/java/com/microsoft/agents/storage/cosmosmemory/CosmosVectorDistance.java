// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmosmemory;

/** Selects the vector similarity distance function. */
public enum CosmosVectorDistance {
    /** Cosine distance. */
    COSINE,
    /** Dot-product distance. */
    DOT_PRODUCT,
    /** Euclidean distance. */
    EUCLIDEAN
}
