// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

final class CosmosCheckpointDocument {
    public String id;

    public String partitionKey;

    public String kind;

    public Integer schemaVersion;

    public String checkpointKey;

    public String workflowId;

    public String checkpointId;

    public Long revision;

    public String snapshotSortKey;

    public String payload;

    public String payloadDigest;

    public Integer ttl;

    public Long _ts;

    public CosmosCheckpointDocument() {}
}
