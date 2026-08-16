// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

final class CosmosLedgerDocument {
    public String id;

    public String partitionKey;

    public String kind;

    public Integer schemaVersion;

    public Long revision;

    public String entryKind;

    public String invocationId;

    public String requestDigest;

    public String logicalRunId;

    public String callId;

    public String toolName;

    public String outcome;

    public Object value;

    public String error;

    public Integer ttl;

    public CosmosLedgerDocument() {}
}
