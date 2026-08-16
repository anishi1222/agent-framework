// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.valkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.ValidationException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ValkeyOptionsTest {
    @Test
    void defaults_shouldBeBoundedAndStandalone() {
        // Arrange
        ValkeyClientOptions client = ValkeyClientOptions.defaults(new ValkeyEndpoint("localhost", 6379));

        // Act
        ValkeyHistoryOptions options =
                ValkeyHistoryOptions.defaults(client, new ValkeyPartitionContext("tenant", "isolation", "agent"));

        // Assert
        assertThat(client.authentication().kind()).isEqualTo(ValkeyAuthentication.Kind.NONE);
        assertThat(client.useTls()).isFalse();
        assertThat(client.operationTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(options.maxStoredMessages()).isEqualTo(1000);
        assertThat(options.maxLoadedMessages()).isEqualTo(100);
        assertThat(options.maxMessageBytes()).isEqualTo(1024 * 1024);
        assertThat(options.maxDocumentBytes()).isEqualTo(8 * 1024 * 1024);
    }

    @Test
    void aclAndOptionsToString_shouldRedactSecretsAndPartitionIdentifiers() {
        // Arrange
        ValkeyPassword password = ValkeyPassword.of("super-secret-password");
        ValkeyAuthentication authentication = ValkeyAuthentication.acl("sensitive-user", password);
        ValkeyClientOptions client = new ValkeyClientOptions(
                new ValkeyEndpoint("cache.example.test", 6380),
                authentication,
                true,
                "client-a",
                Duration.ofSeconds(3));
        ValkeyHistoryOptions options = new ValkeyHistoryOptions(
                client,
                new ValkeyPartitionContext("tenant-secret", "principal-secret", "agent-secret"),
                "history",
                "namespace",
                50,
                25,
                Duration.ofHours(1),
                4096,
                16_384);

        // Act
        String rendered = password + " " + authentication + " " + client + " " + options;

        // Assert
        assertThat(rendered)
                .contains("REDACTED")
                .doesNotContain(
                        "super-secret-password", "sensitive-user", "tenant-secret", "principal-secret", "agent-secret");
    }

    @Test
    void password_shouldRejectUseAfterCloseAndCloseIdempotently() {
        // Arrange
        ValkeyPassword password = ValkeyPassword.of("secret");

        // Act
        password.close();
        password.close();

        // Assert
        assertThatThrownBy(password::secretValue)
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void endpointAndPrefix_shouldRejectUriSyntaxAndHashTagInjection() {
        assertThatThrownBy(() -> new ValkeyEndpoint("redis://localhost", 6379)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new ValkeyEndpoint("user@localhost", 6379)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new ValkeyEndpoint("localhost", 0)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new ValkeyHistoryOptions(
                        ValkeyClientOptions.defaults(new ValkeyEndpoint("localhost", 6379)),
                        new ValkeyPartitionContext("tenant", "isolation", "agent"),
                        "history",
                        "unsafe:{tag}",
                        10,
                        5,
                        null,
                        1024,
                        2048))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("hash-tag braces");
    }

    @Test
    void options_shouldRejectUnboundedOrInconsistentLimits() {
        ValkeyClientOptions client = ValkeyClientOptions.defaults(new ValkeyEndpoint("localhost", 6379));
        ValkeyPartitionContext partition = new ValkeyPartitionContext("tenant", "isolation", "agent");

        assertThatThrownBy(
                        () -> new ValkeyHistoryOptions(client, partition, "history", "prefix", 0, 1, null, 1024, 2048))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(
                        () -> new ValkeyHistoryOptions(client, partition, "history", "prefix", 5, 6, null, 1024, 2048))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new ValkeyHistoryOptions(
                        client, partition, "history", "prefix", 5, 5, Duration.ZERO, 1024, 2048))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(
                        () -> new ValkeyHistoryOptions(client, partition, "history", "prefix", 5, 5, null, 4096, 2048))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new ValkeyClientOptions(
                        new ValkeyEndpoint("localhost", 6379),
                        ValkeyAuthentication.none(),
                        false,
                        null,
                        Duration.ofNanos(1)))
                .isInstanceOf(ValidationException.class);
    }
}
