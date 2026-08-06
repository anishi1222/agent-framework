// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import com.microsoft.agents.core.RunCancellations;
import com.microsoft.agents.core.RunCancelledException;
import com.microsoft.agents.core.StateValue;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * Creates, discovers, and validates function tools.
 */
public final class FunctionTools {
    private FunctionTools() {}

    /**
     * Creates a function tool from explicit metadata and a JSON-shaped handler.
     *
     * @param metadata immutable metadata
     * @param handler asynchronous handler
     * @return function tool
     */
    public static FunctionTool create(ToolMetadata metadata, FunctionToolHandler handler) {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(handler, "handler");
        if (!metadata.capabilities().contains(ToolCapability.FUNCTION)) {
            throw new IllegalArgumentException("FunctionTool metadata must contain the FUNCTION capability.");
        }
        return new ExplicitFunctionTool(metadata, handler);
    }

    /**
     * Discovers annotated public methods deterministically.
     *
     * <p>When {@code target} is a {@link Class}, only annotated static methods are accepted. Otherwise
     * annotated instance and static public methods are accepted. Tool names must be unique.
     *
     * @param target public instance or public class
     * @return immutable tools sorted by method signature
     */
    public static List<FunctionTool> fromAnnotated(Object target) {
        Objects.requireNonNull(target, "target");
        Class<?> targetType = target instanceof Class<?> clazz ? clazz : target.getClass();
        requirePublicClass(targetType);
        List<Method> methods = java.util.Arrays.stream(targetType.getMethods())
                .filter(method -> method.isAnnotationPresent(ToolMethod.class))
                .sorted(Comparator.comparing(FunctionTools::declaredName).thenComparing(Method::toGenericString))
                .toList();
        List<FunctionTool> tools = new ArrayList<>(methods.size());
        for (Method method : methods) {
            tools.add(fromMethod(target, method));
        }
        return normalize(tools).stream().map(FunctionTool.class::cast).toList();
    }

    /**
     * Creates a function tool from one annotated public method.
     *
     * @param target declaring instance, or declaring {@link Class} for a static method
     * @param method annotated public method
     * @return function tool
     */
    public static FunctionTool fromMethod(Object target, Method method) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(method, "method");
        ToolMethod declaration = method.getAnnotation(ToolMethod.class);
        if (declaration == null) {
            throw new ToolBindingException(
                    "Method " + method.toGenericString() + " is not annotated with @ToolMethod.");
        }
        validateMethod(target, method);

        MethodHandle handle;
        try {
            handle = MethodHandles.publicLookup().unreflect(method);
            if (!Modifier.isStatic(method.getModifiers())) {
                handle = handle.bindTo(target);
            }
        } catch (IllegalAccessException exception) {
            throw new ToolBindingException(
                    "Method is not accessible through MethodHandles.publicLookup(): " + method.toGenericString() + ".",
                    exception);
        }

        Parameter[] parameters = method.getParameters();
        List<ParameterBinding> bindings = new ArrayList<>(parameters.length);
        LinkedHashMap<String, StateValue> properties = new LinkedHashMap<>();
        List<StateValue> required = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        int contextCount = 0;
        for (int index = 0; index < parameters.length; index++) {
            Parameter parameter = parameters[index];
            if (parameter.getType() == ToolInvocationContext.class) {
                contextCount++;
                bindings.add(ParameterBinding.context(index));
                continue;
            }
            ToolParam annotation = parameter.getAnnotation(ToolParam.class);
            String parameterName =
                    annotation != null ? annotation.value() : parameter.isNamePresent() ? parameter.getName() : null;
            if (parameterName == null) {
                throw new ToolBindingException("Parameter "
                        + index
                        + " of "
                        + method.toGenericString()
                        + " requires @ToolParam because Java parameter names are unavailable.");
            }
            parameterName = ToolValidation.requireNonBlank(parameterName, "tool parameter name");
            if (!names.add(parameterName)) {
                throw new ToolBindingException("Method " + method.toGenericString()
                        + " declares duplicate tool parameter '" + parameterName + "'.");
            }
            StateValue.ObjectValue propertySchema = ToolSchemaGenerator.generate(parameter.getParameterizedType());
            if (annotation != null && !annotation.description().isEmpty()) {
                LinkedHashMap<String, StateValue> described = new LinkedHashMap<>(propertySchema.values());
                described.put("description", StateValue.string(annotation.description()));
                propertySchema = StateValue.object(described);
            }
            properties.put(parameterName, propertySchema);
            if (!ToolSchemaGenerator.isOptional(parameter.getParameterizedType())) {
                required.add(StateValue.string(parameterName));
            }
            bindings.add(ParameterBinding.argument(
                    index,
                    parameterName,
                    parameter.getParameterizedType(),
                    ToolSchemaGenerator.isOptional(parameter.getParameterizedType())));
        }
        if (contextCount > 1) {
            throw new ToolBindingException(
                    "Method " + method.toGenericString() + " declares multiple ToolInvocationContext parameters.");
        }

