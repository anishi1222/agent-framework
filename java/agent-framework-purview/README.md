# Microsoft Purview policy integration

`agent-framework-purview` ports the actual .NET/Python behavior: it is a chat/agent data-governance
middleware and Microsoft Graph `dataSecurityAndGovernance` policy client, not a catalog or analytics
service.

The integration calls these Microsoft Graph v1.0 APIs:

- `POST /users/{userId}/dataSecurityAndGovernance/protectionScopes/compute`
- `POST /users/{userId}/dataSecurityAndGovernance/processContent`
- `POST /users/{userId}/dataSecurityAndGovernance/activities/contentActivities`

It caches ETag-bearing protection scopes, applies the most restrictive matching execution mode,
waits for inline evaluation, schedules bounded offline/audit work, invalidates modified scopes, and
blocks both finite prompts and finite responses when DLP actions require it. Streaming ingress is
checked before subscription; streaming egress is not post-evaluated because partial output cannot be
recalled, so applications requiring egress enforcement must use finite runs or buffer output at a
trusted boundary.

`PurviewFailureMode` makes fail-open versus fail-closed behavior explicit, including a separate
payment-required setting. Fail-closed is the default. Auth uses the framework-owned
`AzureAuthenticationProvider`; production applications should select managed identity or
`productionDefaultCredential()` and grant the documented Graph permissions. User and tenant IDs are
resolved from a user token when possible or supplied through trusted message/run metadata for app
tokens.

Only verified Microsoft Graph HTTPS v1.0 endpoints are accepted. Redirects are disabled, retries and
background work are bounded, ETags use `If-None-Match`, and errors preserve sanitized status,
request ID, code, and retry-after. Telemetry contains only operation name, status, duration, success,
and request ID—never content, identities, policy payloads, or credentials. Caller-provided
credentials, executors, schedulers, evaluators, and clients remain caller-owned.

## Public API

- `PurviewClient` implements the three Graph data-security-and-governance calls with bounded,
  cancellable JDK HTTP.
- `PurviewPolicyEvaluator` applies cached scopes, most-restrictive execution mode, local DLP actions,
  bounded offline work, and cache invalidation.
- `PurviewPolicyMiddleware<T>` and `PurviewChatPolicyMiddleware` enforce prompt and finite-response
  policies at agent and chat boundaries.
- `PurviewSettings`, `PurviewFailureMode`, `PurviewProtectionScopes`, `PurviewDecision`,
  `PurviewPolicyAction`, and the remaining request/location/telemetry types are framework-owned.

Use least-privileged Microsoft Graph permissions appropriate to the enabled calls:
`ProtectionScopes.Compute.User`, `Content.Process.User`, and `ContentActivity.Write`; only the first
two have documented `.All` alternatives for applications that truly require tenant-wide scope. Production
applications should use managed identity or
`AzureAuthenticationProviders.productionDefaultCredential()` and grant only the required delegated
or application permissions. This module does not provision Purview policies, Graph permissions, or
Entra identities. Content is sent to Graph only for policy enforcement; it is never copied into
telemetry or exception messages.
