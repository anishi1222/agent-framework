# Java contributor instructions

These instructions apply to all files under `java/`.

## Required commands

Run commands from `java/`:

```bash
./gradlew -p build-logic build
./gradlew :agent-framework-conformance:test
./gradlew clean build
./gradlew check
./gradlew checkArchitecture
./gradlew spotlessApply
./gradlew publishToTestRepository
```

Use the committed wrapper; do not require a system Gradle installation. Java 25 is the minimum
compile and runtime version. Production and test compilation use `--release 25`.
The root and `build-logic` `gradle/gradle-daemon-jvm.properties` files deliberately select a
Microsoft JDK 21 Gradle daemon so Palantir Java Format 2.97.0 runs on its supported launcher. Do not
remove or bypass Spotless; Gradle still launches JDK 25 for build-logic functional tests and all
project compile/test toolchains, and the existing build cache remains enabled.

## Project structure

- `build-logic/`: included build containing convention and architecture plugins.
- `agent-framework-core`: provider-neutral models, options, run control, and serialization SPI.
- `agent-framework-tools`: tool metadata and execution; depends on core.
- `agent-framework-tools-shell`: approval-gated local and Docker-compatible shell execution,
  bounded output, policy guardrails, and shell-environment context; depends on agents.
- `agent-framework-codeact`: approval-gated, shell-backed CodeAct runs with deterministic
  transcripts; depends on tools-shell.
- `agent-framework-agents`: agents, chat client, sessions, middleware, context, history, and the
  provider-neutral Skills model/source/provider runtime; depends on tools and core.
- `agent-framework-declarative`: YAML-backed declarative agent definitions, validation, and runtime
  materialization; depends on agents.
- `agent-framework-evaluation`: provider-neutral evaluator, scenario, scoring, run, and report
  contracts; depends on agents and workflows.
- `agent-framework-harness`: autonomous loop assembly, session-persisted todo/mode state,
  session-scoped file memory, shared file access, and background-agent lifecycle; depends on agents.
- `agent-framework-workflows`: workflow graph and checkpoint runtime; depends on agents.
- `agent-framework-workflows-declarative`: YAML-backed declarative workflow definitions and runtime
  materialization; depends on workflows.
- `agent-framework-orchestrations`: higher-level orchestration patterns; depends directly on agents.
- `agent-framework-observability`: optional telemetry decorators; depends inward on agents.
- `agent-framework-reactor-adapter`: optional Reactor bridge; depends inward on agents.
- `agent-framework-mcp`: official MCP Java SDK client, `FunctionTool` adapter, and SEP-2640
  `skill://index.json`/archive Skills source; SDK and Reactor types remain internal.
- `agent-framework-hosting-mcp`: stdio and embedded Streamable HTTP/SSE hosting for framework tools,
  agents, prompts, and resources; depends inward on agents and MCP.
- `agent-framework-a2a`: framework-owned A2A v1 model, secure JDK JSON-RPC/SSE client, and remote
  `A2AAgent`; official SDK types remain test-only.
- `agent-framework-hosting-a2a`: principal-isolated Agent/Workflow hosting, bounded stores, card
  generation, and embedded loopback-first JSON-RPC/SSE server; depends inward on agents, workflows,
  and A2A.
- `agent-framework-agui`: framework-owned AG-UI 0.0.57 model, strict JSON/NDJSON/SSE codec, event
  validator, converters, and redirect-free JDK HTTP/SSE client.
- `agent-framework-hosting-agui`: principal-isolated Agent/Workflow/Orchestration AG-UI routes,
  CAS/TTL thread store, generic dispatcher continuations, and loopback-first JDK HTTP/SSE server.
- `agent-framework-hosting-agui-spring`: opt-in Spring WebFlux AG-UI route adapter; reuses generic
  hosting beans and excludes embedded Tomcat.
- `agent-framework-hosting`: generic provider-neutral registry, dispatcher, strict wire codec,
  principal/isolation, bounded run, and one-time continuation support.
- `agent-framework-hosting-http`: embedded loopback-first JSON/SSE/WebSocket transport; depends
  inward on generic hosting and keeps Tomcat types internal.
- `agent-framework-hosting-openai`: strict framework-owned OpenAI Responses HTTP/SSE hosting
  contract; depends on generic and HTTP hosting.
- `agent-framework-hosting-telegram`: hardened Telegram webhook and Bot API hosting adapter;
  depends on generic hosting.
