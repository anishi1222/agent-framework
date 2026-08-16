// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

import com.microsoft.agents.tools.InvocationId;
import com.microsoft.agents.tools.InvocationLedgerEntry;
import com.microsoft.agents.tools.InvocationOutcome;
import com.microsoft.agents.tools.InvocationRecord;
import com.microsoft.agents.tools.ToolInvocationOutcome;
import com.microsoft.agents.tools.ToolInvocationResult;

final class CosmosLedgerCodec {
    private static final String KIND = "invocation-ledger";

    private static final int SCHEMA_VERSION = 1;

    CosmosLedgerDocument encode(
            InvocationLedgerEntry entry, long revision, String id, String partitionKey, Integer ttl) {
        requireString(entry.invocationId().value(), "invocationId");
        requireString(entry.requestDigest(), "requestDigest");
        CosmosLedgerDocument document = new CosmosLedgerDocument();
        document.id = id;
        document.partitionKey = partitionKey;
        document.kind = KIND;
        document.schemaVersion = SCHEMA_VERSION;
        document.revision = revision;
        document.invocationId = entry.invocationId().value();
        document.requestDigest = entry.requestDigest();
        document.ttl = ttl;
        if (entry instanceof InvocationRecord pending) {
            requireString(pending.logicalRunId(), "logicalRunId");
            requireString(pending.callId(), "callId");
            requireString(pending.toolName(), "toolName");
            document.entryKind = "pending";
            document.logicalRunId = pending.logicalRunId();
            document.callId = pending.callId();
            document.toolName = pending.toolName();
        } else if (entry instanceof InvocationOutcome terminal) {
            requireString(terminal.result().callId(), "callId");
            if (terminal.result().error() != null) {
                requireString(terminal.result().error(), "error");
            }
            document.entryKind = "outcome";
            document.callId = terminal.result().callId();
            document.outcome = terminal.result().outcome().name();
            document.value = CosmosStateValueMapper.toObject(terminal.result().value());
            document.error = terminal.result().error();
        } else {
            throw new IllegalArgumentException("Unsupported invocation-ledger entry type.");
        }
        return document;
    }

    InvocationLedgerEntry decode(CosmosLedgerDocument document, String expectedId, String expectedPartitionKey) {
        if (document == null
                || !expectedId.equals(document.id)
                || !expectedPartitionKey.equals(document.partitionKey)
                || !KIND.equals(document.kind)
                || document.schemaVersion == null
                || document.schemaVersion != SCHEMA_VERSION
                || document.revision == null
                || document.revision <= 0
                || document.invocationId == null
                || document.requestDigest == null
                || document.entryKind == null) {
            throw incompatible();
        }
        requireString(document.invocationId, "invocationId");
        requireString(document.requestDigest, "requestDigest");
        InvocationId invocationId = new InvocationId(document.invocationId);
        if ("pending".equals(document.entryKind)) {
            if (document.logicalRunId == null || document.callId == null || document.toolName == null) {
                throw incompatible();
            }
            requireString(document.logicalRunId, "logicalRunId");
            requireString(document.callId, "callId");
            requireString(document.toolName, "toolName");
            return new InvocationRecord(
                    invocationId, document.logicalRunId, document.callId, document.toolName, document.requestDigest);
        }
        if (!"outcome".equals(document.entryKind) || document.callId == null || document.outcome == null) {
            throw incompatible();
        }
        requireString(document.callId, "callId");
        if (document.error != null) {
            requireString(document.error, "error");
        }
        ToolInvocationOutcome outcome;
        try {
            outcome = ToolInvocationOutcome.valueOf(document.outcome);
        } catch (IllegalArgumentException exception) {
            throw incompatible();
        }
        ToolInvocationResult result = new ToolInvocationResult(
                invocationId,
                document.callId,
                outcome,
                CosmosStateValueMapper.fromObject(document.value),
                document.error);
        return new InvocationOutcome(invocationId, document.requestDigest, result);
    }

    private static CosmosStorageException incompatible() {
        return new CosmosStorageException(
                "Stored Cosmos invocation-ledger document is malformed or incompatible.",
                null,
                CosmosStorageException.Kind.INCOMPATIBLE_RESOURCE,
                null);
    }

    private static void requireString(String value, String name) {
        if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 250_000) {
            throw new CosmosStorageException(
                    "Cosmos invocation-ledger " + name + " exceeds the maximum string bytes.",
                    null,
                    CosmosStorageException.Kind.INCOMPATIBLE_RESOURCE,
                    null);
        }
    }
}
