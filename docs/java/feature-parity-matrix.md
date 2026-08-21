# Java feature parity matrix

This matrix records the areas the Java port covers in its initial scope and the conformance cases
that pin each area's behavior. It is the companion document to the language-neutral fixtures in
[`java/agent-framework-conformance`](../../java/agent-framework-conformance/README.md).

## How to read this matrix

- **Area / Group** — the exact area label. It must match a `matrixAreas` entry in
  [`manifest-v1.json`](../../java/agent-framework-conformance/src/main/resources/conformance/manifest-v1.json)
  and may appear only once in this document.
- **.NET** / **Python** — `✅` when the manifest records at least one `dotnet/` or `python/` source
  reference for the listed cases, and `—` when it does not, in which case the Java behavior is
  pinned by a specification, an architectural decision record, or a published service contract
  instead. The two columns state where the ported behavior was read from; they are not a
  feature-by-feature parity claim about the other SDKs.
- **Java** — the Gradle module or modules that own the behavior. See
  [`java/AGENTS.md`](../../java/AGENTS.md) for the full module inventory.
- **Contract** — the conformance case IDs registered for the area.
- **Status** — `initial-scope` marks an area that the initial Java port implements and pins with
  fixtures.

`ConformanceManifestCoverageTest` reads the tables between `## 1. Core Abstractions` and
`## SDK Classification Audit`. Every `initial-scope` row must name its area exactly once and must
list exactly the case IDs the manifest registers for that area, so a new case is added by editing
the manifest and this document together.

## 1. Core Abstractions

Provider-neutral message, response, options, and embedding contracts.

| Area / Group | .NET | Python | Java | Contract | Status |
| --- | --- | --- | --- | --- | --- |
| Chat message / content | ✅ | ✅ | `agent-framework-core` | `JCF-CORE-001` | `initial-scope` |
| Run options / response | ✅ | ✅ | `agent-framework-core` | `JCF-CORE-002`, `JCF-CORE-003` | `initial-scope` |
| Streaming responses | ✅ | ✅ | `agent-framework-core`, `agent-framework-agents` | `JCF-CORE-002`, `JCF-CORE-005`, `JCF-AGENTS-001`, `JCF-TOOLS-006` | `initial-scope` |
| Chat options / finish reason | — | — | `agent-framework-core` | `JCF-CORE-004` | `initial-scope` |
| Structured output | ✅ | ✅ | `agent-framework-core`, `agent-framework-agents` | `JCF-CORE-006` | `initial-scope` |
| Embedding client interface | ✅ | ✅ | `agent-framework-core` | `JCF-CORE-007` | `initial-scope` |

## 2. Agents and Middleware

Agent lifecycle, the chat client boundary, and middleware pipelines.

| Area / Group | .NET | Python | Java | Contract | Status |
| --- | --- | --- | --- | --- | --- |
| Agent interface / base | ✅ | ✅ | `agent-framework-agents` | `JCF-AGENTS-001` | `initial-scope` |
| Chat client interface | ✅ | ✅ | `agent-framework-agents` | `JCF-AGENTS-001` | `initial-scope` |
| Context providers | ✅ | ✅ | `agent-framework-agents` | `JCF-AGENTS-002` | `initial-scope` |
| Run context / metadata | ✅ | ✅ | `agent-framework-agents` | `JCF-AGENTS-002` | `initial-scope` |
| Agent middleware | — | ✅ | `agent-framework-agents` | `JCF-AGENTS-003` | `initial-scope` |
| Chat middleware | — | ✅ | `agent-framework-agents` | `JCF-AGENTS-003` | `initial-scope` |
| Function middleware | — | ✅ | `agent-framework-agents` | `JCF-AGENTS-003` | `initial-scope` |
| Middleware termination / context | — | ✅ | `agent-framework-agents` | `JCF-AGENTS-003` | `initial-scope` |
| Delegating agent | ✅ | ✅ | `agent-framework-agents` | `JCF-AGENTS-004` | `initial-scope` |
| Message injection | ✅ | ✅ | `agent-framework-agents` | `JCF-AGENTS-005` | `initial-scope` |
| Additional properties / extensions | ✅ | — | `agent-framework-agents` | `JCF-AGENTS-006` | `initial-scope` |

## 3. Context, Sessions, and History

Compaction, session state, and persisted history stores.

