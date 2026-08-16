// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TelegramOptionsTest {
    @Test
    void webhookOptions_shouldRedactSecretAndValidateTelegramTokenShape() {
        TelegramWebhookOptions options = TelegramWebhookOptions.builder()
                .botId(123)
                .routeId("agent")
                .webhookSecretToken("safe_secret-token")
                .build();

        assertThat(options.toString()).contains("webhookSecretToken=[REDACTED]").doesNotContain("safe_secret-token");
        assertThatThrownBy(() -> TelegramWebhookOptions.builder()
                        .botId(123)
                        .routeId("agent")
                        .webhookSecretToken("contains space")
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void webhookOptions_shouldRejectInvalidBounds() {
        assertThatThrownBy(() -> TelegramWebhookOptions.builder()
                        .botId(123)
                        .routeId("agent")
                        .webhookSecretToken("secret")
                        .maxOutboundTextLength(4097)
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TelegramWebhookOptions.builder()
                        .botId(123)
                        .routeId("agent")
                        .webhookSecretToken("secret")
                        .maxStringLength(10)
                        .maxInboundTextLength(11)
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TelegramWebhookOptions.builder()
                        .botId(123)
                        .routeId("agent")
                        .webhookSecretToken("secret")
                        .processingTimeout(Duration.ZERO)
                        .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sendMessageRequest_shouldRejectOverflowAndSplitSurrogate() {
        assertThatThrownBy(() -> new TelegramSendMessageRequest(123, "x".repeat(4097)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TelegramSendMessageRequest(123, "\ud83d"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TelegramSendMessageRequest(123, "\ude00"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TelegramSendMessageRequest(123, "A\ud83dB"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boundedText_shouldPreserveSplitPairsAndDropUnpairedSurrogates() {
        TelegramResponseText.BoundedText split = new TelegramResponseText.BoundedText(2);
        split.append("\ud83d");
        split.append("\ude00");

        TelegramResponseText.BoundedText invalid = new TelegramResponseText.BoundedText(4);
        invalid.append("\ud83d");
        invalid.append("A\ude00B");

        TelegramResponseText.BoundedText boundary = new TelegramResponseText.BoundedText(3);
        boundary.append("A😀B");

        TelegramResponseText.BoundedText insufficient = new TelegramResponseText.BoundedText(2);
        insufficient.append("A😀");

        TelegramResponseText.BoundedText boundedFallback = new TelegramResponseText.BoundedText(6);
        boundedFallback.append(" \ud83d");

        assertThat(split.finish()).isEqualTo("😀");
        assertThat(invalid.finish()).isEqualTo("AB");
        assertThat(boundary.finish()).isEqualTo("A😀");
        assertThat(insufficient.finish()).isEqualTo("A");
        assertThat(boundedFallback.finish()).isEqualTo("(no re");
    }
}
