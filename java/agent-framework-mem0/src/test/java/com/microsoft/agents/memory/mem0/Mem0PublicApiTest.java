// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.memory.mem0;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.agents.ContextProvider;
import com.microsoft.agents.agents.memory.MemoryStore;
import com.microsoft.agents.core.ValidationException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Mem0PublicApiTest {
    private static final List<Class<?>> PUBLIC_TYPES = List.of(
            Mem0Endpoint.class,
            Mem0ApiKey.class,
            Mem0ClientOptions.class,
            Mem0RetryOptions.class,
            Mem0LimitOptions.class,
            Mem0Scope.class,
            Mem0ProviderState.class,
            Mem0ScopeResolver.class,
            Mem0FailurePolicy.class,
            Mem0StorageException.class,
            Mem0Memory.class,
            Mem0ContextProvider.class);

    @Test
    void publicApi_shouldExposeOnlyFrameworkAndJdkTypes() {
        // Arrange
        ArrayList<String> signatures = new ArrayList<>();

        // Act
        for (Class<?> type : expandedPublicTypes()) {
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (Modifier.isPublic(constructor.getModifiers()) || Modifier.isProtected(constructor.getModifiers())) {
                    signatures.add(constructor.toGenericString());
                    inspect(constructor.getGenericParameterTypes());
                    inspect(constructor.getGenericExceptionTypes());
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers()) || Modifier.isProtected(method.getModifiers())) {
                    signatures.add(method.toGenericString());
                    inspect(method.getGenericReturnType());
                    inspect(method.getGenericParameterTypes());
                    inspect(method.getGenericExceptionTypes());
                }
            }
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isPublic(field.getModifiers()) || Modifier.isProtected(field.getModifiers())) {
                    signatures.add(field.toGenericString());
                    inspect(field.getGenericType());
                }
            }
        }

        // Assert
        assertThat(signatures).isNotEmpty();
    }

    @Test
    void contextProvider_shouldNotClaimMemoryStoreCasSemantics() {
        assertThat(ContextProvider.class.isAssignableFrom(Mem0ContextProvider.class))
                .isTrue();
        assertThat(MemoryStore.class.isAssignableFrom(Mem0ContextProvider.class))
                .isFalse();
    }

    @Test
    void endpoint_shouldAllowHttpsOrLoopbackHttpOnly() {
        assertThat(Mem0Endpoint.platform().uri()).isEqualTo(URI.create("https://api.mem0.ai/"));
        assertThat(Mem0Endpoint.of("http://127.0.0.1:8080").uri().toString()).isEqualTo("http://127.0.0.1:8080/");
        assertThat(Mem0Endpoint.of("http://127.255.0.1:8080").uri().toString()).isEqualTo("http://127.255.0.1:8080/");
        assertThatThrownBy(() -> Mem0Endpoint.of("http://example.com")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> Mem0Endpoint.of("http://127.attacker.example"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> Mem0Endpoint.of("http://127.0.0.1.attacker.example"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> Mem0Endpoint.of("https://user@example.com")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> Mem0Endpoint.of("https://example.com/?token=secret"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> Mem0Endpoint.platform().resolve("v1/../memories/"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void contextProvider_shouldNotExposeUnscopedItemOperations() {
        assertThat(Mem0ContextProvider.class.getDeclaredMethods())
                .extracting(Method::getName)
                .doesNotContain("getAsync", "updateAsync", "deleteAsync");
    }

    @Test
    void scope_shouldRequireIdentityAndRejectDeleteAllWildcard() {
        assertThatThrownBy(() -> Mem0Scope.builder().build()).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> Mem0Scope.builder().userId("*").build()).isInstanceOf(ValidationException.class);
        assertThat(Mem0Scope.forUser("user").toString()).contains("[REDACTED]").doesNotContain("user}");
    }

    @Test
    void publicValues_shouldRedactSecretsAndMemoryContentFromStringConversion() {
        Mem0Memory memory = new Mem0Memory(
                "secret-id",
                "secret-memory",
                0.9,
                1,
                "secret-app",
                "secret-user",
                null,
                null,
                Map.of("secret", com.microsoft.agents.core.StateValue.string("value")),
                List.of("private"),
                Instant.EPOCH,
                null);

        assertThat(memory.toString())
                .doesNotContain("secret-id")
                .doesNotContain("secret-memory")
                .doesNotContain("secret-user")
                .doesNotContain("value");
        assertThat(Mem0ApiKey.of("secret-key").toString()).doesNotContain("secret-key");
    }

    private static List<Class<?>> expandedPublicTypes() {
        ArrayList<Class<?>> types = new ArrayList<>(PUBLIC_TYPES);
        for (int index = 0; index < types.size(); index++) {
            for (Class<?> nested : types.get(index).getDeclaredClasses()) {
                if (Modifier.isPublic(nested.getModifiers()) || Modifier.isProtected(nested.getModifiers())) {
                    types.add(nested);
                }
            }
        }
        return List.copyOf(types);
    }

    private static void inspect(Type... types) {
        for (Type type : types) {
            String signature = type.getTypeName();
            assertThat(signature)
                    .doesNotContain("java.net.http.HttpClient")
                    .doesNotContain("com.fasterxml.jackson.")
                    .doesNotContain("io.mem0.")
                    .doesNotContain("ai.mem0.")
                    .doesNotContain("dev.langchain4j.")
                    .doesNotContain("org.springframework.")
                    .doesNotContain("reactor.core.");
        }
    }
}