| Area / Group | .NET | Python | Java | Contract | Status |
| --- | --- | --- | --- | --- | --- |
| Compaction strategies | ✅ | ✅ | `agent-framework-agents` | `JCF-CONTEXT-001` | `initial-scope` |
| Token estimator SPI | ✅ | ✅ | `agent-framework-agents` | `JCF-CONTEXT-001` | `initial-scope` |
| Message grouping and history integration | ✅ | ✅ | `agent-framework-agents` | `JCF-CONTEXT-001` | `initial-scope` |
| Session / state | — | — | `agent-framework-agents` | `JCF-SESSIONS-001` | `initial-scope` |
| Session store interface | — | — | `agent-framework-agents` | `JCF-SESSIONS-002` | `initial-scope` |
| In-memory session store | — | ✅ | `agent-framework-agents` | `JCF-SESSIONS-003` | `initial-scope` |
| Cosmos NoSQL history | ✅ | ✅ | `agent-framework-azure-cosmos` | `JCF-SESSIONS-004`, `JCF-WORKFLOWS-006` | `initial-scope` |
| Redis / Valkey history | ✅ | ✅ | `agent-framework-valkey` | `JCF-SESSIONS-005` | `initial-scope` |

## 4. Tools

Tool metadata, the function-invocation loop, and approval-gated execution.

| Area / Group | .NET | Python | Java | Contract | Status |
| --- | --- | --- | --- | --- | --- |
| Tool capability contracts | — | ✅ | `agent-framework-tools` | `JCF-TOOLS-001` | `initial-scope` |
| Tool mode / normalization | — | ✅ | `agent-framework-tools` | `JCF-TOOLS-001` | `initial-scope` |
| Tools (general) | ✅ | ✅ | `agent-framework-tools`, `agent-framework-tools-shell` | `JCF-TOOLS-001`, `JCF-TOOLS-014` | `initial-scope` |
| Function tool / annotation | — | ✅ | `agent-framework-tools` | `JCF-TOOLS-002`, `JCF-TOOLS-003`, `JCF-TOOLS-004`, `JCF-TOOLS-005`, `JCF-TOOLS-006`, `JCF-TOOLS-009`, `JCF-TOOLS-012` | `initial-scope` |
| Tool approval / resume | — | ✅ | `agent-framework-tools`, `agent-framework-agents` | `JCF-TOOLS-007`, `JCF-TOOLS-008`, `JCF-TOOLS-010`, `JCF-TOOLS-011`, `JCF-TOOLS-013` | `initial-scope` |
| Shell tool | ✅ | ✅ | `agent-framework-tools-shell` | `JCF-TOOLS-014` | `initial-scope` |

## 5. Skills

Provider-neutral skill model, skill sources, and MCP-backed discovery.

| Area / Group | .NET | Python | Java | Contract | Status |
| --- | --- | --- | --- | --- | --- |
| Skills sources / provider | ✅ | ✅ | `agent-framework-agents` | `JCF-SKILLS-001` | `initial-scope` |
| Skill types | ✅ | ✅ | `agent-framework-agents` | `JCF-SKILLS-002` | `initial-scope` |
| MCP skill templates (dotnet) | ✅ | ✅ | `agent-framework-mcp` | `JCF-SKILLS-003` | `initial-scope` |

## 6. Harness

Autonomous loop assembly and session-scoped workspace state.

| Area / Group | .NET | Python | Java | Contract | Status |
| --- | --- | --- | --- | --- | --- |
| Harness agent | ✅ | ✅ | `agent-framework-harness` | `JCF-HARNESS-001` | `initial-scope` |
| Background agents | ✅ | ✅ | `agent-framework-harness` | `JCF-HARNESS-002` | `initial-scope` |
| File access / memory / todo | ✅ | ✅ | `agent-framework-harness` | `JCF-HARNESS-003` | `initial-scope` |

## 7. Workflows

Workflow graph construction, event stream, checkpointing, and visualization.

| Area / Group | .NET | Python | Java | Contract | Status |
| --- | --- | --- | --- | --- | --- |
| Workflow core / builder | — | ✅ | `agent-framework-workflows` | `JCF-WORKFLOWS-001` | `initial-scope` |
| Executor / function executor | — | ✅ | `agent-framework-workflows` | `JCF-WORKFLOWS-001`, `JCF-WORKFLOWS-003` | `initial-scope` |
| Workflow events | ✅ | ✅ | `agent-framework-workflows` | `JCF-WORKFLOWS-001`, `JCF-WORKFLOWS-003`, `JCF-WORKFLOWS-004`, `JCF-WORKFLOWS-008` | `initial-scope` |
| Edges / graph | ✅ | ✅ | `agent-framework-workflows` | `JCF-WORKFLOWS-002` | `initial-scope` |
| Workflow validation | ✅ | ✅ | `agent-framework-workflows` | `JCF-WORKFLOWS-002` | `initial-scope` |
| Checkpoint storage / resume | ✅ | ✅ | `agent-framework-workflows`, `agent-framework-azure-cosmos` | `JCF-WORKFLOWS-004`, `JCF-WORKFLOWS-005`, `JCF-WORKFLOWS-006` | `initial-scope` |
| Visualization | ✅ | ✅ | `agent-framework-workflows` | `JCF-WORKFLOWS-007` | `initial-scope` |
| Functional workflow | — | ✅ | `agent-framework-workflows` | `JCF-WORKFLOWS-008` | `initial-scope` |

