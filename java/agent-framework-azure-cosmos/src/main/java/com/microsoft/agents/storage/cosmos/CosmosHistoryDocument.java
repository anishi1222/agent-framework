// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

final class CosmosHistoryDocument {
    public String id;

    public String partitionKey;

    public String kind;

    public Integer schemaVersion;

    public Long sequence;

    public String operationId;

    public String messageId;

    public String payload;

    public String payloadDigest;

    public Integer ttl;

    public CosmosHistoryDocument() {}
}
