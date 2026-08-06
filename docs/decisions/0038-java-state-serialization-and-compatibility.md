---
status: proposed
contact: anishi1222
date: 2026-08-04
deciders: anishi1222
consulted:
informed:
---

# Java state serialization and compatibility

## Context and Problem Statement

Java sessions and workflow checkpoints need durable, inspectable state. Existing .NET and Python implementations have
different runtime types and serialization mechanisms, including provider-defined state. The Java implementation must
choose a safe default format and define what compatibility means for its initial release without claiming a
cross-language wire contract that does not yet exist.

## Decision Drivers

- Persist state without serializing live behavior, clients, executors, credentials, or other resources.
- Use a readable, testable, and versioned default format.
- Support framework and registered provider state without unsafe arbitrary-class deserialization.
- Preserve behavioral compatibility with .NET and Python as the initial parity target.
- Avoid blocking the Java implementation on a canonical cross-language session or checkpoint schema.
- Leave a deliberate path to future wire compatibility.

## Considered Options

- Versioned Jackson JSON for Java state with behavioral compatibility
- A canonical cross-language JSON schema in the initial release
- Java native object serialization
- Serialize provider SDK objects directly
- Use the Python or .NET persistence representation as Java's format

## Decision Outcome

Chosen option: **versioned Jackson JSON for Java state with behavioral compatibility**.

Java session snapshots and workflow checkpoints use UTF-8 JSON encoded and decoded through Jackson. The Jackson
version is pinned centrally by the Java build; persistence code uses framework-owned snapshot DTOs and a serialization
SPI rather than exposing Jackson types in the provider-neutral public model.

Every persisted document has a framework-owned envelope containing:

- a stable format identifier;
- a document kind, distinguishing at least an agent session from a workflow checkpoint;
- an integer payload version; and
- the state payload for that kind.

The first implementation defines version `1` fixtures before production writers are enabled. Readers reject an
unsupported payload version and a mismatched document kind with a framework serialization exception rather than
partially restoring state. Readers ignore unknown additive properties within a supported version, while required
properties remain validated. Writers produce deterministic property names and must not rely on map iteration order for
semantic meaning.

For Java version `1`, "canonical JSON" means compact UTF-8 JSON in which keys in every object are ordered recursively
by Java `String.compareTo`. This one rule applies to envelopes, framework payload objects, buffered-input objects, and
application/provider map values; there is no separate fixed field order for checkpoint objects.

Arrays preserve semantic order and the JSON serializer never sorts them implicitly. A caller sorts an array before
serialization only when that schema explicitly defines the array as an unordered semantic set. In the version `1`
workflow-checkpoint schema:

- `pendingExecutors` is a semantic set and is sorted by Java `String.compareTo`;
- `bufferedInputs` is a semantic set keyed by `(targetId, sourceId)` and is sorted first by `targetId`, then by
  `sourceId`, using Java `String.compareTo`; duplicate keys are invalid; and
- `fanInNextEpochs` maps each uniquely targeted fan-in group to the zero-based epoch it will release next. An incomplete
  buffered epoch uses that stored value; an already released pending `FanInInput` uses the preceding value. Object-key
  canonicalization orders target IDs lexically; and
- every other array is order-bearing and is emitted without reordering, including messages, content, events, outputs,
  fan-in `sourceIds`/`values`, and arrays nested inside application/provider values.

Readers do not treat authored object or semantic-set insertion order as meaningful. Golden encodings are produced by
sorting schema-defined semantic-set arrays and then passing the resulting framework-owned state tree to the core
serializer, which recursively orders object keys. Serializing the parsed authored tree directly without those
semantic-set transforms is not a canonicalization algorithm.

Document payload versions and registered-codec versions are independent. Each registered value includes its stable type
ID and codec version. The provider-neutral `com.microsoft.agents.core.StateCodec<T>` SPI exposes
`typeId()`, `currentVersion()`, `encode(T)`, `migrate(StateValue, int fromVersion, int toVersion)`, and
`decode(StateValue, int version)`. `StateValue` is the framework-owned JSON value model, so the public SPI does not
expose Jackson. A reader applies registered, deterministic migrations one version at a time before decode; a missing
migration or a value newer than `currentVersion()` fails with `SerializationException`. A document-envelope migration
does not silently change nested codec versions.

Only state is serialized. Agents, middleware, providers, history implementations, SDK clients, credentials, executors,
open streams, and other behavior or resources are reconstructed from application configuration and reattached to the
restored state. This follows the state/behavior separation in [ADR-0018](0018-agentthread-serialization.md).

Jackson default typing and Java class names are not written as polymorphic type metadata. Framework-owned polymorphic
values use explicit stable discriminators. Provider or application state that is not plain JSON must use an explicitly
registered codec with a stable, package-qualified type ID. Duplicate registrations fail at registration time, and an
unknown type ID fails restoration without executing or instantiating an arbitrary class.

