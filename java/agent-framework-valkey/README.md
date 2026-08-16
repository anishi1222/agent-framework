# Microsoft Agent Framework Valkey History for Java

`com.microsoft.agents:agent-framework-valkey` provides a standalone Valkey implementation of the
Java `HistoryProvider` contract, plus explicit asynchronous clear and count operations. It uses the
official stable universal Maven Central artifact `io.valkey:valkey-glide:2.5.1`. All GLIDE and native
client types remain internal.

This module implements history parity only. It does **not** invent a Valkey `SessionStore` or provide
Redis Search/vector memory.

## Configuration and public API

Public configuration is immutable and framework owned:

- `ValkeyEndpoint` — one standalone host and port;
- `ValkeyAuthentication` / `ValkeyPassword` — no authentication or ACL username/password, with
  redacted string rendering;
- `ValkeyClientOptions` — TLS, client name, and operation deadline;
- `ValkeyPartitionContext` — tenant, isolation, and agent namespace;
- `ValkeyHistoryOptions` — key prefix, stored/load limits, sliding TTL, and message/document byte
  limits; and
- `ValkeyStorageException` — sanitized stable error categories.

```java
ValkeyPassword password = ValkeyPassword.of(System.getenv("VALKEY_PASSWORD"));
ValkeyClientOptions client = new ValkeyClientOptions(
        new ValkeyEndpoint("cache.internal.example", 6380),
        ValkeyAuthentication.acl("agent-app", password),
        true,
        "support-agent",
        Duration.ofSeconds(5));
ValkeyHistoryOptions options = new ValkeyHistoryOptions(
        client,
        new ValkeyPartitionContext("tenant-42", "principal-17", "agent-support"),
        "valkey-history",
        "agent-framework:history",
        1000,
        100,
        Duration.ofDays(7),
        1024 * 1024,
        8 * 1024 * 1024);

ValkeyHistoryProvider history =
        ValkeyHistoryProvider.createAsync(options).toCompletableFuture().join();
```

The public factory owns its GLIDE client and `close()` closes it exactly once. The package-internal
adapter injection seam supports externally owned clients for tests and composition; closing such a
provider never closes the external adapter.

## Key and storage contract

Tenant, isolation, agent, and session identifiers are encoded as length-prefixed UTF-8 values and
SHA-256 hashed. Raw identifiers and credentials never appear in keys. One history uses:

```text
<prefix>:{<base64url-sha256>}:messages
<prefix>:{<base64url-sha256>}:dedup
<prefix>:{<base64url-sha256>}:dedup-order
```

The shared hash tag keeps all structures in one Valkey cluster slot even though the current public
endpoint is standalone.

Each non-empty append invokes one Lua script with the keys in the exact order above. Arguments are
the hashed run ID, ordered-message digest, stored-message limit, bounded dedup limit, TTL
milliseconds, message count, and encoded messages. The script:

1. returns replay success when an operation ID already has the same digest;
2. returns a typed conflict when the same operation ID has another digest;
3. appends encoded entries in authored order and trims the list tail;
4. bounds both dedup hash and insertion-order metadata; and
5. refreshes or clears TTL consistently on the list and both dedup structures.

The dedup-operation limit equals `maxStoredMessages`, so every retained one-message append remains
retry protected while metadata growth stays finite. Clear uses one atomic `DEL` script across all
three keys. Count uses `LLEN`. Loads use an atomic bounded tail script: `LLEN` selects at most
`maxLoadedMessages`, one-entry `LRANGE` calls preserve oldest-to-newest order, and per-message plus
aggregate bytes are validated server-side before any values are returned to the client.

## Serialization and safety

Each list entry is compact canonical JSON using the Java state envelope:

```json
{"documentKind":"history-message","format":"agent-framework-java-state","payload":{},"payloadVersion":1}
```

The payload reuses the core internal message-to-`StateValue` codec used by `AgentSessionCodec`.
There is no reflective/default-typing fallback. Duplicate keys, trailing content, non-finite
numbers, unknown content discriminators, wrong document kinds, future versions, null entries, and
configured byte/count overflows fail closed; corrupt entries are never silently skipped. Decoded
messages are detached immutable values.

Stored conversation history may contain sensitive content. Use TLS and least-privilege ACLs, select
bounded retention and TTL, and protect the Valkey deployment independently of this client.

## Cancellation, deadline, and errors

Every GLIDE future, including client creation, is bounded by `operationTimeout`. Provider operations
fail before issuing a command when cancellation was already requested. Once issued, user
cancellation races completion and surfaces `RunCancelledException`; the implementation does not
rely solely on `CompletableFuture.cancel()`. The underlying SDK/server operation may still finish
after cancellation or timeout, so retry the same run and payload to use append idempotency.

`ValkeyStorageException.Kind` distinguishes authentication, conflict, service, timeout, transport,
incompatible data, and closed-client failures. Public messages contain no key, identifier,
credential, script, or stored message data.

## Loopback integration test

The real test is opt-in and accepts loopback hosts only:

```bash
export VALKEY_INTEGRATION_TESTS=true
export VALKEY_HOST=127.0.0.1
export VALKEY_PORT=6379
# Optional: VALKEY_TLS=true, VALKEY_USERNAME, VALKEY_PASSWORD
./gradlew :agent-framework-valkey:test
```

It creates unique hashed namespaces and covers append/load, trim, replay, conflicting digest,
concurrency, TTL, clear, and cleanup. TLS is exercised when the supplied loopback server enables it.
Without the gate and host/port variables, the integration test is explicitly skipped.

## Samples

The current Java tree has no executable `java/samples/` structure. An executable Valkey sample is
therefore deferred to the existing `java-docs-samples` work item rather than introducing a one-off
layout in this module.
