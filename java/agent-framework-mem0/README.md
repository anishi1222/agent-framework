# Microsoft Agent Framework Mem0 integration

`com.microsoft.agents:agent-framework-mem0` provides a framework-owned
`com.microsoft.agents.memory.mem0.Mem0ContextProvider`. It targets **Mem0 Platform** through the JDK
`HttpClient`; there is no official Mem0 Java SDK and this module does not add another HTTP stack.

## Current Platform API mapping

The mapping below was verified against the official Mem0 OpenAPI document and API reference on
2026-08-13:

- <https://docs.mem0.ai/openapi.json> (`info.version: v1`)
- <https://docs.mem0.ai/api-reference/memory/add-memories>
- <https://docs.mem0.ai/api-reference/memory/search-memories>
- <https://docs.mem0.ai/api-reference/memory/get-memories>
- <https://docs.mem0.ai/api-reference/memory/delete-memories>
- <https://docs.mem0.ai/api-reference/events/get-event>

| Operation | Verified method and path | Request / response contract used |
|---|---|---|
| Add conversation | `POST /v3/memories/add/` | One `messages` array plus top-level `app_id`, `user_id`, `agent_id`, and/or `run_id`; accepts `status` / `event_id` and polls async events. |
| Search | `POST /v3/memories/search/` | Nonblank `query`, identity values inside `filters`, bounded `top_k`; accepts the documented `{ "results": [...] }` envelope and the legacy list response only. |
| List | `POST /v3/memories/?page=N&page_size=N` | Identity `filters`; parses the documented paginated envelope. |
| Clear scope | `DELETE /v1/memories/?<encoded identities>` | Requires at least one explicit non-wildcard identity and polls a returned event. There is no unscoped delete-all API. |
| Event status | `GET /v1/event/{event_id}/` | Accepts only `PENDING`, `RUNNING`, `SUCCEEDED`, or `FAILED`; polling has bounded delay, deadline, and cancellation. |

The OpenAPI document still lists a V1 history route, but this integration intentionally exposes **no
history endpoint**. The V1 item get/update/delete routes are also not exposed because they accept only
a memory ID and cannot atomically enforce the trusted app/user/agent/run scope at the service
boundary. Mem0 owns extraction, vector persistence, and memory history; it cannot implement Agent
Framework `MemoryStore` CAS semantics.

## Usage

```java
Mem0Scope scope = Mem0Scope.builder()
        .appId("travel-app")
        .userId("user-42")
        .build();

try (Mem0ContextProvider mem0 = Mem0ContextProvider.builder(
                Mem0ApiKey.of(System.getenv("MEM0_API_KEY")),
                new Mem0ProviderState(scope))
        .build()) {
    // Register mem0 in the agent's ContextProvider list.
}
```

For trusted per-session isolation, supply a resolver instead of deriving IDs from message text or
run metadata:

```java
Mem0ContextProvider mem0 = Mem0ContextProvider.builder(
                Mem0ApiKey.of(System.getenv("MEM0_API_KEY")),
                request -> new Mem0ProviderState(
                        Mem0Scope.forRun("app-owned-" + request.session().sessionId())))
        .build();
```

`Mem0Scope` requires at least one app, user, agent, or run identity. Scope values come only from the
configured static state or trusted resolver. Retrieved memories are injected as one `USER` message
using escaped `<memory-reference>` blocks, `mem0://` citations, an explicit untrusted-reference
warning, and `memoryTrust=untrusted-reference` provenance metadata. They are never promoted to system
instructions.

Mem0 stores user-authored and agent-authored facts in distinct entity partitions. When both
`userId` and `agentId` are configured, search and clear execute once per partition while retaining
the configured app and run qualifiers, then search results are deduplicated and deterministically
reranked. V3 list pagination requires a single user or agent partition because combining independent
page cursors would be ambiguous.

## Storage and eventual consistency

After a successful agent run, allowed nonblank caller-input and response messages are sent in authored
order in **one** V3 add request. USER, ASSISTANT, and SYSTEM are enabled by default; the builder can
restrict roles or install an additional trusted message predicate. Failed agent runs store nothing.

V3 add is not retried because the official API does not document an idempotency key. If the service
accepts an add but the connection fails before the response is observed, a caller retry can create
at-least-once / duplicate risk. Search deduplicates returned entries by stable memory ID while
preserving the first service rank.

## Failure and transport policy

`FAIL_RUN` is the default for retrieval and storage because the Java `ContextProvider` runtime does
not silently fall back. `CONTINUE_WITHOUT_MEMORY` must be configured explicitly and applies only to
eligible transport, timeout, local-concurrency, HTTP 408/429, and 5xx failures. Permanent 3xx/4xx
responses, cancellation, validation, authentication/authorization, malformed response, limit, and
partial-event failures always propagate.

The JDK adapter:

- requires HTTPS, except exact syntactic `localhost`, numeric `127/8`, or IPv6 loopback HTTP for local
  tests and compatible proxies;
- disables redirects and validates every resolved URI remains on the configured origin;
- uses HTTP/2 and exactly `Authorization: Token <api-key>`;
- bounds request/response bytes, nesting, strings, numbers, collections, concurrency, request time,
  total operation time, retry delay, and event polling;
- rejects duplicate JSON keys, trailing content, non-finite/malformed numbers, unsupported content
  types, and unexpected response shapes;
- retries only search, event status, and scoped clear for bounded HTTP 408, 429, and 5xx responses;
- cancels the JDK request future when caller cancellation fires; and
- sanitizes exceptions and warning logs so keys, identities, queries, messages, and response bodies
  are not retained.

Caller-supplied executors and schedulers in `Mem0ClientOptions` remain caller-owned. Provider-owned
resources are closed by `Mem0ContextProvider.close()`.

## Platform and custom endpoint compatibility

The hosted default is `https://api.mem0.ai/`. A custom endpoint must expose the same Platform paths
and response contracts under its configured base URI. Mem0 OSS deployments have different
capabilities and are not claimed to be universally compatible; in particular, Platform app scope,
V3 async events, and advanced filtering may be absent.

An executable example is intentionally deferred to `java-docs-samples`; this module contains no
one-off sample application.

## Tests

Loopback tests use the JDK `HttpServer` and do not require credentials:

```bash
./gradlew :agent-framework-mem0:test
```

The optional live Platform test always uses the official HTTPS endpoint and is disabled by default:

```bash
MEM0_INTEGRATION_TESTS=true MEM0_API_KEY=... \
  ./gradlew :agent-framework-mem0:test --tests '*Mem0PlatformIntegrationTest'
```

The live test uses a unique user scope and performs add → event completion → search → scoped clear
cleanup. A skipped live test is not evidence that Platform integration succeeded.