Every reader receives a required `SerializationLimits` configuration. It enforces maximum document bytes, nesting
depth, string length, numeric token length, and collection/map entry count. Duplicate object keys, non-finite numbers,
and trailing content are rejected. Limits apply before or during tokenization, not only after constructing an in-memory
tree, and limit violations fail with `SerializationException`. The portable names are `maxDocumentBytes`,
`maxNestingDepth`, `maxStringLength`, `maxNumericTokenLength`, and `maxCollectionEntries`; implementations may choose
environment-specific values, but must accept all five explicitly rather than relying on hidden parser defaults.

Credentials, access tokens, API keys, private keys, SDK clients, and live resource handles are prohibited from
serialized state by default. Codecs must fail rather than persist a known credential-bearing type. Encryption at rest,
integrity/authenticity protection, tenant authorization, access control, retention, and key management are the
responsibility of the selected storage adapter and hosting application; the JSON codec alone provides none of these
properties.

### Session and checkpoint storage semantics

`SessionStore` is owned by `agent-framework-agents`; `CheckpointStorage` is owned by
`agent-framework-workflows`. Both SPIs use immutable snapshot DTOs and optimistic revisions. Their required operation
shape is:

```java
CompletionStage<Optional<VersionedSnapshot<T>>> loadAsync(Key key);
CompletionStage<VersionedSnapshot<T>> saveAsync(Key key, T snapshot, long expectedRevision);
CompletionStage<Void> deleteAsync(Key key, long expectedRevision);
```

The concrete `Key` and snapshot type are `SessionKey`/`AgentSessionSnapshot` for sessions and
`CheckpointKey`/`WorkflowCheckpoint` for workflows. `VersionedSnapshot<T>` is a `core` immutable value. Expected
revision `-1` means "create only"; every successful replacement returns a greater opaque revision. A mismatched expected
revision completes exceptionally with `StorageConflictException`; implementations must not perform a last-writer-wins
fallback.

Loads return immutable values or detached snapshots whose nested mutable values cannot mutate stored state. Saves and
deletes are atomic at one key: readers observe either the previous complete value or the replacement, never a partial
document. File adapters use write-new, flush, and atomic replace where the platform supports it. Database adapters use
the backend's conditional-write primitive. Crash durability after successful completion is not implied by the SPI:
every adapter must document whether completion means process memory, operating-system flush, or durable backend commit,
and durable providers must not acknowledge before their stated boundary. Provider conformance tests must cover detached
reads, compare-and-set conflicts, atomic replacement, failed-write preservation, and the documented crash boundary.

### Tool invocation and checkpoint boundary

The tool runtime guarantees one invocation for a given framework `invocationId` within one uninterrupted logical run,
including the synchronous, asynchronous, and streaming views of that run. It does not claim that an external side
effect occurs exactly once after a process crash.

Crash-safe resume requires one of these explicit mechanisms:

1. a durable `ToolInvocationLedger` records `invocationId`, request digest, and terminal outcome, and a
   `CheckpointCommit` atomically compare-and-set writes the workflow checkpoint plus the ledger delta in the same
   storage transaction; or
2. the provider accepts the durable `invocationId` as an idempotency key and guarantees idempotent replay.

`CheckpointStorage` has a stable capability contract:

```java
enum StorageCapability {
    ATOMIC_CHECKPOINT_AND_LEDGER
}

Set<StorageCapability> capabilities();
CompletionStage<VersionedSnapshot<WorkflowCheckpoint>> commitAsync(
    CheckpointCommit commit, long expectedRevision);
```

`commitAsync` is part of every `CheckpointStorage` implementation's SPI. An adapter whose `capabilities()` does not
contain `ATOMIC_CHECKPOINT_AND_LEDGER` must complete `commitAsync` exceptionally with
`UnsupportedStorageCapabilityException` before writing the checkpoint, ledger, or any other effect. An adapter that
advertises the capability must atomically implement the first mechanism. `CheckpointCommit` contains the replacement
`WorkflowCheckpoint` and its invocation-ledger delta; the adapter rejects the whole commit when either the checkpoint
revision or a ledger precondition conflicts.

Before invoking a storage operation, workflow callers inspect `capabilities()` and choose exactly one recovery path:
transactional checkpoint-plus-ledger through `commitAsync`, provider idempotency using the durable `invocationId`, or
the documented at-least-once path. Callers must not invoke `commitAsync` speculatively and then choose a fallback. The
ledger SPI exposes `lookupAsync(InvocationId)`, `recordPendingAsync(InvocationRecord, long expectedRevision)`, and
`recordOutcomeAsync(InvocationOutcome, long expectedRevision)` for non-transactional and provider-idempotent runs. If
neither durable mechanism is configured, resume is at-least-once for external effects: the framework may replay an
invocation that completed externally but was not durably recorded before the crash. Documentation and compatibility
claims must state that boundary rather than calling checkpoint replay exactly-once.

