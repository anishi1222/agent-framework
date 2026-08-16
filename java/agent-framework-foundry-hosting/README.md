# Foundry hosting

`agent-framework-foundry-hosting` registers existing Foundry Responses agents and Azure AI
Persistent agents in the generic `HostingRegistry`; it does not implement another dispatcher or
wire server.

The generic `HostingDispatcher` propagates authenticated `hosting.principalId` and independently
derived `hosting.isolationId` metadata. Persistent sessions are keyed by route, principal,
isolation, and an authorized conversation ID. Thread and run IDs are continuation data only and
never authorize a request. The default store is bounded, process-local, TTL-based, and CAS-aware;
durable/multi-replica hosts must supply a durable `FoundryHostedSessionStore`. Stable framework
message IDs are reserved in the same CAS-protected session before submission and retained within a
configurable per-session bound. Concurrent retries therefore append a stable message at most once;
session TTL, authorized deletion, eviction, and bridge-owned store close remove the retained IDs.
If concurrent first requests create competing service threads, the bridge keeps the CAS winner and
requests best-effort deletion of each losing thread.

Persistent requires-action responses receive an opaque one-time process-local resume handle bound
to principal and isolation. The bridge makes that limitation explicit. Hosted persistent streaming
is not exposed until a durable streaming-session contract exists; direct native streaming remains
available from `agent-framework-azure-ai-persistent`.

Closing the bridge clears only bridge-owned process-local state. It never closes caller agents,
registries, dispatchers, stores, credentials, or remote threads. Remote thread deletion is performed
only through the explicit authorized cleanup API.

## Public API

- `FoundryHostingBridge` registers Responses and Persistent agents into an existing
  `HostingRegistry`, consumes principal-bound one-time persistent continuation handles, and exposes
  explicit authorized persistent-session cleanup.
- `FoundryHostedSessionStore`, `FoundryHostedSession`, and `FoundryHostedSessionKey` define the
  durable-store boundary and its route/principal/isolation/conversation partition.
- `InMemoryFoundryHostedSessionStore` is the bounded TTL/CAS process-local implementation;
  `FoundryHostingOptions` controls session, message-ID, retry, and continuation bounds.

The bridge has no authentication implementation and never trusts thread, run, request, or
conversation identifiers as principals. The generic host must authenticate and authorize each route
and supply independently derived `principalId` and `isolationId` values. An unauthorized resume
attempt does not consume the authorized caller's handle; successful consumption is atomic and
one-time.
