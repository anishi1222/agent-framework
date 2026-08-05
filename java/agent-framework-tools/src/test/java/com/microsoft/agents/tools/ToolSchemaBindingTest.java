// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.StateValue;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

public class ToolSchemaBindingTest {
    @Test
    void generate_shouldDescribeNestedSupportedTypesAndRequiredRecordComponents() {
        // Arrange
        StateValue.ObjectValue schema = ToolSchemaGenerator.generate(Input.class);

        // Act
        StateValue.ObjectValue properties = object(schema.values().get("properties"));
        List<String> required = array(schema.values().get("required")).values().stream()
                .map(ToolSchemaBindingTest::string)
                .toList();

        // Assert
        assertThat(string(schema.values().get("type"))).isEqualTo("object");
        assertThat(properties.values()).containsKeys("name", "mode", "limit", "tags", "weights", "nested");
        assertThat(required).containsExactly("name", "mode", "tags", "weights", "nested");
        assertThat(properties.values().get("limit")).isInstanceOf(StateValue.ObjectValue.class);
        assertThat(booleanValue(schema.values().get("additionalProperties"))).isFalse();
    }

    @Test
    void annotatedTool_shouldBindSupportedValuesInjectContextAndEncodeRecordOutput() {
        // Arrange
        Fixtures fixtures = new Fixtures();
        FunctionTool tool = FunctionTools.fromAnnotated(fixtures).getFirst();
        StateValue.ObjectValue arguments = StateValue.object(Map.of(
                "input",
                StateValue.object(Map.of(
                        "name",
                        StateValue.string("alpha"),
                        "mode",
                        StateValue.string("FAST"),
                        "limit",
                        StateValue.nullValue(),
                        "tags",
                        StateValue.array(List.of(StateValue.string("one"), StateValue.string("two"))),
                        "weights",
                        StateValue.object(Map.of("score", StateValue.number(new BigDecimal("1.25")))),
                        "nested",
                        StateValue.object(
                                Map.of("identifier", StateValue.integer(new BigInteger("9007199254740993")))))),
                "factor",
                StateValue.number(new BigDecimal("2.5"))));
        ToolInvocationContext context = context("run-schema", "call-schema");

        // Act
        StateValue result =
                tool.invokeAsync(context, arguments).toCompletableFuture().join();

        // Assert
        assertThat(tool.name()).isEqualTo("transform");
        assertThat(tool.metadata().approvalMode()).isEqualTo(ToolApprovalMode.NEVER_REQUIRE);
        assertThat(tool.metadata().inputSchema().values()).containsKeys("type", "properties", "required");
        assertThat(object(result).values())
                .containsEntry("summary", StateValue.string("run-schema:alpha:FAST:2:2.5"))
                .containsEntry("identifier", StateValue.integer(new BigInteger("9007199254740993")));
    }

    @Test
    void annotatedTool_shouldUseOptionalEmptyForOmittedAndExplicitNullArguments() {
        // Arrange
        OptionalFixtures fixtures = new OptionalFixtures();
        FunctionTool tool = FunctionTools.fromAnnotated(fixtures).getFirst();
        ToolInvocationContext context = context("run-optional", "call-optional");

        // Act
        StateValue omitted = tool.invokeAsync(context, StateValue.object(Map.of()))
                .toCompletableFuture()
                .join();
        StateValue explicitNull = tool.invokeAsync(context, StateValue.object(Map.of("value", StateValue.nullValue())))
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(string(omitted)).isEqualTo("empty");
        assertThat(string(explicitNull)).isEqualTo("empty");
    }

    @Test
    void annotatedTool_shouldBindEverySupportedPrimitiveAndWrapper() {
        // Arrange
        FunctionTool tool = FunctionTools.fromAnnotated(new PrimitiveFixtures()).getFirst();
        LinkedHashMap<String, StateValue> arguments = new LinkedHashMap<>();
        arguments.put("byteValue", StateValue.integer(1));
        arguments.put("boxedByte", StateValue.integer(2));
        arguments.put("shortValue", StateValue.integer(3));
        arguments.put("boxedShort", StateValue.integer(4));
        arguments.put("intValue", StateValue.integer(5));
        arguments.put("boxedInt", StateValue.integer(6));
        arguments.put("longValue", StateValue.integer(7));
        arguments.put("boxedLong", StateValue.integer(8));
        arguments.put("floatValue", StateValue.number(new BigDecimal("1.5")));
        arguments.put("boxedFloat", StateValue.number(new BigDecimal("2.5")));
        arguments.put("doubleValue", StateValue.number(new BigDecimal("3.5")));
        arguments.put("boxedDouble", StateValue.number(new BigDecimal("4.5")));
        arguments.put("booleanValue", StateValue.bool(true));
        arguments.put("boxedBoolean", StateValue.bool(false));
        arguments.put("charValue", StateValue.string("x"));
        arguments.put("boxedChar", StateValue.string("y"));

        // Act
        StateValue result = tool.invokeAsync(context("run-primitives", "call-primitives"), StateValue.object(arguments))
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(string(result)).isEqualTo("1:2:3:4:5:6:7:8:1.5:2.5:3.5:4.5:true:false:x:y");
    }

