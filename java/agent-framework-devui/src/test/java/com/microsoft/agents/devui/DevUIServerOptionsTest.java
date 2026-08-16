// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.devui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.hosting.HostingAuthentication;
import com.microsoft.agents.hosting.HostingPrincipal;
import java.net.InetAddress;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class DevUIServerOptionsTest {
    @Test
    void options_shouldDefaultToLoopbackAndEphemeralPort() {
        // Arrange / Act
        DevUIServerOptions options = DevUIServerOptions.builder().build();

        // Assert
        assertThat(options.bindAddress().isLoopbackAddress()).isTrue();
        assertThat(options.port()).isZero();
        assertThat(options.transportSecurity()).isEqualTo(DevUITransportSecurity.LOOPBACK_HTTP);
        assertThat(options.allowNonLoopback()).isFalse();
        assertThat(options.allowedHosts()).contains("localhost:*", "127.0.0.1:*", "[::1]:*");
    }

    @Test
    void options_shouldRequireExplicitNonLoopbackOptIn() throws Exception {
        // Arrange
        InetAddress anyAddress = InetAddress.getByName("0.0.0.0");

        // Act / Assert
        assertThatThrownBy(() -> DevUIServerOptions.builder()
                        .bindAddress(anyAddress)
                        .transportSecurity(DevUITransportSecurity.TRUSTED_TLS_PROXY)
                        .advertisedEndpoint(URI.create("https://devui.example.com"))
                        .allowedHosts(Set.of("devui.example.com"))
                        .allowedOrigins(Set.of("https://devui.example.com"))
                        .authenticator(request -> CompletableFuture.completedFuture(
                                HostingAuthentication.authenticated(new HostingPrincipal("developer", "tenant"))))
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("allowNonLoopback=true");
    }

    @Test
    void options_shouldRetainGenericHostingRemoteSecurityRequirements() throws Exception {
        // Arrange
        InetAddress anyAddress = InetAddress.getByName("0.0.0.0");

        // Act / Assert
        assertThatThrownBy(() -> DevUIServerOptions.builder()
                        .bindAddress(anyAddress)
                        .allowNonLoopback(true)
                        .transportSecurity(DevUITransportSecurity.TRUSTED_TLS_PROXY)
                        .build())
                .isInstanceOf(ValidationException.class);

        DevUIServerOptions options = DevUIServerOptions.builder()
                .bindAddress(anyAddress)
                .allowNonLoopback(true)
                .transportSecurity(DevUITransportSecurity.TRUSTED_TLS_PROXY)
                .advertisedEndpoint(URI.create("https://devui.example.com"))
                .allowedHosts(Set.of("devui.example.com"))
                .allowedOrigins(Set.of("https://devui.example.com"))
                .authenticator(request -> CompletableFuture.completedFuture(
                        HostingAuthentication.authenticated(new HostingPrincipal("developer", "tenant"))))
                .build();

        assertThat(options.bindAddress()).isEqualTo(anyAddress);
        assertThat(options.allowNonLoopback()).isTrue();
        assertThat(options.advertisedEndpoint()).isEqualTo(URI.create("https://devui.example.com"));
    }
}
