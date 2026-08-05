// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Loads only the safe metadata manifest for raw serialization controls and rejection cases.
 *
 * <p>Raw positive and malformed resources are never opened or parsed by this loader.
 */
public final class SerializationRejectionCorpusLoader {
    /** Default classpath location of the version 1 rejection manifest. */
    public static final String DEFAULT_MANIFEST_RESOURCE = "conformance/rejections/manifest-v1.json";

    /** Default safety cap for the metadata manifest. */
    public static final int DEFAULT_MAX_MANIFEST_BYTES = 65_536;

    /** Default safety cap for one intentionally unsafe raw corpus resource. */
    public static final int DEFAULT_MAX_RAW_RESOURCE_BYTES = 2_097_152;

    private static final int MANIFEST_MAX_NESTING_DEPTH = 16;

    private static final int MANIFEST_MAX_STRING_LENGTH = 4_096;

    private static final int MANIFEST_MAX_NUMBER_LENGTH = 32;

    private static final Set<String> LIMIT_FIELDS = Set.of(
            "maxDocumentBytes", "maxNestingDepth", "maxStringLength", "maxNumericTokenLength", "maxCollectionEntries");

    private static final Set<String> CASE_FIELDS =
            Set.of("caseId", "documentKind", "reason", "resource", "limitProfile");

    private static final Set<String> POSITIVE_CONTROL_FIELDS =
            Set.of("controlId", "documentKind", "resource", "limitProfile");

    private final int maxManifestBytes;

    private final int maxRawResourceBytes;

    private final ObjectMapper mapper;

    /** Creates a loader with the named default corpus safety caps. */
    public SerializationRejectionCorpusLoader() {
        this(DEFAULT_MAX_MANIFEST_BYTES, DEFAULT_MAX_RAW_RESOURCE_BYTES);
    }

    /**
     * Creates a loader with explicit corpus safety caps.
     *
     * @param maxManifestBytes metadata manifest cap
     * @param maxRawResourceBytes per-resource raw byte cap
     */
    public SerializationRejectionCorpusLoader(int maxManifestBytes, int maxRawResourceBytes) {
        if (maxManifestBytes <= 0
                || maxRawResourceBytes <= 0
                || maxManifestBytes == Integer.MAX_VALUE
                || maxRawResourceBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Corpus safety caps must be positive and leave room for one overflow-detection byte.");
        }
        this.maxManifestBytes = maxManifestBytes;
        this.maxRawResourceBytes = maxRawResourceBytes;
        mapper = createMapper(maxManifestBytes);
    }

    /**
     * Loads the checked-in version 1 rejection corpus.
     *
     * @return rejection corpus with bounded raw-resource access
     */
    public SerializationRejectionCorpus loadDefault() {
        ClassLoader classLoader = SerializationRejectionCorpusLoader.class.getClassLoader();
        InputStream manifest = classLoader.getResourceAsStream(DEFAULT_MANIFEST_RESOURCE);
        if (manifest == null) {
            throw new ConformanceValidationException(
                    "Serialization rejection manifest resource '" + DEFAULT_MANIFEST_RESOURCE + "' was not found.");
        }
        return load(manifest, classLoader::getResourceAsStream);
    }

    /**
     * Loads corpus metadata while leaving every raw resource unopened and unparsed.
     *
     * @param manifestStream safe metadata manifest
     * @param resolver raw resource resolver retained by the corpus
     * @return validated corpus
     */
    public SerializationRejectionCorpus load(InputStream manifestStream, FixtureResourceResolver resolver) {
        Objects.requireNonNull(manifestStream, "manifestStream");
        Objects.requireNonNull(resolver, "resolver");
        JsonNode manifest = readManifest(manifestStream);
        JsonSchemaV1.exactObject(
                manifest,
                "serialization rejection manifest",
                "schemaVersion",
                "limitProfiles",
                "positiveControls",
                "cases");
        int schemaVersion = JsonSchemaV1.requireInteger(manifest, "schemaVersion", "serialization rejection manifest");
        if (schemaVersion != 1) {
            throw new ConformanceValidationException(
                    "Serialization rejection manifest has unsupported schemaVersion " + schemaVersion + ".");
        }
        Map<String, SerializationLimits> profiles = parseProfiles(
                JsonSchemaV1.requireObject(manifest, "limitProfiles", "serialization rejection manifest"));
        List<SerializationPositiveControl> positiveControls = parsePositiveControls(
                JsonSchemaV1.requireArray(manifest, "positiveControls", "serialization rejection manifest", true),
                profiles,
                schemaVersion);
        JsonNode cases = JsonSchemaV1.requireArray(manifest, "cases", "serialization rejection manifest", true);
        ArrayList<SerializationRejectionCase> parsedCases = new ArrayList<>();
        HashSet<String> caseIds = new HashSet<>();
        for (int index = 0; index < cases.size(); index++) {
            JsonNode caseNode = cases.get(index);
            String source = "serialization rejection manifest cases[" + index + "]";
            JsonSchemaV1.object(caseNode, source, java.util.List.copyOf(CASE_FIELDS), java.util.List.of());
            String caseId = JsonSchemaV1.requireText(caseNode, "caseId", source);
            if (!caseIds.add(caseId)) {
                throw new ConformanceValidationException(
                        "Serialization rejection manifest declares duplicate caseId '" + caseId + "'.");
            }
            String resource = requireCorpusResourcePath(JsonSchemaV1.requireText(caseNode, "resource", source), source);
            String profileName = JsonSchemaV1.requireText(caseNode, "limitProfile", source);
            SerializationLimits limits = profiles.get(profileName);
            if (limits == null) {
                throw new ConformanceValidationException(
                        source + " references unknown limitProfile '" + profileName + "'.");
            }
            parsedCases.add(new SerializationRejectionCase(
                    schemaVersion,
                    caseId,
                    SerializationDocumentKind.fromWireName(JsonSchemaV1.requireText(caseNode, "documentKind", source)),
                    SerializationRejectionReason.fromWireName(JsonSchemaV1.requireText(caseNode, "reason", source)),
                    resource,
                    limits));
        }
        return new SerializationRejectionCorpus(positiveControls, parsedCases, resolver, maxRawResourceBytes);
    }

