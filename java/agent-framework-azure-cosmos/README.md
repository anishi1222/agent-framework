# Microsoft Agent Framework Azure Cosmos DB for Java

`com.microsoft.agents:agent-framework-azure-cosmos` provides durable Azure Cosmos DB for NoSQL
implementations of the Java `SessionStore`, `HistoryProvider`, and workflow `CheckpointStorage`
contracts. It uses the stable Azure Cosmos DB Java SDK v4
`com.azure:azure-cosmos:4.81.0` and requires Java 25 without preview APIs.

The public API contains only Agent Framework and JDK types. Azure SDK, Reactor, and Jackson types are
implementation details.

## Authentication and client ownership

Cosmos data-plane RBAC is preferred. The Azure authentication bridge can use a production-constrained
default credential or managed identity:

```java
CosmosClientOptions client = new CosmosClientOptions(
        CosmosEndpoint.parse("https://my-account.documents.azure.com/"),
        CosmosAuthentication.rbac(AzureAuthenticationProviders.productionDefaultCredential()),
        CosmosRetryOptions.defaults(),
        CosmosConnectionMode.DIRECT,
        "my-agent-app");
```

Account keys are supported only through `CosmosAccountKey`, whose `toString()` is always redacted.
Do not put keys in source code. The public factories create and own one reusable async Cosmos client;
closing a store closes only its owned client. Package-private injection seams used by tests never
expose the SDK in public signatures.

## Container and provisioning

All module documents require the logical partition path **`/partitionKey`**. Provisioning is
disabled by default:

```java
CosmosContainerOptions container = new CosmosContainerOptions(
        "agents",
        "framework-state",
        CosmosProvisioningOptions.disabled());
CosmosStorageOptions storage = new CosmosStorageOptions(
        client,
        container,
        new CosmosPartitionContext("tenant-42", "principal-17", "agent-support"),
        1_800_000,
        100,
        16);
```

When provisioning is enabled, the adapter creates missing database/container resources, then reads
the effective container and validates `/partitionKey`, automatic indexing, and TTL. It never replaces
or silently mutates an incompatible existing container. Resource deletion is always application
owned.

## Data and partition model

Raw tenant, principal, agent, session, and workflow identifiers are length-prefixed and SHA-256
normalized into URL-safe, bounded partition/item identifiers. This prevents delimiter collisions and
Cosmos ID/partition injection.

| Capability | Logical partition input | Documents |
|---|---|---|
| Session | tenant + isolation + agent + session key | `agent-session` |
| History | tenant + isolation + agent + session ID | `history-head`, `history-message` |
| Checkpoint | tenant + isolation + agent + workflow ID | `checkpoint-head`, immutable `workflow-checkpoint`, `invocation-ledger` |

Session and checkpoint payloads use the production Java version-1 `JsonStateSerializer` envelopes.
Unknown future versions, wrong kinds, malformed payloads, oversized documents, and cross-partition
documents fail closed. These are Java schemas only; this module does **not** claim .NET/Python
session or checkpoint wire compatibility.

## Session store

```java
JsonStateSerializer serializer =
        new JsonStateSerializer(new SerializationLimits(1_800_000, 64, 250_000, 128, 50_000));
CosmosSessionStore sessions = CosmosSessionStore.create(
        new CosmosSessionStoreOptions(storage, 86_400, CosmosDeletePolicy.SOFT, 3_600),
        serializer);
```

Creates use `If-None-Match`; replacements/deletes point-read the current item and use its ETag with
`If-Match`. Framework revisions increase monotonically across soft delete/recreate. Soft delete is
the default and writes an expiring tombstone. Explicit hard delete physically removes the item and
therefore starts a new revision lineage if recreated.

## Ordered history

`CosmosHistoryProvider` persists one versioned message envelope per item. A transactional batch
conditionally advances an ETag-protected sequence head and creates deterministic message IDs derived
from the framework message ID or run ID. Retrying an acknowledged-but-lost append verifies payload
digests and does not duplicate history. Reads are partition-bound, chronological by the unique
sequence alone, page/cursor bounded, and never cross-partition by default.

## Workflow checkpoints