    @Test
    void annotatedTool_shouldRejectMissingExtraWrongEnumAndRequiredNullArguments() {
        // Arrange
        FunctionTool tool = FunctionTools.fromAnnotated(new RequiredFixtures()).getFirst();
        ToolInvocationContext context = context("run-invalid", "call-invalid");

        // Act / Assert
        assertBindingFailure(tool, context, StateValue.object(Map.of()), "Missing required argument");
        assertBindingFailure(
                tool,
                context,
                StateValue.object(Map.of("mode", StateValue.string("FAST"), "extra", StateValue.string("no"))),
                "Unexpected argument");
        assertBindingFailure(
                tool,
                context,
                StateValue.object(Map.of("mode", StateValue.string("UNKNOWN"))),
                "Unsupported enum value");
        assertBindingFailure(
                tool, context, StateValue.object(Map.of("mode", StateValue.nullValue())), "must not be null");
    }

    @Test
    void schemaGeneration_shouldRejectRawUnsupportedRecursiveAndNonStringMapTypes() throws Exception {
        // Arrange
        Method raw = InvalidFixtures.class.getMethod("raw", List.class);
        Method arbitrary = InvalidFixtures.class.getMethod("arbitrary", Object.class);
        Method map = InvalidFixtures.class.getMethod("map", Map.class);

        // Act / Assert
        assertThatThrownBy(() -> FunctionTools.fromMethod(new InvalidFixtures(), raw))
                .isInstanceOf(ToolBindingException.class)
                .hasMessageContaining("raw generic");
        assertThatThrownBy(() -> FunctionTools.fromMethod(new InvalidFixtures(), arbitrary))
                .isInstanceOf(ToolBindingException.class)
                .hasMessageContaining("only public records");
        assertThatThrownBy(() -> FunctionTools.fromMethod(new InvalidFixtures(), map))
                .isInstanceOf(ToolBindingException.class)
                .hasMessageContaining("map keys must be String");
        assertThatThrownBy(() -> ToolSchemaGenerator.generate(Recursive.class))
                .isInstanceOf(ToolBindingException.class)
                .hasMessageContaining("recursive");
    }

    @Test
    void explicitSchema_shouldRejectUnsupportedReferenceAndCompositionKeywords() {
        // Arrange / Act / Assert
        assertThatThrownBy(() -> new ToolMetadata(
                        "unsafe",
                        "unsafe",
                        java.util.Set.of(ToolCapability.FUNCTION),
                        ToolApprovalMode.NEVER_REQUIRE,
                        StateValue.object(Map.of("$ref", StateValue.string("com.example.Arbitrary"))),
                        StateValue.object(Map.of())))
                .isInstanceOf(ToolBindingException.class)
                .hasMessageContaining("Unsupported JSON Schema keyword");
        assertThatThrownBy(() -> new ToolMetadata(
                        "polymorphic",
                        "polymorphic",
                        java.util.Set.of(ToolCapability.FUNCTION),
                        ToolApprovalMode.NEVER_REQUIRE,
                        StateValue.object(Map.of("oneOf", StateValue.array(List.of()))),
                        StateValue.object(Map.of())))
                .isInstanceOf(ToolBindingException.class)
                .hasMessageContaining("Unsupported JSON Schema keyword");
    }

    @Test
    void discovery_shouldBeDeterministicAndRejectOverloadNameCollisions() {
        // Arrange / Act
        List<FunctionTool> tools = FunctionTools.fromAnnotated(new OrderedFixtures());

        // Assert
        assertThat(tools).extracting(Tool::name).containsExactly("alpha", "zeta");
        assertThatThrownBy(() -> FunctionTools.fromAnnotated(new OverloadedFixtures()))
                .isInstanceOf(ToolBindingException.class)
                .hasMessageContaining("Duplicate tool name 'lookup'");
    }

    @Test
    void methodAdapter_shouldRejectPrivateMembersInsteadOfChangingAccessibility() throws Exception {
        // Arrange
        Method privateMethod = PrivateFixtures.class.getDeclaredMethod("hidden", String.class);

        // Act / Assert
        assertThatThrownBy(() -> FunctionTools.fromMethod(new PrivateFixtures(), privateMethod))
                .isInstanceOf(ToolBindingException.class)
                .hasMessageContaining("must be public");
        assertThat(java.lang.reflect.Modifier.isPrivate(privateMethod.getModifiers()))
                .isTrue();
    }

    @Test
    void asyncAnnotatedMethod_shouldUseTheSameBindingAndEncodingPath() {
        // Arrange
        FunctionTool tool = FunctionTools.fromAnnotated(new AsyncFixtures()).getFirst();

        // Act
        StateValue result = tool.invokeAsync(
                        context("run-async", "call-async"), StateValue.object(Map.of("value", StateValue.integer(21))))
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(result).isEqualTo(StateValue.integer(42));
    }

