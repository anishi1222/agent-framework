# Agent Framework Telegram webhook hosting for Java

`agent-framework-hosting-telegram` is an opt-in Telegram Bot API adapter around generic
`HostingDispatcher` agent routes. It has no Telegram SDK dependency and exposes only
framework-owned request, response, option, and client types.

## Supported contract

- Incoming HTTPS route registration remains application-owned. Pass a complete bounded request to
  `TelegramWebhookAdapter.handleAsync`.
- The adapter requires `POST`, exact `application/json` with optional UTF-8 charset, and exactly one
  `X-Telegram-Bot-Api-Secret-Token` value matching the configured Telegram `setWebhook`
  `secret_token`.
- Parsing is strict and bounded. Duplicate JSON members, trailing content, excessive bytes,
  nesting, strings, numbers, or collection sizes are rejected.
- The implemented update surface is deliberately narrow: a new `message` containing `message_id`,
  `chat.id`, `chat.type`, `from.id`, and non-empty `text`.
- Other syntactically valid update types, and messages without supported text/user fields, return
  `TelegramWebhookDisposition.UNSUPPORTED` with HTTP 204 and do not dispatch or call Telegram.
- Inbound text becomes one provider-neutral user `Message` and is dispatched to the configured
  `HostingRouteKind.AGENT` route.
- Finite responses concatenate assistant text from the completed generic-hosting result.
  Streaming responses aggregate `AGENT_UPDATE` text under explicit event and character limits.
- Empty text output becomes `(no response)` within the configured output cap. Output is capped
  without splitting a UTF-16 surrogate pair and sent with one Telegram `sendMessage` request.
- Caller disconnect cancellation, adapter close, processing deadlines, generic-hosting run
  cancellation, and outbound client cancellation are linked.

The adapter does not implement polling, webhook registration, commands, callback queries, media,
edits, retries, rate limiting, duplicate-update persistence, or durable/exactly-once delivery.
Telegram retries and application idempotency remain deployment concerns.

## Identity and isolation

The adapter never treats caller-supplied session identifiers as authorization state. For bot `B`,
chat `C`, and sender `U`, it derives:

```text
principalId = telegram:bot:B:user:U
isolationId = telegram:bot:B:chat:C:user:U
```

This prevents cross-bot, cross-chat, and cross-user state sharing. The generic hosting authorizer
still decides whether the derived principal may start the selected route.

## Example

```java
TelegramWebhookOptions webhookOptions = TelegramWebhookOptions.builder()
        .botId(123456789L)
        .routeId("assistant")
        .webhookSecretToken(System.getenv("TELEGRAM_WEBHOOK_SECRET"))
        .streaming(true)
        .build();

TelegramBotClientOptions clientOptions = TelegramBotClientOptions.builder()
        .botToken(System.getenv("TELEGRAM_BOT_TOKEN"))
        .build();

try (JdkTelegramBotClient telegram = new JdkTelegramBotClient(clientOptions);
     TelegramWebhookAdapter adapter =
             new TelegramWebhookAdapter(dispatcher, telegram, webhookOptions)) {
    TelegramWebhookResponse response = adapter.handleAsync(
            new TelegramWebhookRequest(
                    method,
                    headers,
                    boundedBody,
                    disconnectCancellation))
            .toCompletableFuture()
            .join();
    // Write response.statusCode(); no Telegram SDK types are involved.
}
```

`JdkTelegramBotClient` uses JDK `HttpClient` with redirects disabled. The default endpoint is
`https://api.telegram.org/`; non-loopback hosts require HTTPS and an allowlist entry. Loopback HTTP
is available only through explicit test/local-server opt-in.

## Shared build

The module is published with the Java release, constrained by `agent-framework-bom`, and depends
only on `agent-framework-hosting`; it adds no Telegram SDK. Run
`./gradlew :agent-framework-hosting-telegram:check` from `java/` for focused validation.