- `agent-framework-hosting-spring`: opt-in Spring Boot WebFlux JSON/SSE adapter; keeps Reactor
  internal and documents WebSocket as embedded-host-only.
- `agent-framework-devui`: loopback-by-default development UI over generic hosting contracts.
- `agent-framework-chatkit`: strict framework-owned ChatKit wire models, conversion, attachments,
  and streaming.
- `agent-framework-openai`: official OpenAI Responses provider adapter; depends inward on agents and
  keeps SDK types out of public signatures.
- `agent-framework-azure-openai`: Azure OpenAI Responses adapter with API-key or Azure Identity
  authentication; reuses the OpenAI protocol boundary without exposing Azure service models.
- `agent-framework-foundry`: Microsoft Foundry direct-model and existing-agent adapter using the GA
  Azure AI Projects/Agents SDKs; persistent thread/run and hosted-session surfaces are deferred.
- `agent-framework-anthropic`: official Anthropic Java Messages SDK adapter.
- `agent-framework-bedrock`: AWS SDK v2 Bedrock Runtime Converse/ConverseStream adapter.
- `agent-framework-gemini`: official Google Gen AI Java SDK adapter for Gemini Developer API and
  Vertex AI authentication modes.
- `agent-framework-mistral`: redirect-free bounded JDK HTTP/JSON/SSE Mistral Chat Completions adapter.
- `agent-framework-ollama`: loopback-first redirect-free JDK HTTP/JSON/NDJSON Ollama `/api/chat`
  adapter.
- `agent-framework-foundry-local`: process-neutral Foundry Local Chat Completions/status/catalog
  integration; it never installs or starts native binaries.
- `agent-framework-github-copilot`: official stable Java SDK `1.0.9` as the sole
  protocol/RPC/session/event/tool/hook/MCP implementation behind framework-owned lifecycle,
  Agent, ChatClient, cancellation, limits, and public API isolation.
- `agent-framework-copilotstudio`: strict redirect-free Power Platform Direct-to-Engine
  `2022-03-01-preview` HTTP/SSE client with Activity/Card/Citation and Agent/ChatClient APIs.
- `agent-framework-valkey`: official GLIDE-backed standalone Valkey history with framework-owned
  options, bounded versioned messages, atomic idempotent append, and internal SDK types.
- `agent-framework-mem0`: Mem0 Platform V3 add/search/list and V1 scoped-clear/event integration
  through a strict redirect-free JDK HTTP context provider; unscoped item mutation is not exposed.
- `agent-framework-azure-ai-search`: official stable Azure AI Search SDK-backed full-text, vector,
  hybrid, semantic, and existing-knowledge-base agentic retrieval with mandatory tenant/scope
  filters and bounded untrusted context injection.
- `agent-framework-conformance`: non-published, implementation-neutral fixtures and test support;
  production modules must not depend on it.
- `agent-framework-bom`: Java Platform constraints for the shared release version.

Never reverse the dependency direction. Provider, protocol, hosting, storage, observability, and
ecosystem adapters must depend on the smallest shared module they require; shared runtime modules
must never depend on adapters.

## Java and API conventions

- Public packages use `com.microsoft.agents.<capability>`.
- Types use `PascalCase`, methods and fields use `lowerCamelCase`, and constants use
  `UPPER_SNAKE_CASE`. Interfaces do not use an `I` prefix.
- Finite asynchronous methods returning `CompletionStage<T>` use an `Async` suffix.
- Streaming methods returning `Flow.Publisher<T>` use a `Streaming` suffix.
- Synchronous facades use an unsuffixed operation name and derive from the same execution core.
- Do not use preview APIs, add preview compiler/runtime flags, or reference `StructuredTaskScope`.
- Shared public APIs must not expose provider SDK, Spring AI, LangChain4j, Reactor, or
  OpenTelemetry types.
- Do not add `module-info.java` unless a later approved ADR requires JPMS.

## Tests and quality

- Put tests under `src/test/java` and suffix test classes with `Test`.
- Use JUnit 5, AssertJ, and Mockito. Keep tests independent and use Arrange/Act/Assert comments.
- Run `./gradlew check`; it includes compilation, tests, Checkstyle, Spotless, JaCoCo reports, the
  no-preview guard, dependency-direction checks, package ownership, and shared API isolation.
- Every `.java` production and test file starts with
  `// Copyright (c) Microsoft. All rights reserved.`
- Every public or protected API requires Javadoc. The first sentence must be a complete summary.
