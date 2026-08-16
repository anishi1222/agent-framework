// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.valkey;

import static org.assertj.core.api.Assertions.assertThat;

import glide.api.models.exceptions.ClosingException;
import glide.api.models.exceptions.ConnectionException;
import glide.api.models.exceptions.RequestException;
import glide.api.models.exceptions.TimeoutException;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class GlideValkeyFailureMapperTest {
    @Test
    void mapper_shouldExposeOnlyStableFrameworkCategoriesAndSanitizedMessages() {
        // Arrange
        List<TestCase> cases = List.of(
                new TestCase(new TimeoutException("secret-timeout"), ValkeyStorageException.Kind.TIMEOUT),
                new TestCase(new ConnectionException("secret-host"), ValkeyStorageException.Kind.TRANSPORT),
                new TestCase(new ClosingException("secret-client"), ValkeyStorageException.Kind.CLOSED),
                new TestCase(
                        new RequestException("NOAUTH secret-password"), ValkeyStorageException.Kind.AUTHENTICATION),
                new TestCase(
                        new RequestException("ERR AF_VALKEY_DOCUMENT_BYTES secret-payload"),
                        ValkeyStorageException.Kind.INCOMPATIBLE_DATA),
                new TestCase(
                        new RequestException("WRONGTYPE AF_VALKEY_WRONG_TYPE_DEDUP secret-key"),
                        ValkeyStorageException.Kind.INCOMPATIBLE_DATA),
                new TestCase(new RequestException("ERR secret-payload"), ValkeyStorageException.Kind.SERVICE));

        // Act and assert
        assertThat(cases).allSatisfy(testCase -> {
            RuntimeException mapped = GlideValkeyFailureMapper.map(testCase.failure());
            assertThat(mapped).isInstanceOfSatisfying(ValkeyStorageException.class, failure -> {
                assertThat(failure.kind()).isEqualTo(testCase.kind());
                assertThat(failure.getCause()).isNull();
            });
            assertThat(mapped.getMessage())
                    .doesNotContain(
                            "secret-timeout", "secret-host", "secret-client", "secret-password", "secret-payload");
        });
    }

    @Test
    void creationMapper_shouldClassifyClosingExceptionAsTransport() {
        RuntimeException mapped = GlideValkeyFailureMapper.mapCreation(
                new CompletionException(new ClosingException("Failed to create client - Connection refused")));

        assertThat(mapped).isInstanceOfSatisfying(ValkeyStorageException.class, failure -> {
            assertThat(failure.kind()).isEqualTo(ValkeyStorageException.Kind.TRANSPORT);
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getMessage()).doesNotContain("Connection refused");
        });
    }

    private record TestCase(RuntimeException failure, ValkeyStorageException.Kind kind) {}
}
