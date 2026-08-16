// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class UserAgentTelemetryTest {
    @Test
    void registry_shouldAccumulateDeduplicateAndEncodeAll128Bits() throws Exception {
        // Arrange
        FeatureUsageRegistry registry = new FeatureUsageRegistry();

        // Act
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.invokeAll(IntStream.range(0, 128)
                    .mapToObj(index -> (java.util.concurrent.Callable<Void>) () -> {
                        registry.markUsed(index);
                        registry.markUsed(index);
                        return null;
                    })
                    .toList());
        }

        // Assert
        assertThat(registry.token()).contains("v1.ffffffffffffffffffffffffffffffff");
        assertThat(registry.isMarked(0)).isTrue();
        assertThat(registry.isMarked(127)).isTrue();
    }

    @Test
    void registry_shouldRejectOutOfRangeIndexesAndSuppressDisabledMask() {
        FeatureUsageRegistry enabled = new FeatureUsageRegistry();
        FeatureUsageRegistry disabled = new FeatureUsageRegistry(1, false);

        assertThatThrownBy(() -> enabled.markUsed(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0..127");
        assertThatThrownBy(() -> enabled.markUsed(128))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0..127");

        disabled.markUsed(0);
        assertThat(disabled.token()).isEmpty();
        assertThat(UserAgentUtil.applyFeatureToken("agent-framework-java/1.0 (feat=v1.5)", disabled))
                .isEqualTo("agent-framework-java/1.0");
    }

    @Test
    void featureIndex_shouldValidatePublishedValueAndIdentifierShape() {
        assertThat(new FeatureUsageIndex(56, "openai")).isEqualTo(new FeatureUsageIndex(56, "openai"));
        assertThatThrownBy(() -> new FeatureUsageIndex(128, "future"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0..127");
        assertThatThrownBy(() -> new FeatureUsageIndex(1, "Customer Value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lowercase");
    }

    @Test
    void registry_shouldExposeOnlyTypedPublicMarking() {
        assertThat(Arrays.stream(FeatureUsageRegistry.class.getMethods())
                        .filter(method -> method.getName().equals("markUsed"))
                        .map(method -> List.of(method.getParameterTypes()))
                        .toList())
                .containsExactly(List.of(FeatureUsageIndex.class));
    }

    @Test
    void userAgent_shouldComposePrefixesAndRefreshOnlyApprovedOrigins() {
        // Arrange
        FeatureUsageRegistry registry = new FeatureUsageRegistry();
        registry.markUsed(0);
        String base = UserAgentUtil.frameworkUserAgent("1.2.3", List.of("outer", "foundry-hosting", "outer"));
        String stale = base + " (custom=value) (feat=v1.80)";
        List<String> suffixes = List.of("cognitiveservices.azure.com", "openai.azure.com", "services.ai.azure.com");

        // Act
        String approved = UserAgentUtil.stampFeatureToken(
                stale, URI.create("https://resource.openai.azure.com/openai/v1/responses"), suffixes, registry);
        String denied = UserAgentUtil.stampFeatureToken(
                approved, URI.create("https://gateway.example.com/openai/v1/responses"), suffixes, registry);

        // Assert
        assertThat(base).isEqualTo("foundry-hosting/outer/agent-framework-java/1.2.3");
        assertThat(approved).isEqualTo(base + " (custom=value) (feat=v1.1)");
        assertThat(denied).isEqualTo(base + " (custom=value)");
    }

    @Test
    void headers_shouldBeCopiedCaseInsensitivelyWithoutMutatingCallerState() {
        // Arrange
        Map<String, String> headers = Map.of("user-agent", "sdk/1.0", "Accept", "application/json");

        // Act
        Map<String, String> result = UserAgentUtil.withFrameworkUserAgent(headers);

        // Assert
        assertThat(result.get("user-agent")).startsWith("agent-framework-java/").endsWith(" sdk/1.0");
        assertThat(result).containsEntry("Accept", "application/json");
        assertThat(headers.get("user-agent")).isEqualTo("sdk/1.0");
        assertThatThrownBy(() -> result.put("Other", "value")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void userAgent_shouldRejectHeaderInjectionAndInvalidOrigins() {
        assertThatThrownBy(() -> UserAgentUtil.prependFrameworkUserAgent("sdk/1.0\r\nInjected: true"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control characters");
        assertThat(UserAgentUtil.isApprovedHttpsOrigin(
                        URI.create("http://resource.openai.azure.com"), List.of("openai.azure.com")))
                .isFalse();
        assertThat(UserAgentUtil.isApprovedHttpsOrigin(
                        URI.create("https://openai.azure.com.example.org"), List.of("openai.azure.com")))
                .isFalse();
    }
}
