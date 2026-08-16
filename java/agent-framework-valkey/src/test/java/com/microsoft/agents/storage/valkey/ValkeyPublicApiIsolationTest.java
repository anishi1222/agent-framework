// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.valkey;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValkeyPublicApiIsolationTest {
    @Test
    void publicAndProtectedSignatures_shouldNotExposeGlideTypes() {
        // Arrange
        List<Class<?>> publicTypes = List.of(
                ValkeyAuthentication.class,
                ValkeyClientOptions.class,
                ValkeyEndpoint.class,
                ValkeyHistoryOptions.class,
                ValkeyHistoryProvider.class,
                ValkeyPartitionContext.class,
                ValkeyPassword.class,
                ValkeyStorageException.class,
                ValkeyStorageException.Kind.class);

        // Act
        List<String> signatureTypes = new ArrayList<>();
        publicTypes.forEach(type -> collectSignatures(type, signatureTypes));

        // Assert
        assertThat(signatureTypes)
                .noneMatch(name -> name.startsWith("glide."))
                .noneMatch(name -> name.startsWith("io.valkey."));
        assertThat(Modifier.isPublic(GlideValkeyCommandAdapter.class.getModifiers()))
                .isFalse();
        assertThat(Modifier.isPublic(ValkeyCommandAdapter.class.getModifiers())).isFalse();
    }

    private static void collectSignatures(Class<?> type, List<String> target) {
        add(type.getGenericSuperclass(), target);
        for (Type value : type.getGenericInterfaces()) {
            add(value, target);
        }
        for (var constructor : type.getDeclaredConstructors()) {
            if (isPublicOrProtected(constructor.getModifiers())) {
                for (Type value : constructor.getGenericParameterTypes()) {
                    add(value, target);
                }
                for (Type value : constructor.getGenericExceptionTypes()) {
                    add(value, target);
                }
            }
        }
        for (var method : type.getDeclaredMethods()) {
            if (isPublicOrProtected(method.getModifiers())) {
                add(method.getGenericReturnType(), target);
                for (Type value : method.getGenericParameterTypes()) {
                    add(value, target);
                }
                for (Type value : method.getGenericExceptionTypes()) {
                    add(value, target);
                }
            }
        }
        for (var field : type.getDeclaredFields()) {
            if (isPublicOrProtected(field.getModifiers())) {
                add(field.getGenericType(), target);
            }
        }
        if (type.isRecord()) {
            for (var component : type.getRecordComponents()) {
                add(component.getGenericType(), target);
            }
        }
    }

    private static boolean isPublicOrProtected(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static void add(Type type, List<String> target) {
        if (type == null) {
            return;
        }
        switch (type) {
            case Class<?> clazz -> {
                target.add(clazz.getName());
                if (clazz.isArray()) {
                    add(clazz.getComponentType(), target);
                }
            }
            case ParameterizedType parameterized -> {
                add(parameterized.getRawType(), target);
                for (Type argument : parameterized.getActualTypeArguments()) {
                    add(argument, target);
                }
            }
            case GenericArrayType array -> add(array.getGenericComponentType(), target);
            case WildcardType wildcard -> {
                for (Type bound : wildcard.getUpperBounds()) {
                    add(bound, target);
                }
                for (Type bound : wildcard.getLowerBounds()) {
                    add(bound, target);
                }
            }
            default -> target.add(type.getTypeName());
        }
    }
}