    private static ObjectMapper createMapper(int maxDocumentBytes) {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxDocumentLength(maxDocumentBytes)
                .maxNestingDepth(MANIFEST_MAX_NESTING_DEPTH)
                .maxStringLength(MANIFEST_MAX_STRING_LENGTH)
                .maxNumberLength(MANIFEST_MAX_NUMBER_LENGTH)
                .build();
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(constraints)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        return new ObjectMapper(factory);
    }

    private JsonNode readManifest(InputStream manifestStream) {
        try (manifestStream) {
            byte[] bytes = manifestStream.readNBytes(maxManifestBytes + 1);
            if (bytes.length > maxManifestBytes) {
                throw new ConformanceValidationException(
                        "Serialization rejection manifest exceeds safety cap " + maxManifestBytes + " bytes.");
            }
            try (JsonParser parser = mapper.getFactory().createParser(bytes)) {
                JsonNode root = mapper.readTree(parser);
                if (root == null) {
                    throw new ConformanceValidationException("Serialization rejection manifest is empty.");
                }
                JsonToken trailing = parser.nextToken();
                if (trailing != null) {
                    throw new ConformanceValidationException(
                            "Serialization rejection manifest contains trailing JSON content.");
                }
                return root;
            }
        } catch (ConformanceValidationException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ConformanceValidationException("Invalid serialization rejection manifest JSON.", exception);
        }
    }

    private static Map<String, SerializationLimits> parseProfiles(JsonNode profiles) {
        if (profiles.isEmpty()) {
            throw new ConformanceValidationException(
                    "Serialization rejection manifest limitProfiles must not be empty.");
        }
        LinkedHashMap<String, SerializationLimits> parsed = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> entries = profiles.properties().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            String source = "serialization rejection manifest limitProfiles." + entry.getKey();
            JsonSchemaV1.object(entry.getValue(), source, java.util.List.copyOf(LIMIT_FIELDS), java.util.List.of());
            JsonNode profile = entry.getValue();
            parsed.put(
                    entry.getKey(),
                    new SerializationLimits(
                            JsonSchemaV1.requirePositiveInteger(profile, "maxDocumentBytes", source),
                            JsonSchemaV1.requirePositiveInteger(profile, "maxNestingDepth", source),
                            JsonSchemaV1.requirePositiveInteger(profile, "maxStringLength", source),
                            JsonSchemaV1.requirePositiveInteger(profile, "maxNumericTokenLength", source),
                            JsonSchemaV1.requirePositiveInteger(profile, "maxCollectionEntries", source)));
        }
        return Map.copyOf(parsed);
    }

    private static List<SerializationPositiveControl> parsePositiveControls(
            JsonNode controls, Map<String, SerializationLimits> profiles, int schemaVersion) {
        ArrayList<SerializationPositiveControl> parsed = new ArrayList<>();
        HashSet<String> controlIds = new HashSet<>();
        for (int index = 0; index < controls.size(); index++) {
            JsonNode control = controls.get(index);
            String source = "serialization rejection manifest positiveControls[" + index + "]";
            JsonSchemaV1.object(control, source, java.util.List.copyOf(POSITIVE_CONTROL_FIELDS), java.util.List.of());
            String controlId = JsonSchemaV1.requireText(control, "controlId", source);
            if (!controlIds.add(controlId)) {
                throw new ConformanceValidationException(
                        "Serialization rejection manifest declares duplicate controlId '" + controlId + "'.");
            }
            String profileName = JsonSchemaV1.requireText(control, "limitProfile", source);
            SerializationLimits limits = profiles.get(profileName);
            if (limits == null) {
                throw new ConformanceValidationException(
                        source + " references unknown limitProfile '" + profileName + "'.");
            }
            parsed.add(new SerializationPositiveControl(
                    schemaVersion,
                    controlId,
                    SerializationDocumentKind.fromWireName(JsonSchemaV1.requireText(control, "documentKind", source)),
                    requireCorpusResourcePath(JsonSchemaV1.requireText(control, "resource", source), source),
                    limits));
        }
        return List.copyOf(parsed);
    }

    private static String requireCorpusResourcePath(String resource, String source) {
        if (resource.startsWith("/")
                || resource.indexOf('\\') >= 0
                || !resource.startsWith("conformance/rejections/v1/")) {
            throw new ConformanceValidationException(
                    source + " resource must be a normalized path under conformance/rejections/v1/.");
        }
        String[] segments = resource.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new ConformanceValidationException(
                        source + " resource must be a normalized path under conformance/rejections/v1/.");
            }
        }
        return resource;
    }
}