`CosmosCheckpointStorage` binds one instance to one workflow ID. Immutable checkpoint snapshots are
addressed by checkpoint ID while an ETag-protected key head implements the framework CAS surface.
The store advertises `ATOMIC_CHECKPOINT_AND_LEDGER`: a Cosmos transactional batch atomically commits
the new checkpoint head/snapshot and up to 98 invocation-ledger mutations in the same workflow
partition. The Java checkpoint codec preserves previous checkpoint ID, framework revision, graph
fingerprint, buffered inputs, pending executors, and fan-in epochs.

`deleteAsync` is key-scoped, matching the provider-neutral `CheckpointStorage` contract. It delegates
to `purgeAsync`, which removes every immutable snapshot whose `checkpointKey` equals the target key
and deletes the target ETag-fenced head last. Each query traversal and transactional delete batch is
bounded. Empty SDK pages are followed through continuation, every projected row is validated, and
the query is restarted after each delete batch. Snapshot-delete batches conditionally replace the
unchanged head document and carry its new ETag forward, so they do not rely on conditional batch
reads. Other checkpoint heads and snapshots, plus all workflow-wide invocation-ledger documents,
remain untouched.

`CosmosCheckpointPurgeResult` reports deleted head/snapshot and completed-batch counts without SDK
types. Partial conflict/throttle/failure outcomes retain the target head for retry. A missing head is
`ALREADY_PURGED` only after a complete key-scoped query finds no snapshots; orphan snapshots produce
an incomplete conflict report and are not deleted without an ETag fence. The explicit purge remains
idempotent, while SPI `deleteAsync` preserves exact-revision CAS semantics by treating an already
absent key or any partial report as a conflict/failure.

Snapshot revisions are scoped to a `CheckpointKey` and can collide across keys. Every immutable
snapshot therefore stores a canonical single-property sort key consisting of its zero-padded
19-digit revision and checkpoint ID. Listing orders by that property, avoiding a composite-index
requirement while remaining deterministic across keys. Current checkpoint head and snapshot writes
both store the exact canonical property, and reads reject missing or malformed values. This
development checkpoint format is not released: development snapshots that lack `snapshotSortKey`
are incompatible and must be recreated. No online migration or legacy-writer rolling-upgrade
compatibility is provided or claimed.

## Reliability, RUs, and diagnostics

- The SDK client is reused and uses Session consistency.
- Direct mode is the default; gateway mode is explicit.
- Item content responses on writes are disabled. Save/append/commit paths use caller-owned values,
  ETags and diagnostics headers, and explicit point reads rather than write response bodies.
- SDK 429 retries are bounded by `CosmosRetryOptions`; a remaining 429 becomes
  `CosmosThrottledException` with sanitized retry-after, request charge, activity ID, and status.
- Every adapter operation has a bounded Reactor deadline and cancellation cancels the upstream
  subscription/future.
- Diagnostics contain no document, message, query parameter, token, or key content by default.
- Point operations consume fewer RUs than queries. History/checkpoint listings stay inside one
  logical partition and expose bounded continuation cursors.

## Limitations

- No default cross-partition session/history enumeration.
- No cross-language persisted-state wire compatibility claim.
- Hard session delete cannot retain a monotonic revision lineage.
- Atomic checkpoint/ledger commits are limited to one workflow logical partition and 100 Cosmos
  batch operations.
- Provisioning validates but does not update existing policies.

## Local emulator integration test

The test suite includes an opt-in disposable database/container test for ordered history,
same-revision cross-key checkpoint listing, key-scoped purge, preservation of another key and the
invocation ledger, and purge idempotency. It accepts only a loopback HTTPS endpoint and never uses
live Azure credentials:

```bash
export COSMOS_EMULATOR_TESTS=true
export COSMOS_EMULATOR_ENDPOINT=https://localhost:8081/
export COSMOS_EMULATOR_KEY='<local emulator key>'
./gradlew :agent-framework-azure-cosmos:test
```

Install the emulator certificate in the Java trust store first. Without the three environment
variables the test is explicitly skipped.

## References

- [Azure Cosmos DB Java SDK v4 overview](https://learn.microsoft.com/java/api/overview/azure/cosmos-readme)
- [Java SDK v4 performance tips](https://learn.microsoft.com/azure/cosmos-db/performance-tips-java-sdk-v4-sql)
- [Design resilient applications](https://learn.microsoft.com/azure/cosmos-db/conceptual-resilient-sdks)
- [Optimistic concurrency control](https://learn.microsoft.com/azure/cosmos-db/nosql/database-transactions-optimistic-concurrency)