    private static void assertBindingFailure(
            FunctionTool tool, ToolInvocationContext context, StateValue.ObjectValue arguments, String message) {
        assertThatThrownBy(() -> tool.invokeAsync(context, arguments)
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .rootCause()
                .isInstanceOf(ToolBindingException.class)
                .hasMessageContaining(message);
    }

    private static ToolInvocationContext context(String runId, String callId) {
        return new ToolInvocationContext(
                runId,
                callId,
                new InvocationId(runId + ":" + callId),
                new DefaultRunCancellation(),
                Runnable::run,
                Map.of());
    }

    private static StateValue.ObjectValue object(StateValue value) {
        return (StateValue.ObjectValue) value;
    }

    private static StateValue.ArrayValue array(StateValue value) {
        return (StateValue.ArrayValue) value;
    }

    private static String string(StateValue value) {
        return ((StateValue.StringValue) value).value();
    }

    private static boolean booleanValue(StateValue value) {
        return ((StateValue.BooleanValue) value).value();
    }

    public enum Mode {
        FAST,
        SAFE
    }

    public record Nested(BigInteger identifier) {}

    public record Input(
            String name,
            Mode mode,
            Optional<Integer> limit,
            List<String> tags,
            Map<String, BigDecimal> weights,
            Nested nested) {}

    public record Output(String summary, BigInteger identifier) {}

    public record Recursive(Optional<Recursive> next) {}

    public static final class Fixtures {
        @ToolMethod(name = "transform", description = "Transforms a structured value.")
        public Output transform(
                @ToolParam("input") Input input,
                @ToolParam("factor") BigDecimal factor,
                ToolInvocationContext context) {
            return new Output(
                    context.logicalRunId()
                            + ":"
                            + input.name()
                            + ":"
                            + input.mode()
                            + ":"
                            + input.tags().size()
                            + ":"
                            + factor.toPlainString(),
                    input.nested().identifier());
        }
    }

    public static final class OptionalFixtures {
        @ToolMethod
        public String optional(@ToolParam("value") Optional<String> value) {
            return value.orElse("empty");
        }
    }

    public static final class PrimitiveFixtures {
        @ToolMethod
        public String primitives(
                @ToolParam("byteValue") byte byteValue,
                @ToolParam("boxedByte") Byte boxedByte,
                @ToolParam("shortValue") short shortValue,
                @ToolParam("boxedShort") Short boxedShort,
                @ToolParam("intValue") int intValue,
                @ToolParam("boxedInt") Integer boxedInt,
                @ToolParam("longValue") long longValue,
                @ToolParam("boxedLong") Long boxedLong,
                @ToolParam("floatValue") float floatValue,
                @ToolParam("boxedFloat") Float boxedFloat,
                @ToolParam("doubleValue") double doubleValue,
                @ToolParam("boxedDouble") Double boxedDouble,
                @ToolParam("booleanValue") boolean booleanValue,
                @ToolParam("boxedBoolean") Boolean boxedBoolean,
                @ToolParam("charValue") char charValue,
                @ToolParam("boxedChar") Character boxedChar) {
            return byteValue
                    + ":"
                    + boxedByte
                    + ":"
                    + shortValue
                    + ":"
                    + boxedShort
                    + ":"
                    + intValue
                    + ":"
                    + boxedInt
                    + ":"
                    + longValue
                    + ":"
                    + boxedLong
                    + ":"
                    + floatValue
                    + ":"
                    + boxedFloat
                    + ":"
                    + doubleValue
                    + ":"
                    + boxedDouble
                    + ":"
                    + booleanValue
                    + ":"
                    + boxedBoolean
                    + ":"
                    + charValue
                    + ":"
                    + boxedChar;
        }
    }

    public static final class RequiredFixtures {
        @ToolMethod
        public String required(@ToolParam("mode") Mode mode) {
            return mode.name();
        }
    }

    public static final class InvalidFixtures {
        @ToolMethod
        public String raw(@SuppressWarnings("rawtypes") @ToolParam("values") List values) {
            return values.toString();
        }

        @ToolMethod
        public String arbitrary(@ToolParam("value") Object value) {
            return value.toString();
        }

        @ToolMethod
        public String map(@ToolParam("value") Map<Integer, String> value) {
            return value.toString();
        }
    }

    public static final class OrderedFixtures {
        @ToolMethod(name = "zeta")
        public String first(@ToolParam("value") String value) {
            return value;
        }

        @ToolMethod(name = "alpha")
        public String second(@ToolParam("value") String value) {
            return value;
        }
    }

    public static final class OverloadedFixtures {
        @ToolMethod
        public String lookup(@ToolParam("value") String value) {
            return value;
        }

        @ToolMethod
        public String lookup(@ToolParam("value") int value) {
            return Integer.toString(value);
        }
    }

    public static final class PrivateFixtures {
        @ToolMethod
        private String hidden(@ToolParam("value") String value) {
            return value;
        }
    }

    public static final class AsyncFixtures {
        @ToolMethod
        public CompletableFuture<Integer> doubleValue(@ToolParam("value") int value) {
            return CompletableFuture.completedFuture(value * 2);
        }
    }
}
