// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.http;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.hosting.HostingAuthentication;
import com.microsoft.agents.hosting.HostingPrincipal;
import java.net.InetAddress;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class HostingHttpServerOptionsTest {
    @Test
    void options_shouldRejectUnsafeRemoteBindingAndAdvertisedUris() throws Exception {
        // Arrange
        InetAddress remote = InetAddress.getByName("0.0.0.0");

        // Act / Assert
        assertThatThrownBy(() ->
                        HostingHttpServerOptions.builder().bindAddress(remote).build())
                .isInstanceOf(com.microsoft.agents.core.ValidationException.class);
        assertThatThrownBy(() -> HostingHttpServerOptions.builder()
                        .bindAddress(remote)
                        .transportSecurity(HostingTransportSecurity.TRUSTED_TLS_PROXY)
                        .advertisedEndpoint(URI.create("https://user@example.com?redirect=evil"))
                        .allowedHosts(Set.of("example.com"))
                        .allowedOrigins(Set.of("https://app.example.com"))
                        .authenticator(request -> CompletableFuture.completedFuture(
                                HostingAuthentication.authenticated(new HostingPrincipal("user", "tenant"))))
                        .build())
                .isInstanceOf(com.microsoft.agents.core.ValidationException.class);
    }

    @Test
    void options_shouldRequireRemoteAuthenticatorHostsOriginsAndHttps() throws Exception {
        // Arrange
        InetAddress remote = InetAddress.getByName("0.0.0.0");

        // Act / Assert
        assertThatThrownBy(() -> HostingHttpServerOptions.builder()
                        .bindAddress(remote)
                        .transportSecurity(HostingTransportSecurity.TRUSTED_TLS_PROXY)
                        .advertisedEndpoint(URI.create("http://example.com"))
                        .allowedHosts(Set.of("example.com"))
                        .allowedOrigins(Set.of("https://app.example.com"))
                        .build())
                .isInstanceOf(com.microsoft.agents.core.ValidationException.class);
    }

    @Test
    void options_shouldRejectCredentialHeadersAsTrustedContext() {
        assertThatThrownBy(() -> HostingHttpServerOptions.builder()
                        .trustedHeaderNames(Set.of("authorization"))
                        .build())
                .isInstanceOf(com.microsoft.agents.core.ValidationException.class);
    }
}
