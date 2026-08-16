# Agent Framework Declarative Workflows for Java

`agent-framework-workflows-declarative` provides framework-owned immutable JSON/YAML workflow graph
definitions and resolves them to caller-owned typed executors and conditions. Construction always
goes through the production `WorkflowBuilder`, so missing nodes, duplicate routes, unreachable
nodes, disallowed cycles, fan-in/fan-out rules, and payload type compatibility use the same runtime
validation as programmatic workflows.

PowerFx is intentionally excluded. Conditions are stable logical references to caller-supplied
typed Java predicates.

## Definition

```yaml
kind: Workflow
id: support-flow
schemaVersion: 1
allowCycles: false
entry: start
output: finish
nodes:
  - id: start
    executor: normalize
  - id: answer
    executor: answer
  - id: finish
    executor: finish
edges:
  - kind: conditional
    source: start
    target: answer
    condition: valid
  - kind: direct
    source: answer
    target: finish
```

Supported edge kinds are `direct`, `conditional`, `fanOut`, and `fanIn`. Node definitions contain
only stable IDs and executor registry references. JSON/YAML parsing rejects duplicate keys, aliases,
unknown fields, unsupported edge kinds, trailing documents/content, invalid field types, duplicate
node IDs, missing node references, duplicate routes, and invalid fan-in/fan-out membership.

## Construction

```java
DeclarativeWorkflowDefinition definition =
        DeclarativeWorkflowParser.parse(path);

WorkflowComponentRegistry registry = new WorkflowComponentRegistry(
        Map.of(
                "normalize", normalizeExecutor,
                "answer", answerExecutor,
                "finish", finishExecutor),
        Map.of("valid", new WorkflowCondition<>(NormalizedInput.class, NormalizedInput::isValid)));

Workflow<Input, Output> workflow =
        new DeclarativeWorkflowBuilder(registry).build(definition, Input.class, Output.class);
```

Executors, predicates, and an optional `ExecutorService` remain caller-owned. Nodes and edges are
added in definition order; the production builder canonicalizes the immutable graph for stable node
ordering, edge ordering, and graph fingerprints.

## Parent integration checklist

The parent integration change must:

1. Add `agent-framework-workflows-declarative` to `java/settings.gradle.kts`.
2. Add `api(project(":agent-framework-workflows-declarative"))` to
   `java/agent-framework-bom/build.gradle.kts`.
3. Add a `JavaModulePolicy` entry allowing only `agent-framework-workflows` and owning
   `com.microsoft.agents.workflows.declarative`; include the module in shared/public-signature
   isolation checks and update the build-logic policy tests.
4. Add a `snakeyaml = "2.5"` catalog version/library and replace the module's direct coordinate
   with the catalog alias.
5. Add the module to Java module documentation, publication validation, and CI matrices.
6. Add conformance fixtures for direct, conditional, fan-out, fan-in, malformed, unknown-field,
   missing-reference, duplicate-route, unreachable-node, cycle, and type-incompatibility cases.
7. Mark the declarative workflow parity row implemented only after the integrated root checks pass.

After those edits, run from `java/`:

```bash
./gradlew -p build-logic build
./gradlew :agent-framework-workflows-declarative:check
./gradlew :agent-framework-conformance:test
./gradlew checkArchitecture
./gradlew clean build
./gradlew check
./gradlew publishToTestRepository
```

No root build, settings, BOM, shared documentation, existing module, or CI file is changed by this
module tree.
