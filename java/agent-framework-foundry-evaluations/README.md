# Foundry evaluations

`agent-framework-foundry-evaluations` is a cloud integration over the Foundry project endpoint. It
uses `com.azure:azure-ai-projects:2.3.0` for connection, dataset, deployment, index, and explicitly
enabled preview evaluator discovery. Evaluation definitions, runs, cancellation, polling, and output
pagination use the project-scoped OpenAI Evals paths under `/openai/v1/evals`.

All public request, status, result, dataset, evaluator, resource, and page types are framework-owned.
Finite operations use `CompletionStage`; `startRun` returns a framework `RunHandle`. Polling is
scheduled with bounded exponential delays and a total timeout. Cancelling or timing out a run
started through `startRun` requests best-effort service cancellation. Public `awaitRunAsync`
observes an existing run and stops only its local poller by default; callers must select the explicit
`cancelRemoteOnTimeoutOrCancellation` overload to cancel the service run. Unknown future statuses
fail as protocol errors rather than becoming successful results. Pagination is bounded and rejects
repeated cursors.

Evaluator management in Azure Projects 2.3.0 is marked beta and requires
`previewEvaluatorManagement(true)`; the module makes no GA claim for it. This module intentionally
does not define the future provider-neutral evaluator framework.

The client never closes caller authentication providers, executors, or schedulers. It closes only
framework-created executors and schedulers. HTTP redirects are disabled, endpoints require the
Foundry HTTPS project shape, payloads are bounded, and service errors retain sanitized status,
request ID, code, and retry-after without retaining raw bodies or tokens.

## Public API

- `FoundryEvaluationClient` exposes evaluation create/get/delete, run create/get/cancel/start/await,
  bounded output-item pagination, and Foundry project connection/dataset/deployment/index discovery.
- `FoundryEvaluationRequest`, `FoundryEvaluationRunRequest`, `FoundryEvaluation`,
  `FoundryEvaluationRun`, `FoundryEvaluationResult`, `FoundryEvaluationOutputItem`,
  `FoundryEvaluationPage<T>`, and `FoundryEvaluationStatus` are framework-owned.
- `FoundryDataset`, `FoundryEvaluator`, and `FoundryProjectResource` provide SDK-isolated discovery
  projections.

The module pins `com.azure:azure-ai-projects:2.3.0`, its GA `v1` project API, and
`com.openai:openai-java:4.50.0` for repository-wide OpenAI convergence. Evaluation lifecycle uses
the documented project-scoped `/openai/v1/evals` strict transport and requests
`https://ai.azure.com/.default`; discovery uses real Azure Projects async clients. Production
applications should use managed identity or
`AzureAuthenticationProviders.productionDefaultCredential()` with least privilege.

The client never creates or deletes Foundry projects, connections, deployments, datasets, indexes,
or evaluators. Evaluation definitions are deleted only by explicit `deleteEvaluationAsync`; run
deletion is not exposed, while run cancellation is explicit and best effort after local
cancellation/timeout only for start-and-await or explicit remote-cancel observation. Tests use
injected JDK and Azure SDK HTTP clients and make no live Azure calls.
