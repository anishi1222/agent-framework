# Azure AI Content Understanding

`agent-framework-azure-content-understanding` uses the stable
`com.azure:azure-ai-contentunderstanding:1.0.0` SDK and its `2025-11-01` service API. It is distinct
from Azure AI Document Intelligence.

The module provides framework-owned URL/byte inputs, analyzer definitions, operation status,
analysis results, extracted document/content values, warnings, usage, and bounded pages. Analysis
and analyzer creation return framework `RunHandle` instances; the SDK poller is configured with a
bounded interval and total timeout, and logical cancellation cancels HTTP/polling subscriptions.
The stable SDK exposes no remote operation-cancel endpoint, so cancellation does not claim remote
resource deletion.

URLs must use HTTPS, reject local/private literal targets, and redact query strings in diagnostics.
SAS/query data is sent only to the service and is never retained in errors or telemetry. Byte input,
input count, JSON, page, and page-count limits are configurable and enforced. Service endpoints are
restricted to recognized Azure Foundry/Cognitive Services HTTPS resource origins. Redirect behavior,
retry count, and HTTP logging are controlled by the Azure SDK pipeline; body logging is disabled.

The client never auto-deletes analyzers, analysis results, or caller content. Callers must invoke
explicit deletion for resources they own.

## Public API

- `AzureContentUnderstandingClient` and `AzureContentUnderstandingOptions` expose bounded analysis
  LROs plus analyzer create/get/update/delete/list operations.
- `ContentUrlInput` and `ContentBytesInput` separate URL and byte safety rules.
- `ContentAnalysisRequest`, `ContentAnalysisResult`, `AnalyzedContent`, `ContentAnalyzerRequest`,
  `ContentAnalyzerDefinition`, `ContentOperationStatus`, and `ContentUnderstandingPage<T>` are
  framework-owned; Azure SDK models remain internal.

The client requests `https://cognitiveservices.azure.com/.default`; production applications should
use managed identity or `AzureAuthenticationProviders.productionDefaultCredential()` and grant the
least-privileged **Cognitive Services User** role. The endpoint must be a recognized Azure Foundry
or Cognitive Services HTTPS resource origin. This library performs data-plane calls only and never
provisions resources or role assignments.

Analyzer deletion is available only through explicit `deleteAnalyzerAsync`. The stable SDK also
contains result-file/result-deletion operations, but this initial framework facade does not expose
them; callers that require early result deletion must use the SDK directly and retain ownership of
that lifecycle. Offline tests use an injected Azure SDK HTTP client and make no live Azure calls.
