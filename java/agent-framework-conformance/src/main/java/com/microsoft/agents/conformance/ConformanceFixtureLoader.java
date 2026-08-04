// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Loads and explicitly validates versioned conformance manifests and fixtures.
 *
 * <p>The loader uses Jackson tree parsing only. It does not enable default typing or polymorphic
 * binding, and every accepted discriminator is handled by an explicit switch.
 */
public final class ConformanceFixtureLoader {
    /** Default classpath location of the version 1 manifest. */
    public static final String DEFAULT_MANIFEST_RESOURCE = "conformance/manifest-v1.json";

    private static final Set<String> MANIFEST_FIELDS = Set.of("schemaVersion", "cases");

    private static final Set<String> MANIFEST_CASE_FIELDS =
            Set.of("caseId", "suiteId", "matrixStatus", "matrixAreas", "fixture", "kind", "sourceReferences");

    private static final Set<String> COMMON_FIXTURE_FIELDS =
            Set.of("schemaVersion", "caseId", "kind", "description", "expected");

    private static final ObjectMapper MAPPER = createMapper();

    /**
     * Loads the checked-in version 1 catalog from this class's defining class loader.
     *
     * @return validated fixture catalog
     * @throws ConformanceValidationException when a resource is absent or invalid
     */
    public ConformanceFixtureCatalog loadDefault() {
        ClassLoader classLoader = ConformanceFixtureLoader.class.getClassLoader();
        InputStream manifestStream = classLoader.getResourceAsStream(DEFAULT_MANIFEST_RESOURCE);
        if (manifestStream == null) {
            throw new ConformanceValidationException(
                    "Conformance manifest resource '" + DEFAULT_MANIFEST_RESOURCE + "' was not found.");
        }
        ClassLoader resourceLoader = classLoader;
        return load(manifestStream, resourceLoader::getResourceAsStream);
    }

    /**
     * Loads a manifest and all resources it registers.
     *
     * @param manifestStream manifest JSON stream
     * @param resolver fixture resource resolver
     * @return validated fixture catalog
     * @throws ConformanceValidationException when the manifest or a fixture is invalid
     */
    public ConformanceFixtureCatalog load(InputStream manifestStream, FixtureResourceResolver resolver) {
        Objects.requireNonNull(manifestStream, "manifestStream");
        Objects.requireNonNull(resolver, "resolver");
        ConformanceManifest manifest = parseManifest(readJson(manifestStream, "manifest"));
        LinkedHashMap<String, ConformanceFixture> fixtures = new LinkedHashMap<>();

        for (ManifestCase manifestCase : manifest.cases()) {
            try {
                InputStream fixtureStream = resolver.open(manifestCase.fixture());
                if (fixtureStream == null) {
                    throw new ConformanceValidationException(
                            "Fixture resource '" + manifestCase.fixture() + "' was not found.");
                }
                ConformanceFixture fixture = loadFixture(fixtureStream, manifestCase.fixture());
                if (!fixture.caseId().equals(manifestCase.caseId())) {
                    throw new ConformanceValidationException("Fixture '"
                            + manifestCase.fixture()
                            + "' declares "
                            + fixture.caseId()
                            + " but manifest registers "
                            + manifestCase.caseId()
                            + ".");
                }
                if (fixture.kind() != manifestCase.kind()) {
                    throw new ConformanceValidationException("Fixture '"
                            + manifestCase.fixture()
                            + "' kind "
                            + fixture.kind().wireName()
                            + " does not match manifest kind "
                            + manifestCase.kind().wireName()
                            + ".");
                }
                fixtures.put(fixture.caseId(), fixture);
            } catch (IOException exception) {
                throw new ConformanceValidationException(
                        "Unable to open fixture resource '" + manifestCase.fixture() + "'.", exception);
            }
        }

        return new ConformanceFixtureCatalog(manifest, fixtures);
    }