        LinkedHashMap<String, StateValue> inputFields = new LinkedHashMap<>();
        inputFields.put("type", StateValue.string("object"));
        inputFields.put("properties", StateValue.object(properties));
        if (!required.isEmpty()) {
            inputFields.put("required", StateValue.array(required));
        }
        inputFields.put("additionalProperties", StateValue.bool(false));

        ReturnBinding returnBinding = ReturnBinding.from(method.getGenericReturnType());
        String name = declaration.name().isEmpty() ? method.getName() : declaration.name();
        ToolMetadata metadata = new ToolMetadata(
                name,
                declaration.description(),
                Set.of(ToolCapability.FUNCTION),
                declaration.approvalMode(),
                StateValue.object(inputFields),
                ToolSchemaGenerator.generate(returnBinding.valueType()));
        return new ReflectiveFunctionTool(metadata, handle, bindings, returnBinding);
    }

    /**
     * Returns an immutable deterministic tool list and rejects duplicate names.
     *
     * @param tools tools in requested order
     * @return immutable normalized tools
     */
    public static List<Tool> normalize(Iterable<? extends Tool> tools) {
        Objects.requireNonNull(tools, "tools");
        LinkedHashMap<String, Tool> byName = new LinkedHashMap<>();
        for (Tool tool : tools) {
            Objects.requireNonNull(tool, "tool");
            Tool previous = byName.putIfAbsent(tool.name(), tool);
            if (previous != null && previous != tool) {
                throw new ToolBindingException("Duplicate tool name '" + tool.name() + "'. Tool names must be unique.");
            }
        }
        return List.copyOf(byName.values());
    }

    /**
     * Returns an immutable name-to-tool lookup.
     *
     * @param tools tools
     * @return insertion-ordered immutable lookup
     */
    public static Map<String, Tool> byName(Iterable<? extends Tool> tools) {
        LinkedHashMap<String, Tool> result = new LinkedHashMap<>();
        normalize(tools).forEach(tool -> result.put(tool.name(), tool));
        return java.util.Collections.unmodifiableMap(result);
    }

    private static void validateMethod(Object target, Method method) {
        requirePublicClass(method.getDeclaringClass());
        if (!Modifier.isPublic(method.getModifiers())) {
            throw new ToolBindingException("Tool method must be public: " + method.toGenericString() + ".");
        }
        if (method.isBridge() || method.isSynthetic()) {
            throw new ToolBindingException(
                    "Bridge and synthetic methods cannot be tools: " + method.toGenericString() + ".");
        }
        if (method.isVarArgs()) {
            throw new ToolBindingException("Varargs methods cannot be tools: " + method.toGenericString() + ".");
        }
        if (method.getTypeParameters().length != 0) {
            throw new ToolBindingException("Generic methods cannot be tools: " + method.toGenericString() + ".");
        }
        if (Modifier.isStatic(method.getModifiers())) {
            boolean compatible = target instanceof Class<?> targetClass
                    ? method.getDeclaringClass().isAssignableFrom(targetClass)
                    : method.getDeclaringClass().isInstance(target);
            if (!compatible) {
                throw new ToolBindingException("Tool target is incompatible with " + method.toGenericString() + ".");
            }
        } else {
            if (target instanceof Class<?> || !method.getDeclaringClass().isInstance(target)) {
                throw new ToolBindingException(
                        "Instance tool method requires a compatible public target: " + method.toGenericString() + ".");
            }
        }
    }

    private static void requirePublicClass(Class<?> type) {
        if (!Modifier.isPublic(type.getModifiers())) {
            throw new ToolBindingException("Tool declaring class must be public: " + type.getName() + ".");
        }
    }

    private static String declaredName(Method method) {
        ToolMethod declaration = method.getAnnotation(ToolMethod.class);
        return declaration.name().isEmpty() ? method.getName() : declaration.name();
    }

    private record ParameterBinding(int index, String name, Type type, boolean optional, boolean context) {
        static ParameterBinding context(int index) {
            return new ParameterBinding(index, null, ToolInvocationContext.class, false, true);
        }

        static ParameterBinding argument(int index, String name, Type type, boolean optional) {
            return new ParameterBinding(index, name, type, optional, false);
        }
    }

    private record ReturnBinding(Type valueType, boolean asynchronous) {
        static ReturnBinding from(Type returnType) {
            if (returnType instanceof Class<?> clazz && CompletionStage.class.isAssignableFrom(clazz)) {
                throw new ToolBindingException("Raw CompletionStage return types are not supported.");
            }
            if (returnType instanceof ParameterizedType parameterized
                    && parameterized.getRawType() instanceof Class<?> raw
                    && CompletionStage.class.isAssignableFrom(raw)) {
                Type[] arguments = parameterized.getActualTypeArguments();
                if (arguments.length != 1) {
                    throw new ToolBindingException("CompletionStage return type must have exactly one value type.");
                }
                ToolSchemaGenerator.generate(arguments[0]);
                return new ReturnBinding(arguments[0], true);
            }
            ToolSchemaGenerator.generate(returnType);
            return new ReturnBinding(returnType, false);
        }
    }

    private record ExplicitFunctionTool(ToolMetadata metadata, FunctionToolHandler handler) implements FunctionTool {
        private ExplicitFunctionTool {
            Objects.requireNonNull(metadata, "metadata");
            Objects.requireNonNull(handler, "handler");
        }

        @Override
        public CompletionStage<StateValue> invokeAsync(
                ToolInvocationContext context, StateValue.ObjectValue arguments) {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(arguments, "arguments");
            if (context.cancellation().isCancellationRequested()) {
                return CompletableFuture.failedFuture(new RunCancelledException());
            }
            try {
                ToolSchemaValidator.validate(arguments, metadata.inputSchema(), "$arguments");
            } catch (ToolBindingException failure) {
                return CompletableFuture.failedFuture(failure);
            }
            CompletionStage<StateValue> stage;
            try {
                stage = handler.invokeAsync(context, arguments);
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
            if (stage == null) {
                return CompletableFuture.failedFuture(
                        new ToolInvocationException("Function tool handler returned a null CompletionStage."));
            }
            return stage.thenApply(result -> {
                StateValue nonNull = Objects.requireNonNull(result, "function tool result");
                try {
                    ToolSchemaValidator.validate(nonNull, metadata.outputSchema(), "$result");
                } catch (ToolBindingException failure) {
                    throw new ToolOutputValidationException(
                            "Function tool result does not satisfy the declared output schema.", failure);
                }
                return nonNull;
            });
        }
    }

    private static final class ReflectiveFunctionTool implements FunctionTool {
        private final ToolMetadata metadata;

        private final MethodHandle handle;

        private final List<ParameterBinding> bindings;

        private final ReturnBinding returnBinding;

        private ReflectiveFunctionTool(
                ToolMetadata metadata,
                MethodHandle handle,
                List<ParameterBinding> bindings,
                ReturnBinding returnBinding) {
            this.metadata = metadata;
            this.handle = handle;
            this.bindings = List.copyOf(bindings);
            this.returnBinding = returnBinding;
        }

        @Override
        public ToolMetadata metadata() {
            return metadata;
        }

        @Override
        public CompletionStage<StateValue> invokeAsync(
                ToolInvocationContext context, StateValue.ObjectValue arguments) {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(arguments, "arguments");
            if (context.cancellation().isCancellationRequested()) {
                return CompletableFuture.failedFuture(new RunCancelledException());
            }
            Object[] values;
            try {
                values = bindArguments(context, arguments);
            } catch (RuntimeException exception) {
                return CompletableFuture.failedFuture(exception);
            }

            CompletableFuture<Object> invoked = CompletableFuture.supplyAsync(
                    () -> {
                        if (context.cancellation().isCancellationRequested()) {
                            throw new CompletionException(new RunCancelledException());
                        }
                        try {
                            return handle.invokeWithArguments(values);
                        } catch (Throwable failure) {
                            throw new CompletionException(failure);
                        }
                    },
                    context.executor());
            CompletableFuture<StateValue> result = new CompletableFuture<>();
            var cancellationRegistration = RunCancellations.register(context.cancellation(), () -> {
                invoked.cancel(true);
                result.completeExceptionally(new RunCancelledException());
            });
            result.whenComplete((ignored, failure) -> cancellationRegistration.close());
            invoked.whenComplete((rawResult, invocationFailure) -> {
                if (invocationFailure != null) {
                    result.completeExceptionally(unwrap(invocationFailure));
                    return;
                }
                if (returnBinding.asynchronous()) {
                    if (!(rawResult instanceof CompletionStage<?> stage)) {
                        result.completeExceptionally(
                                new ToolInvocationException("Annotated method declared CompletionStage but returned "
                                        + (rawResult == null
                                                ? "null"
                                                : rawResult.getClass().getName())
                                        + "."));
                        return;
                    }
                    stage.whenComplete(
                            (asyncResult, asyncFailure) -> completeEncoded(result, asyncResult, asyncFailure));
                } else {
                    completeEncoded(result, rawResult, null);
                }
            });
            return result.minimalCompletionStage();
        }

        private Object[] bindArguments(ToolInvocationContext context, StateValue.ObjectValue arguments) {
            Set<String> accepted = bindings.stream()
                    .filter(binding -> !binding.context())
                    .map(ParameterBinding::name)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            List<String> extras = arguments.values().keySet().stream()
                    .filter(key -> !accepted.contains(key))
                    .sorted()
                    .toList();
            if (!extras.isEmpty()) {
                throw new ToolBindingException("Unexpected argument(s) for '" + name() + "': " + extras + ".");
            }
            Object[] values = new Object[bindings.size()];
            for (ParameterBinding binding : bindings) {
                if (binding.context()) {
                    values[binding.index()] = context;
                    continue;
                }
                StateValue value = arguments.values().get(binding.name());
                if (value == null) {
                    if (binding.optional()) {
                        values[binding.index()] = Optional.empty();
                        continue;
                    }
                    throw new ToolBindingException(
                            "Missing required argument '" + binding.name() + "' for '" + name() + "'.");
                }
                values[binding.index()] = ToolValueCodec.bind(binding.type(), value, "$." + binding.name());
            }
            return values;
        }

        private void completeEncoded(CompletableFuture<StateValue> target, Object rawResult, Throwable failure) {
            if (failure != null) {
                target.completeExceptionally(unwrap(failure));
                return;
            }
            try {
                target.complete(ToolValueCodec.encode(returnBinding.valueType(), rawResult, "$result"));
            } catch (ToolBindingException exception) {
                target.completeExceptionally(new ToolOutputValidationException(
                        "Function tool result cannot be encoded as the declared return type.", exception));
            } catch (RuntimeException exception) {
                target.completeExceptionally(exception);
            }
        }

        private static Throwable unwrap(Throwable failure) {
            Throwable current = failure;
            while ((current instanceof CompletionException
                            || current instanceof java.util.concurrent.ExecutionException)
                    && current.getCause() != null) {
                current = current.getCause();
            }
            return current;
        }
    }
}
