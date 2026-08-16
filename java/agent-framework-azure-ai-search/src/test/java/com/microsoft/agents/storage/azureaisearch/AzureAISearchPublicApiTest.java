// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.azureaisearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.agents.ContextProvider;
import com.microsoft.agents.agents.memory.MemoryStore;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.ValidationException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AzureAISearchPublicApiTest {
    private static final List<Class<?>> PUBLIC_TYPES = List.of(
            AzureAISearchEndpoint.class,
            AzureAISearchApiKey.class,
            AzureAISearchAuthentication.class,
            AzureAISearchAudience.class,
            AzureAISearchQueryMode.class,
            AzureAISearchFailurePolicy.class,
            AzureAISearchScopeResolver.class,
            AzureAISearchFieldMapping.class,
            AzureAISearchOptions.class,
            AzureAISearchResult.class,
            AzureAISearchException.class,
            AzureAISearchValidationException.class,
            AzureAISearchContextProvider.class);

    @Test
    void publicApi_shouldExposeOnlyFrameworkAndJdkTypes() {
        ArrayList<String> signatures = new ArrayList<>();

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

        assertThat(signatures).isNotEmpty();
    }

    @Test
    void contextProvider_shouldBeReadOnlyAndNotClaimMemoryStoreSemantics() {
        assertThat(ContextProvider.class.isAssignableFrom(AzureAISearchContextProvider.class))
                .isTrue();
        assertThat(MemoryStore.class.isAssignableFrom(AzureAISearchContextProvider.class))
                .isFalse();
        assertThat(AzureAISearchContextProvider.class.getDeclaredMethods())
                .extracting(Method::getName)
                .doesNotContain("upsertAsync", "deleteAsync", "clearAsync", "createIndexAsync");
    }

    @Test
    void endpoint_shouldRequireHttpsExceptForExactLoopbackHttp() {
        assertThat(AzureAISearchEndpoint.of("https://search.example.com").uri())
                .isEqualTo(URI.create("https://search.example.com/"));
        assertThat(AzureAISearchEndpoint.of("http://127.255.0.1:8080").uri())
                .isEqualTo(URI.create("http://127.255.0.1:8080/"));
        assertThat(AzureAISearchEndpoint.of("http://[::1]:8080").uri()).isEqualTo(URI.create("http://[::1]:8080/"));
        assertThatThrownBy(() -> AzureAISearchEndpoint.of("http://example.com"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> AzureAISearchEndpoint.of("http://127.0.0.1.attacker.example"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> AzureAISearchEndpoint.of("https://user@search.example.com"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> AzureAISearchEndpoint.of("https://search.example.com/indexes"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void fieldMapping_shouldRequireDistinctAsciiIdentifiers() {
        assertThatThrownBy(() -> AzureAISearchFieldMapping.builder()
                        .contentField("tenantId")
                        .build())
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> AzureAISearchFieldMapping.builder()
                        .contentField("content/name")
                        .build())
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() ->
                        AzureAISearchFieldMapping.builder().contentField("内容").build())
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void publicValues_shouldRedactCredentialsResourcesAndRetrievedContent() {
        AzureAISearchAuthentication authentication =
                AzureAISearchAuthentication.apiKey(AzureAISearchApiKey.of("secret-key"));
        AzureAISearchOptions options = AzureAISearchOptions.forIndex(
                        AzureAISearchEndpoint.of("https://search.example.com"), "secret-index", authentication)
                .staticFilter("secretField eq 'secret-value'")
                .build();
        AzureAISearchResult result = new AzureAISearchResult(
                "secret-id",
                "secret-content",
                "https://secret.example.com/document",
                0.9,
                1,
                Map.of("private", StateValue.string("secret-metadata")));

        assertThat(authentication.toString()).doesNotContain("secret-key");
        assertThat(options.toString()).doesNotContain("secret-index").doesNotContain("secret-value");
        assertThat(result.toString())
                .doesNotContain("secret-id")
                .doesNotContain("secret-content")
                .doesNotContain("secret.example.com")
                .doesNotContain("secret-metadata");
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
            assertThat(type.getTypeName())
                    .doesNotContain("com.azure.")
                    .doesNotContain("reactor.core.")
                    .doesNotContain("org.springframework.")
                    .doesNotContain("com.fasterxml.jackson.");
        }
    }
}