    /**
     * Loads one fixture using its explicit kind discriminator.
     *
     * @param fixtureStream fixture JSON stream
     * @param sourceName source name used in validation messages
     * @return validated fixture
     * @throws ConformanceValidationException when the fixture is invalid
     */
    public ConformanceFixture loadFixture(InputStream fixtureStream, String sourceName) {
        Objects.requireNonNull(fixtureStream, "fixtureStream");
        FixtureValidation.requireNonBlank(sourceName, "sourceName");
        JsonNode root = readJson(fixtureStream, sourceName);
        requireObject(root, sourceName);

        int schemaVersion = requiredInt(root, "schemaVersion", sourceName);
        if (schemaVersion != FixtureValidation.SUPPORTED_SCHEMA_VERSION) {
            throw new ConformanceValidationException(
                    sourceName + " has unsupported schemaVersion " + schemaVersion + ".");
        }
        String caseId = requiredText(root, "caseId", sourceName);
        String description = requiredText(root, "description", sourceName);
        FixtureKind kind = FixtureKind.fromWireName(requiredText(root, "kind", sourceName));
        ConformanceValue.ObjectValue expected = requiredObjectValue(root, "expected", sourceName);
        if (expected.values().isEmpty()) {
            throw new ConformanceValidationException(sourceName + " expected must not be empty.");
        }

        return switch (kind) {
            case CONTRACT, MESSAGE_CONTENT, RESPONSE_AGGREGATION -> {
                rejectUnknownFields(root, with(COMMON_FIXTURE_FIELDS, "input"), sourceName);
                yield new BehaviorFixture(
                        schemaVersion,
                        caseId,
                        kind,
                        description,
                        requiredObjectValue(root, "input", sourceName),
                        expected);
            }
            case TOOL_LOOP, RUN_SIGNAL, WORKFLOW_TRACE -> {
                rejectUnknownFields(root, with(COMMON_FIXTURE_FIELDS, "events"), sourceName);
                yield new EventHistoryFixture(
                        schemaVersion,
                        caseId,
                        kind,
                        description,
                        requiredObjectList(root, "events", sourceName),
                        expected);
            }
            case SESSION_SNAPSHOT -> {
                rejectUnknownFields(root, with(COMMON_FIXTURE_FIELDS, "envelope", "operations"), sourceName);
                yield new SnapshotFixture(
                        schemaVersion,
                        caseId,
                        kind,
                        description,
                        requiredObjectValue(root, "envelope", sourceName),
                        requiredObjectList(root, "operations", sourceName),
                        expected);
            }
        };
    }

    private static ObjectMapper createMapper() {
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        return new ObjectMapper(factory);
    }

    private static JsonNode readJson(InputStream input, String sourceName) {
        try (input;
                JsonParser parser = MAPPER.getFactory().createParser(input)) {
            JsonNode root = MAPPER.readTree(parser);
            if (root == null) {
                throw new ConformanceValidationException(sourceName + " is empty.");
            }
            JsonToken trailing = parser.nextToken();
            if (trailing != null) {
                throw new ConformanceValidationException(sourceName + " contains trailing JSON content.");
            }
            return root;
        } catch (ConformanceValidationException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ConformanceValidationException("Invalid JSON in " + sourceName + ".", exception);
        }
    }

    private static ConformanceManifest parseManifest(JsonNode root) {
        requireObject(root, "manifest");
        rejectUnknownFields(root, MANIFEST_FIELDS, "manifest");
        int schemaVersion = requiredInt(root, "schemaVersion", "manifest");
        JsonNode casesNode = required(root, "cases", "manifest");
        if (!casesNode.isArray()) {
            throw new ConformanceValidationException("manifest cases must be an array.");
        }
        ArrayList<ManifestCase> cases = new ArrayList<>();
        int index = 0;
        for (JsonNode caseNode : casesNode) {
            String source = "manifest cases[" + index + "]";
            requireObject(caseNode, source);
            rejectUnknownFields(caseNode, MANIFEST_CASE_FIELDS, source);
            ManifestCase manifestCase = new ManifestCase(
                    requiredText(caseNode, "caseId", source),
                    requiredText(caseNode, "suiteId", source),
                    requiredText(caseNode, "matrixStatus", source),
                    requiredStringList(caseNode, "matrixAreas", source),
                    requiredText(caseNode, "fixture", source),
                    FixtureKind.fromWireName(requiredText(caseNode, "kind", source)),
                    requiredStringList(caseNode, "sourceReferences", source));
            cases.add(manifestCase);
            index++;
        }
        return new ConformanceManifest(schemaVersion, cases);
    }

