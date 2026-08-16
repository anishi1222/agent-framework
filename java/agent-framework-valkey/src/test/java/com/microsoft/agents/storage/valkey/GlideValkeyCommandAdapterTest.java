// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.valkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import glide.api.GlideClient;
import glide.api.models.exceptions.ClosingException;
import glide.api.models.exceptions.GlideException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class GlideValkeyCommandAdapterTest {
    @ParameterizedTest
    @MethodSource("runtimeCloseFailures")
    void close_shouldMapUncheckedClientFailuresWithoutLeakingSdkTypes(
            RuntimeException closeFailure, ValkeyStorageException.Kind expectedKind) throws Exception {
        // Arrange
        GlideClient client = mock(GlideClient.class);
        doThrow(closeFailure).when(client).close();
        GlideValkeyCommandAdapter adapter = new GlideValkeyCommandAdapter(client);

        // Act
        Throwable thrown = catchThrowable(adapter::close);
        adapter.close();

        // Assert
        assertThat(thrown).isInstanceOfSatisfying(ValkeyStorageException.class, failure -> {
            assertThat(failure.kind()).isEqualTo(expectedKind);
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getMessage()).doesNotContain("secret");
        });
        verify(client).close();
    }

    private static Stream<Arguments> runtimeCloseFailures() {
        return Stream.of(
                Arguments.of(new ClosingException("secret-closing"), ValkeyStorageException.Kind.CLOSED),
                Arguments.of(new GlideException("secret-glide"), ValkeyStorageException.Kind.SERVICE),
                Arguments.of(new IllegalStateException("secret-runtime"), ValkeyStorageException.Kind.SERVICE));
    }
}
