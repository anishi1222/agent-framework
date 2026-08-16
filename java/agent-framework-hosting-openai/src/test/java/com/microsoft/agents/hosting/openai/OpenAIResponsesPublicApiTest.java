// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenAIResponsesPublicApiTest {
    private static final List<String> FORBIDDEN_TYPE_PREFIXES =
            List.of("com.fasterxml.", "com.openai.", "com.azure.ai.openai.", "org.reactivestreams.", "reactor.");

    @Test
    void publicApi_shouldNotExposeProviderJsonOrReactiveLibraryTypes() {
        // Arrange
        List<Class<?>> publicTypes = List.of(
                OpenAIResponsesRunOptionsMapper.class,
                OpenAIResponsesRequestInfo.class,
                OpenAIResponsesRunRequest.class,
                OpenAIResponsesHostingOptions.class,
                OpenAIResponsesHostingOptions.Builder.class,
                OpenAIResponsesHostingRoute.class,
                OpenAIResponsesHostingRegistry.class,
                OpenAIResponsesReferenceType.class,
                OpenAIResponsesConversationKey.class,
                OpenAIResponsesConversationState.class,
                OpenAIResponsesConversationStore.class,
                InMemoryOpenAIResponsesConversationStore.class,
                OpenAIResponsesJsonCodec.class,
                OpenAIResponsesHttpResponse.class,
                OpenAIResponsesHostedRun.class,
                OpenAIResponsesPrincipalResolver.class,
                OpenAIResponsesHttpServer.class,
                OpenAIResponsesHttpHandler.class);

        // Act
        List<String> exposed = new ArrayList<>();
        for (Class<?> type : publicTypes) {
            collect(type.getGenericSuperclass(), type.getName(), exposed);
            for (Type contract : type.getGenericInterfaces()) {
                collect(contract, type.getName(), exposed);
            }
            for (Constructor<?> constructor : type.getConstructors()) {
                for (Type parameter : constructor.getGenericParameterTypes()) {
                    collect(parameter, constructor.toGenericString(), exposed);
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                collect(method.getGenericReturnType(), method.toGenericString(), exposed);
                for (Type parameter : method.getGenericParameterTypes()) {
                    collect(parameter, method.toGenericString(), exposed);
                }
            }
            for (Field field : type.getFields()) {
                collect(field.getGenericType(), field.toGenericString(), exposed);
            }
            if (type.isRecord()) {
                for (RecordComponent component : type.getRecordComponents()) {
                    collect(component.getGenericType(), component.toString(), exposed);
                }
            }
        }

        // Assert
        assertThat(exposed).isEmpty();
    }

    private static void collect(Type candidate, String source, List<String> exposed) {
        if (candidate == null) {
            return;
        }
        String name = candidate.getTypeName();
        if (FORBIDDEN_TYPE_PREFIXES.stream().anyMatch(name::contains)) {
            exposed.add(source + " -> " + name);
        }
    }
}
