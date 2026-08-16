# Azure authentication

`agent-framework-azure-authentication` provides framework-owned token request and authentication
contracts for Azure integrations. Azure SDK credential types remain internal.

For production, use `AzureAuthenticationProviders.productionDefaultCredential()` with
`AZURE_TOKEN_CREDENTIALS=prod`, or select managed identity explicitly. Caller-provided
`AzureAuthenticationProvider` instances remain caller-owned and tokens are never rendered by
framework diagnostic strings.

The module pins `com.azure:azure-identity:1.18.4`. Public APIs are
`AzureAuthenticationProvider`, `AzureAuthenticationProviders`, `AzureTokenRequest`,
`AzureAccessToken`, and `AzureAuthenticationException`; Azure Identity credential and token types
remain internal. Provider factories never perform Azure resource-management operations. Callers own
injected providers and must grant each integration only its documented data-plane scopes and roles.
