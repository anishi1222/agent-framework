// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

final class CosmosHistoryHeadDocument {
    public String id;

    public String partitionKey;

    public String kind;

    public Integer schemaVersion;

    public Long nextSequence;

    public CosmosHistoryHeadDocument() {}
}