    private static Set<String> with(Set<String> base, String... additions) {
        HashSet<String> fields = new HashSet<>(base);
        fields.addAll(List.of(additions));
        return Set.copyOf(fields);
    }

    private static void rejectUnknownFields(JsonNode node, Set<String> allowed, String sourceName) {
        Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowed.contains(field)) {
                throw new ConformanceValidationException(sourceName + " contains unknown field '" + field + "'.");
            }
        }
    }

    private static JsonNode required(JsonNode node, String field, String sourceName) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new ConformanceValidationException(sourceName + " is missing required field '" + field + "'.");
        }
        return value;
    }

    private static String requiredText(JsonNode node, String field, String sourceName) {
        JsonNode value = required(node, field, sourceName);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new ConformanceValidationException(sourceName + " field '" + field + "' must be a non-blank string.");
        }
        return value.textValue();
    }

    private static int requiredInt(JsonNode node, String field, String sourceName) {
        JsonNode value = required(node, field, sourceName);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new ConformanceValidationException(sourceName + " field '" + field + "' must be an integer.");
        }
        return value.intValue();
    }

    private static List<String> requiredStringList(JsonNode node, String field, String sourceName) {
        JsonNode value = required(node, field, sourceName);
        if (!value.isArray()) {
            throw new ConformanceValidationException(sourceName + " field '" + field + "' must be an array.");
        }
        ArrayList<String> result = new ArrayList<>();
        int index = 0;
        for (JsonNode element : value) {
            if (!element.isTextual() || element.textValue().isBlank()) {
                throw new ConformanceValidationException(
                        sourceName + " field '" + field + "[" + index + "]' must be a non-blank string.");
            }
            result.add(element.textValue());
            index++;
        }
        return List.copyOf(result);
    }

    private static ConformanceValue.ObjectValue requiredObjectValue(JsonNode node, String field, String sourceName) {
        JsonNode value = required(node, field, sourceName);
        requireObject(value, sourceName + " field '" + field + "'");
        return toObjectValue(value);
    }

    private static List<ConformanceValue.ObjectValue> requiredObjectList(
            JsonNode node, String field, String sourceName) {
        JsonNode value = required(node, field, sourceName);
        if (!value.isArray()) {
            throw new ConformanceValidationException(sourceName + " field '" + field + "' must be an array.");
        }
        ArrayList<ConformanceValue.ObjectValue> result = new ArrayList<>();
        int index = 0;
        for (JsonNode element : value) {
            requireObject(element, sourceName + " field '" + field + "[" + index + "]'");
            result.add(toObjectValue(element));
            index++;
        }
        return List.copyOf(result);
    }

    private static void requireObject(JsonNode node, String sourceName) {
        if (!node.isObject()) {
            throw new ConformanceValidationException(sourceName + " must be a JSON object.");
        }
    }

    private static ConformanceValue.ObjectValue toObjectValue(JsonNode node) {
        LinkedHashMap<String, ConformanceValue> values = new LinkedHashMap<>();
        node.properties().forEach(entry -> values.put(entry.getKey(), toValue(entry.getValue())));
        return new ConformanceValue.ObjectValue(values);
    }

    private static ConformanceValue toValue(JsonNode node) {
        if (node.isObject()) {
            return toObjectValue(node);
        }
        if (node.isArray()) {
            ArrayList<ConformanceValue> values = new ArrayList<>();
            node.forEach(value -> values.add(toValue(value)));
            return new ConformanceValue.ArrayValue(values);
        }
        if (node.isTextual()) {
            return new ConformanceValue.StringValue(node.textValue());
        }
        if (node.isNumber()) {
            BigDecimal value = node.decimalValue();
            return new ConformanceValue.NumberValue(value);
        }
        if (node.isBoolean()) {
            return new ConformanceValue.BooleanValue(node.booleanValue());
        }
        if (node.isNull()) {
            return ConformanceValue.NullValue.INSTANCE;
        }
        throw new ConformanceValidationException("Unsupported JSON token " + node.getNodeType() + ".");
    }
}
