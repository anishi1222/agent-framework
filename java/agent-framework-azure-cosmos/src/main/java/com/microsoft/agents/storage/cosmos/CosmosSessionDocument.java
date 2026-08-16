// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

final class CosmosSessionDocument {
    public String id;

    public String partitionKey;

    public String kind;

    public Integer schemaVersion;

    public Long revision;

    public Boolean deleted;

    public String payload;

    public String payloadDigest;

    public Integer ttl;

    public CosmosSessionDocument() {}
}
