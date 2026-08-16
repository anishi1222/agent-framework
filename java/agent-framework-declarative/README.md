# Agent Framework Declarative Agents for Java

`agent-framework-declarative` provides framework-owned immutable prompt-agent definitions, strict
JSON/YAML loading, and factories that create provider-neutral `Agent<?>` implementations. The
default factory creates `ChatAgent` instances while keeping every `ChatClient`, `Tool`, and
`ContextProvider` caller-owned.

## Definition

```yaml
kind: Prompt
name: support
displayName: Support Agent
description: Answers support questions.
model:
  id: gpt-4.1
  provider: OpenAI
  apiType: Responses
  options:
    temperature: 0.2
    maxOutputTokens: 1024
tools:
  - weather
contextProviders:
  - memory
instructions: Be concise.
additionalInstructions: Cite the source.
```

The parser accepts exactly one JSON or YAML document. It rejects duplicate keys, aliases, unknown
fields, trailing documents/content, unsupported scalar types, invalid option ranges, duplicate
references, and documents beyond bounded parser limits. Public APIs expose only framework and JDK
types; Jackson and SnakeYAML remain implementation details.

## Construction

```java
PromptAgentDefinition definition = DeclarativeAgentParser.parse(path);

PromptAgentFactory factory = new ChatClientPromptAgentFactory(
        ChatClientRegistry.of(Map.of("OpenAI.Responses", responsesClient)),
        ToolRegistry.of(Map.of("weather", weatherTool)),
        ContextProviderRegistry.of(Map.of("memory", memoryProvider)));

Agent<?> agent = factory.create(definition);
```

When `apiType` is present, lookup first uses `provider.apiType` and then `provider`. A factory-level
default provider can be supplied for definitions that omit `model.provider`.
`AggregatorPromptAgentFactory` evaluates factories in registration order and returns the first
supported agent.

Supported model options map directly to `ChatOptions`: `frequencyPenalty`, `maxOutputTokens`,
`presencePenalty`, `seed`, `temperature`, `topP`, `stopSequences`, and
`allowMultipleToolCalls`. Provider-specific settings are intentionally not interpreted by this
module; applications configure their registered `ChatClient` instances directly.

## Parent integration checklist

The parent integration change must:

1. Add `agent-framework-declarative` to `java/settings.gradle.kts`.
2. Add `api(project(":agent-framework-declarative"))` to
   `java/agent-framework-bom/build.gradle.kts`.
3. Add a `JavaModulePolicy` entry allowing only `agent-framework-agents` and owning
   `com.microsoft.agents.declarative`; include the module in shared/public-signature isolation
   checks and update the build-logic policy tests.
4. Add a `snakeyaml = "2.5"` catalog version/library and replace the module's direct coordinate
   with the catalog alias.
5. Add the module to Java module documentation, publication validation, and CI matrices.
6. Mark the prompt-agent parity row implemented only after the integrated root checks pass.

After those edits, run from `java/`:

```bash
./gradlew -p build-logic build
./gradlew :agent-framework-declarative:check
./gradlew checkArchitecture
./gradlew clean build
./gradlew check
./gradlew publishToTestRepository
```

No provider adapter, root build, BOM, shared documentation, or CI file is changed by this module
tree.