## 8. Orchestrations

Higher-level multi-agent patterns built directly on agents.

| Area / Group | .NET | Python | Java | Contract | Status |
| --- | --- | --- | --- | --- | --- |
| Sequential / concurrent / handoff / group chat / Magentic | ✅ | ✅ | `agent-framework-orchestrations` | `JCF-ORCHESTRATIONS-001` | `initial-scope` |
| Orchestrations | ✅ | ✅ | `agent-framework-orchestrations` | `JCF-ORCHESTRATIONS-001` | `initial-scope` |

## 9. Providers

Model and agent provider adapters.

| Area / Group | .NET | Python | Java | Contract | Status |
| --- | --- | --- | --- | --- | --- |
| OpenAI Responses and embeddings | ✅ | ✅ | `agent-framework-openai` | `JCF-CORE-007`, `JCF-PROVIDERS-001` | `initial-scope` |
| Azure OpenAI Responses, embeddings, identity, and service-version modes | ✅ | ✅ | `agent-framework-azure-openai` | `JCF-CORE-007`, `JCF-PROVIDERS-001` | `initial-scope` |
| Foundry / Azure AI | ✅ | ✅ | `agent-framework-foundry` | `JCF-PROVIDERS-002` | `initial-scope` |
| Anthropic | ✅ | ✅ | `agent-framework-anthropic` | `JCF-PROVIDERS-003` | `initial-scope` |
| AWS Bedrock | — | ✅ | `agent-framework-bedrock` | `JCF-PROVIDERS-004` | `initial-scope` |
| Gemini | — | ✅ | `agent-framework-gemini` | `JCF-PROVIDERS-005` | `initial-scope` |
| Mistral | — | ✅ | `agent-framework-mistral` | `JCF-PROVIDERS-006` | `initial-scope` |
| Ollama | — | ✅ | `agent-framework-ollama` | `JCF-PROVIDERS-007` | `initial-scope` |
| Foundry Local | — | ✅ | `agent-framework-foundry-local` | `JCF-PROVIDERS-008` | `initial-scope` |
| Azure AI Persistent (OpenAI Assistants) | ✅ | — | `agent-framework-azure-ai-persistent` | `JCF-PROVIDERS-009` | `initial-scope` |
| GitHub Copilot | ✅ | ✅ | `agent-framework-github-copilot` | `JCF-PROVIDERS-010` | `initial-scope` |
| Copilot Studio | ✅ | ✅ | `agent-framework-copilotstudio` | `JCF-PROVIDERS-011` | `initial-scope` |

## 10. Protocols, Hosting, and Transports

Wire protocols, hosting surfaces, and shared conformance test support.

| Area / Group | .NET | Python | Java | Contract | Status |
| --- | --- | --- | --- | --- | --- |
| MCP tools (client) | ✅ | ✅ | `agent-framework-mcp` | `JCF-PROTOCOLS-001` | `initial-scope` |
| MCP hosting | ✅ | ✅ | `agent-framework-hosting-mcp` | `JCF-PROTOCOLS-002` | `initial-scope` |
| A2A client / protocol | ✅ | ✅ | `agent-framework-a2a` | `JCF-PROTOCOLS-003` | `initial-scope` |
| A2A hosting | ✅ | ✅ | `agent-framework-hosting-a2a` | `JCF-HOSTING-002` | `initial-scope` |
| AG-UI hosting | ✅ | — | `agent-framework-agui`, `agent-framework-hosting-agui` | `JCF-PROTOCOLS-004`, `JCF-HOSTING-003` | `initial-scope` |
| Hosting abstractions | ✅ | ✅ | `agent-framework-hosting` | `JCF-HOSTING-TRANSPORT-001` | `initial-scope` |
| Generic HTTP / SSE / WebSocket hosting | ✅ | ✅ | `agent-framework-hosting-http` | `JCF-HOSTING-TRANSPORT-001` | `initial-scope` |
| Foundry hosting / session | ✅ | ✅ | `agent-framework-foundry-hosting` | `JCF-HOSTING-004` | `initial-scope` |
| Integration test harness | — | — | `agent-framework-conformance` | `JCF-HOSTING-001` | `initial-scope` |

## 11. Integrations and Storage

Memory, retrieval, governance, and evaluation integrations.

