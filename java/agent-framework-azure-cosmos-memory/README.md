# Microsoft Agent Framework Azure Cosmos DB Memory for Java

`com.microsoft.agents:agent-framework-azure-cosmos-memory` implements the provider-neutral Java
memory contracts with Azure Cosmos DB for NoSQL vector, full-text, and hybrid search. It uses stable
`com.azure:azure-cosmos:4.81.0`, Java 25, and no preview APIs.

## Provider-neutral contracts

`agent-framework-agents` owns the inward contracts:

- `MemoryStore`
- `MemoryRecord`, `MemoryKey`, `MemoryScope`, and immutable `MemoryMetadata`
- `MemoryQuery`, `MemoryFilter`, `MemorySearchResult`, and `MemoryPage<T>`
- `EmbeddingProvider`, `EmbeddingRequest`, and bounded `EmbeddingVector`
- safe `MemoryContextProvider`

None of these types depends on Cosmos, Reactor, Jackson, or an embedding-provider SDK.

## Container policy

Every memory container uses `/partitionKey`, `/vector`, and `/content` as fixed paths. The configured
vector dimensions, `float32` data type, distance function, and vector index type are stored in every
document and checked on every read. Mismatches fail; vectors are never silently truncated or padded.

When optional provisioning is enabled, the adapter creates a container with the exact vector
embedding policy, vector index, optional full-text policy/index, TTL, automatic indexing setting,
and the composite index required by stable list pagination:

```json
{
  "compositeIndexes": [
    [
      { "path": "/updatedAt", "order": "descending" },
      { "path": "/id", "order": "ascending" }
    ]
  ]
}
```

Vector policies/indexes are immutable in Cosmos. Whether creation is enabled or the application
supplies an existing container, the adapter reads the effective SDK policy before use and requires
the `/updatedAt`, `/id` path sequence with either the provisioned `DESC, ASC` directions or the
globally reversed `ASC, DESC` directions that Cosmos can traverse in reverse. Mixed directions fail
with `INCOMPATIBLE_RESOURCE`; the adapter never silently replaces or mutates the container.
Additional compatible composite indexes are allowed.

## Isolation and CRUD

`MemoryScope` always carries explicit tenant and scope IDs. The tenant must match the store's
`CosmosPartitionContext`; the scope is included in the collision-safe partition hash. Every point
operation and query supplies that single partition key. Metadata cannot override tenant/scope.

`putAsync`, `upsertAsync`, `getAsync`, and `deleteAsync` use Cosmos item ETags and framework
revisions. Create uses `If-None-Match`, replacement/delete uses `If-Match`, and an exact retry is
recognized by its deterministic payload digest.

## Parameterized search

```java
MemoryQuery query = new MemoryQuery(
        new MemoryScope("tenant-42", "user-17"),
        "vegetarian hiking preferences",
        queryEmbedding,
        new MemoryFilter(Map.of("category", StateValue.string("preference"))),
        MemorySearchMode.HYBRID,
        5);
MemoryPage<MemorySearchResult> page =
        store.searchAsync(query, cancellation).toCompletableFuture().join();
```

- Vector search uses the legal projection/order shape:
  `SELECT TOP @top c, VectorDistance(c.vector,@vector) AS score ... ORDER BY
  VectorDistance(c.vector,@vector)`.
- Full-text search never projects `FullTextScore`; it ends with only
  `ORDER BY RANK FullTextScore(c.content,...)`.
- Hybrid search never projects a fused score; it ends with only
  `ORDER BY RANK RRF(VectorDistance(c.vector,@vector),FullTextScore(c.content,...))`.
- Ranking clauses have no secondary item path. Vector, full-text, and hybrid results preserve exact
  service response order; equal-score vector rows are not client-reordered.
- Query vectors, terms, limits, and metadata filter pairs are `SqlParameter` values. No user text,
  metadata key, tenant ID, or scope ID is interpolated into the SQL. Cosmos supports `TOP @top`;
  `topK` remains bounded to 100.
