# Agent Framework ChatKit for Java

`agent-framework-chatkit` is a self-hosted backend adapter. It translates a deliberately small,
framework-owned ChatKit wire subset into Agent Framework messages and translates streamed
`AgentResponseUpdate` values back into ChatKit thread events. No OpenAI, Python, JavaScript, or
browser ChatKit SDK type appears in the public Java API.

This module does not replace the ChatKit browser frontend's own OpenAI connectivity, deployment,
origin/domain allow-list, or content-security requirements. Those frontend constraints still apply
to the browser application and its configured OpenAI services.

## Supported protocol subset

Inbound thread items:

- `user_message`: ordered `text` parts, optional `quoted_text`, and ordered `image` or `file`
  attachments.
- `assistant_message`: ordered `output_text` parts with empty annotations.
- `hidden_context_item` and `sdk_hidden_context`: converted to a system message wrapped in
  `<HIDDEN_CONTEXT>...</HIDDEN_CONTEXT>`.

Outbound events:

- `thread.item.added` once after the first agent update.
- `thread.item.updated` for each ordered `TextContent` delta, using content index `0`.
- `thread.item.done` once after normal completion, containing the accumulated text.
- An empty agent stream emits no events. An errored stream emits no item-done event.

Unknown fields on supported objects and unsupported item discriminators are rejected by default.
`ChatKitJsonCodec` can instead ignore bounded unknown fields or retain a metadata-only
`ChatKitUnsupportedThreadItem` marker. Unsupported payloads are never retained or re-encoded.

## Attachments

`ChatKitItemConverter` resolves attachments sequentially to preserve deterministic ordering:

1. An injected `ChatKitAttachmentFetcher` may asynchronously return bounded bytes.
2. If fetching is absent or fails, an HTTPS preview URI is used only when an explicit
   `ChatKitAttachmentUriPolicy` exact-host allow-list accepts it.
3. Otherwise the attachment is skipped by default, or conversion fails when
   `failOnAttachmentError` is enabled.

The module never performs remote HTTP fetching and never logs attachment content or credentials.

## Streaming and JSON safety

`ChatKitStreamingPublisher` is cold and single-subscriber. It uses no executor, requests one source
update at a time, honors downstream demand exactly, forwards cancellation, and bounds queued
events. One buffer slot is reserved for the final item-done event.

`ChatKitJsonCodec` rejects duplicate keys, trailing tokens, non-finite numbers, and configured
document, string, collection, numeric, and nesting-limit violations. Encoding recursively sorts
object keys and preserves array order.

## Dependencies

- Project API: `:agent-framework-core`
- Internal runtime: Jackson Databind through the repository version catalog
- Tests: JUnit 5 and AssertJ supplied by the Java convention plugin

The module-local settings include `:agent-framework-conformance` only because the mapped core
project references it during Gradle configuration; it is not a ChatKit production dependency.

## Isolated validation

From this directory:

```bash
../gradlew --no-daemon :spotlessApply
../gradlew --no-daemon :test
../gradlew --no-daemon :check
```

The module is registered in the shared Java build, published with the release train, and constrained
by `agent-framework-bom`.
