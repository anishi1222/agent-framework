// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

final class CosmosCheckpointPurgeRow {
    public String id;

    public String kind;

    public String checkpointKey;

    public String etag;

    public CosmosCheckpointPurgeRow() {}
}
