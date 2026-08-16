// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.valkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import glide.api.GlideClient;
import glide.api.models.exceptions.ClosingException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class GlideValkeyClientFactoryTest {
    @Test
    void lateCreation_shouldCloseClientOnceWhenCancellationWinsBeforeRaceSetup() throws Exception {
        // Arrange
        CompletableFuture<GlideClient> creation = new CompletableFuture<>();
        DefaultRunCancellation cancellation = new DefaultRunCancellation();
        assertThat(cancellation.cancel()).isTrue();

        // Act
        CompletionStage<GlideClient> result = ValkeyAsyncSupport.race(
                creation, Duration.ofSeconds(5), cancellation, GlideValkeyClientFactory::closeLateClient);
        Throwable thrown = catchThrowable(() -> result.toCompletableFuture().join());
        GlideClient client = mock(GlideClient.class);
        assertThat(creation.complete(client)).isTrue();

        // Assert
        assertThat(thrown).isInstanceOf(CompletionException.class).hasCauseInstanceOf(RunCancelledException.class);
        verify(client).close();
    }

    @Test
    void createAsync_shouldMapRefusedLoopbackPortToTransport() throws Exception {
        // Arrange
        int refusedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            refusedPort = socket.getLocalPort();
        }
        ValkeyClientOptions options = new ValkeyClientOptions(
                new ValkeyEndpoint("127.0.0.1", refusedPort),
                ValkeyAuthentication.none(),
                false,
                "valkey-refused-port-test",
                Duration.ofSeconds(5));

        // Act
        Throwable thrown = catchThrowable(() -> GlideValkeyClientFactory.createAsync(options)
                .toCompletableFuture()
                .join());

        // Assert
        assertThat(thrown).isInstanceOf(CompletionException.class);
        assertThat(thrown.getCause()).isInstanceOfSatisfying(ValkeyStorageException.class, failure -> {
            assertThat(failure.kind()).isEqualTo(ValkeyStorageException.Kind.TRANSPORT);
            assertThat(failure.getCause()).isNull();
        });
    }

    @Test
    void closeLateClient_shouldSwallowUncheckedGlideCloseFailureAfterAttemptingClose() throws Exception {
        // Arrange
        GlideClient client = mock(GlideClient.class);
        doThrow(new ClosingException("secret-client")).when(client).close();

        // Act and assert
        assertThatCode(() -> GlideValkeyClientFactory.closeLateClient(client)).doesNotThrowAnyException();
        verify(client).close();
    }

    @Test
    void closeLateClient_shouldSwallowOtherRuntimeCloseFailureAfterAttemptingClose() throws Exception {
        // Arrange
        GlideClient client = mock(GlideClient.class);
        doThrow(new IllegalStateException("secret-runtime")).when(client).close();

        // Act and assert
        assertThatCode(() -> GlideValkeyClientFactory.closeLateClient(client)).doesNotThrowAnyException();
        verify(client).close();
    }
}
