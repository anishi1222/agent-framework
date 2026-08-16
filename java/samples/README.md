# Java samples

These samples run without credentials or network access. They use deterministic in-process
`ChatClient` implementations so CI verifies the same public APIs applications use with production
providers.

| Sample | Demonstrates |
|---|---|
| `01` | Creating and running a `ChatAgent` |
| `02` | Exactly-once function-tool invocation |
| `03` | Building and running a typed workflow |
| `04` | Sequential multi-agent orchestration |
| `05` | Assembling the autonomous Harness facade |

Run all samples:

```bash
./gradlew :samples:checkSamples
```

Run one sample:

```bash
./gradlew :samples:runSample03
```

Replace the in-process clients with an adapter such as `agent-framework-openai`,
`agent-framework-azure-openai`, or `agent-framework-foundry` when connecting to a model service.