| Area / Group | .NET | Python | Java | Contract | Status |
| --- | --- | --- | --- | --- | --- |
| Azure Content Understanding | — | ✅ | `agent-framework-azure-content-understanding` | `JCF-INTEGRATIONS-001` | `initial-scope` |
| Purview data governance | ✅ | ✅ | `agent-framework-purview` | `JCF-INTEGRATIONS-002` | `initial-scope` |
| Foundry evals integration | ✅ | — | `agent-framework-foundry-evaluations` | `JCF-INTEGRATIONS-003` | `initial-scope` |
| Azure Cosmos memory | — | ✅ | `agent-framework-azure-cosmos-memory` | `JCF-INTEGRATIONS-004` | `initial-scope` |
| Mem0 memory | ✅ | ✅ | `agent-framework-mem0` | `JCF-INTEGRATIONS-005` | `initial-scope` |
| Azure AI Search (RAG) | — | ✅ | `agent-framework-azure-ai-search` | `JCF-INTEGRATIONS-006` | `initial-scope` |

## 12. Telemetry and Observability

Feature stages, user-agent telemetry, and GenAI spans.

| Area / Group | .NET | Python | Java | Contract | Status |
| --- | --- | --- | --- | --- | --- |
| Feature stage decorators | — | ✅ | `agent-framework-core` | `JCF-TELEMETRY-001` | `initial-scope` |
| User-agent telemetry | — | ✅ | `agent-framework-core` | `JCF-TELEMETRY-002` | `initial-scope` |
| Feature usage bitmask | — | ✅ | `agent-framework-core` | `JCF-TELEMETRY-002` | `initial-scope` |
| OpenTelemetry GenAI observability | ✅ | ✅ | `agent-framework-observability` | `JCF-TELEMETRY-003` | `initial-scope` |

## SDK Classification Audit

Adapter modules either build on an official vendor SDK or implement the wire protocol themselves on
the JDK HTTP client with framework-owned models. The classification below is taken from each
module's declared dependencies; shared runtime modules (`agent-framework-core`,
`agent-framework-agents`, `agent-framework-tools`, `agent-framework-tools-shell`,
`agent-framework-harness`, `agent-framework-workflows`, `agent-framework-orchestrations`, and
`agent-framework-hosting`) declare no service client at all.

| Java module | Classification | Client dependency |
| --- | --- | --- |
| `agent-framework-openai` | Official SDK | `openai-java` |
| `agent-framework-azure-openai` | Official SDK | `azure-ai-openai`, `azure-identity` |
| `agent-framework-foundry` | Official SDK | `azure-ai-agents`, `azure-ai-projects` |
| `agent-framework-foundry-evaluations` | Official SDK | `azure-ai-projects` |
| `agent-framework-azure-ai-persistent` | Official SDK | `azure-ai-agents-persistent` |
| `agent-framework-anthropic` | Official SDK | `anthropic-java` |
| `agent-framework-bedrock` | Official SDK | AWS SDK v2 Bedrock Runtime |
| `agent-framework-gemini` | Official SDK | `google-genai` |
| `agent-framework-github-copilot` | Official SDK | GitHub Copilot Java SDK |
| `agent-framework-mcp` | Official SDK | MCP Java SDK |
| `agent-framework-hosting-mcp` | Official SDK | MCP Java SDK, embedded Tomcat |
| `agent-framework-azure-cosmos` | Official SDK | `azure-cosmos` |
| `agent-framework-azure-cosmos-memory` | Official SDK | `azure-cosmos` |
| `agent-framework-azure-ai-search` | Official SDK | `azure-search-documents` |
| `agent-framework-azure-content-understanding` | Official SDK | `azure-ai-contentunderstanding` |
| `agent-framework-valkey` | Official SDK | `valkey-glide` |
| `agent-framework-observability` | Official SDK | OpenTelemetry API |
| `agent-framework-mistral` | Framework-owned | JDK HTTP client |
| `agent-framework-ollama` | Framework-owned | JDK HTTP client |
| `agent-framework-foundry-local` | Framework-owned | JDK HTTP client |
| `agent-framework-copilotstudio` | Framework-owned | JDK HTTP client |
| `agent-framework-mem0` | Framework-owned | JDK HTTP client |
| `agent-framework-purview` | Framework-owned | JDK HTTP client |
| `agent-framework-a2a` | Framework-owned | JDK HTTP client; official A2A SDK types stay test-only |
| `agent-framework-hosting-a2a` | Framework-owned | reuses `agent-framework-a2a` |
| `agent-framework-agui` | Framework-owned | JDK HTTP client |
| `agent-framework-hosting-agui` | Framework-owned | reuses `agent-framework-agui` |
| `agent-framework-hosting-http` | Framework-owned | embedded Tomcat, kept internal |
| `agent-framework-foundry-hosting` | Framework-owned | reuses `agent-framework-hosting` |
| `agent-framework-conformance` | Test support | non-published; never a runtime dependency |
