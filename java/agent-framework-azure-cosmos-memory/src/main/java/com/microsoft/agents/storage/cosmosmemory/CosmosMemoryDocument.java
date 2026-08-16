// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmosmemory;

import java.util.List;
import java.util.Map;

final class CosmosMemoryDocument {
    public String id;

    public String partitionKey;

    public String kind;

    public Integer schemaVersion;

    public Long revision;

    public String tenantDigest;

    public String scopeDigest;

    public String memoryId;

    public String content;

    public Map<String, Object> metadata;

    public List<Map<String, Object>> metadataPairs;

    public List<Double> vector;

    public Integer vectorDimensions;

    public String vectorDataType;

    public String vectorIndexType;

    public String createdAt;

    public String updatedAt;

    public String payloadDigest;

    public Integer ttl;

    public CosmosMemoryDocument() {}
}