The initial compatibility target is behavioral:

- equivalent inputs should produce equivalent messages, tool-call ordering, one invocation per ID within an
  uninterrupted logical run, response aggregation, session isolation, workflow transitions, checkpoint/resume
  behavior, errors, and cancellation according to the language-neutral specifications and conformance fixtures;
- replayed external effects are equivalent only when the atomic invocation ledger or provider idempotency contract above
  is configured; otherwise compatibility is explicitly at-least-once after a crash;
- Java JSON must round-trip within the supported Java version range; and
- no initial guarantee is made that Java can restore .NET or Python session/checkpoint files, or that those
  implementations can restore Java files.

JSON wire compatibility for sessions or checkpoints across languages requires a later ADR, a canonical schema,
golden fixtures, migration/version rules, and readers in every participating implementation. It cannot be inferred from
similar field names or from this Java envelope.

### Consequences

- Good, because Java persistence is readable, versioned, and testable with stable fixtures.
- Good, because state is separated from non-serializable behavior and resources.
- Good, because explicit discriminators avoid unsafe arbitrary-class deserialization.
- Good, because implementation can target observable parity without prematurely freezing a cross-language schema.
- Neutral, because provider and application custom state require codec registration.
- Bad, because Java sessions and checkpoints cannot initially be moved directly to .NET or Python.
- Bad, because future cross-language compatibility may require migration readers for the initial Java format.
- Bad, because Jackson mapping and schema evolution require dedicated compatibility tests.

## Validation

- Golden fixtures must cover session and workflow-checkpoint version `1` envelopes.
- Round-trip tests must cover framework state, registered custom state, unknown type IDs, duplicate registrations,
  codec migrations, unsupported versions, wrong document kinds, nulls, and unknown additive properties.
- Parser tests must cover configured byte/depth/string/number/collection limits, duplicate keys, non-finite numbers, and
  trailing content.
- Tests must prove that credentials, SDK clients, executors, middleware, and provider behavior are absent from persisted
  output.
- Store conformance tests must cover detached snapshot reads, optimistic revision conflicts, atomic replacement,
  failed-write preservation, and each provider's documented crash-durability boundary.
- Checkpoint-storage capability tests must cover stable `capabilities()` reporting, atomic commit when
  `ATOMIC_CHECKPOINT_AND_LEDGER` is present, and effect-free `UnsupportedStorageCapabilityException` failure when it is
  absent. Caller tests must prove the transactional, provider-idempotent, or at-least-once path is selected before
  `commitAsync` is invoked.
- Resume tests must distinguish uninterrupted-run invocation deduplication, atomic checkpoint-plus-ledger recovery,
  provider-idempotent replay, and the documented at-least-once fallback.
- Conformance tests must compare observable behavior with the language-neutral specifications and relevant .NET/Python
  fixtures without asserting byte-for-byte session or checkpoint equality.
- Security tests must confirm Jackson default typing is disabled and that encryption, integrity, and access-control
  responsibilities are enforced by the configured adapter/application rather than attributed to the codec.

## Pros and Cons of the Options

### Versioned Jackson JSON for Java state with behavioral compatibility

- Good, because Jackson is a mature JSON implementation with extensible mapping.
- Good, because a versioned envelope supports Java schema evolution.
- Good, because behavioral parity can proceed without inventing a premature wire standard.
- Neutral, because explicit codecs are needed for custom types.
- Bad, because it does not provide immediate cross-language restore.

### A canonical cross-language JSON schema in the initial release

- Good, because sessions and checkpoints could move between languages.
- Neutral, because all implementations would need new version and migration rules.
- Bad, because current runtime models and persistence behavior differ and have no approved common schema.
- Bad, because it would delay the Java core and freeze decisions before conformance evidence exists.

### Java native object serialization

- Good, because simple Java object graphs can be persisted with little mapping code.
- Neutral, because it is Java-specific by design.
- Bad, because it is unsafe for untrusted data and brittle across class evolution.
- Bad, because it is opaque to users and cannot support future cross-language compatibility.

### Serialize provider SDK objects directly

- Good, because adapters would write less conversion code.
- Neutral, because a provider SDK may already supply JSON models.
- Bad, because persisted state would inherit provider SDK schema and version changes.
- Bad, because live clients, credentials, and resource handles are not valid durable state.

### Use the Python or .NET persistence representation as Java's format

- Good, because Java could potentially read one existing implementation's files sooner.
- Neutral, because mapping would still be required for the other implementation.
- Bad, because choosing either language would not create three-way compatibility.
- Bad, because language-specific type restoration and runtime semantics would leak into Java.

## More Information

- [Java feature-parity matrix](../java/feature-parity-matrix.md)
- [ADR-0018: AgentSession serialization](0018-agentthread-serialization.md)
- [ADR-0034: Python session storage and serialization](0034-python-session-store-serialization.md)