- Top-K search consumes SDK response pages in service order until `topK` rows or terminal response,
  including empty intermediate pages with continuation. The returned framework page remains
  single-page with a `null` cursor. The provider-neutral `MemoryQuery` retains its cursor component
  for other stores, but Cosmos rejects every non-null search cursor. List pagination remains
  cursor-based and uses `/updatedAt DESC, /id ASC`.
- `MemorySearchResult.score()` retains its primitive `double` JVM/source signature. Vector search
  returns the finite provider `VectorDistance` value. Cosmos full-text and hybrid ranking don't
  expose a legal score projection, so `score()` is `Double.NaN`, `hasScore()` is false, and
  `optionalScore()` is empty; `rank()` is the explicit one-based service-order ordinal. Infinities
  are rejected. The bounded local fallback exposes its own finite local score.

Server capability errors propagate by default. `BOUNDED_PARTITION_SCAN` is an explicit opt-in
fallback that reads only the same tenant/scope partition, caps documents, and applies deterministic
local scoring. There is no silent fallback.

## Context integration

`CosmosMemoryContextProvider` delegates to the provider-neutral `MemoryContextProvider`. It injects
bounded top-K snippets as user-role, explicitly **untrusted reference data** with record provenance
and citations. Retrieved content never becomes system instructions and is not written into session
history unless `MemoryContextOptions.persistRetrievedContent()` is explicitly enabled.

## Security, RU, and lifecycle

- RBAC/managed identity is preferred; account keys require the redacting `CosmosAccountKey`.
- Exact HTTPS Cosmos account hosts are required.
- SDK clients are reused and closed only by the creating store.
- Item content responses on writes are disabled; writes use request headers, caller values, and
  explicit point reads rather than response bodies.
- 429 retries, operation timeouts, concurrency, document/page/filter/term/vector sizes, and fallback
  scans are bounded.
- Diagnostics may contain status, request charge, activity ID, and retry-after, but never memory
  content or query parameter values.
- Vector and hybrid queries can consume substantial RUs; select partition keys and top-K/page bounds
  deliberately and monitor request charge.

## Limitations

- Cosmos vector and full-text account features and compatible container policies must already be
  enabled when provisioning is off.
- Cosmos full-text and hybrid service ranking exposes ordinal rank, not a fabricated or
  cross-provider-comparable score.
- Client-side fallback is intentionally bounded and single-partition; it is not a replacement for a
  vector/full-text index.
- No cross-language memory document wire compatibility is claimed.

## Local emulator integration tests

The test suite includes opt-in disposable database/container integration tests. They accept only a
loopback HTTPS endpoint and therefore never use live Azure credentials:

```bash
export COSMOS_EMULATOR_TESTS=true
export COSMOS_EMULATOR_ENDPOINT=https://localhost:8081/
export COSMOS_EMULATOR_KEY='<local emulator key>'
./gradlew :agent-framework-azure-cosmos-memory:test
```

Install the emulator certificate in the Java trust store first. The tests execute memory list,
vector, full-text, and hybrid operations when the emulator supports their effective policies and
query features. The Linux vNext emulator currently documents custom indexing policy as a no-op and
doesn't advertise vector/full-text/hybrid support; an affected test is reported as **skipped**, not
as proof that the feature works. Always-on SDK policy serialization and exact SQL grammar tests
remain mandatory even when emulator capabilities are absent.

## References

- [Vector search with Java](https://learn.microsoft.com/azure/cosmos-db/quickstart-vector-store-java)
- [Hybrid search](https://learn.microsoft.com/azure/cosmos-db/gen-ai/hybrid-search)
- [Full-text search](https://learn.microsoft.com/azure/cosmos-db/gen-ai/full-text-search)
- [Vector indexing policies](https://learn.microsoft.com/azure/cosmos-db/nosql/vector-search)
