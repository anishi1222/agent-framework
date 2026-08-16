// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Holds versioned positive and rejection metadata and provides bounded access to raw resources.
 */
public final class SerializationRejectionCorpus {
    private final List<SerializationPositiveControl> positiveControls;

    private final Map<String, SerializationPositiveControl> positiveControlsById;

    private final List<SerializationRejectionCase> cases;

    private final Map<String, SerializationRejectionCase> casesById;

    private final FixtureResourceResolver resolver;

    private final int maxRawResourceBytes;

    SerializationRejectionCorpus(
            List<SerializationPositiveControl> positiveControls,
            List<SerializationRejectionCase> cases,
            FixtureResourceResolver resolver,
            int maxRawResourceBytes) {
        this.positiveControls = List.copyOf(positiveControls);
        this.cases = List.copyOf(cases);
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.maxRawResourceBytes = maxRawResourceBytes;
        LinkedHashMap<String, SerializationPositiveControl> indexedControls = new LinkedHashMap<>();
        for (SerializationPositiveControl control : positiveControls) {
            if (indexedControls.putIfAbsent(control.controlId(), control) != null) {
                throw new ConformanceValidationException(
                        "Duplicate serialization positive controlId '" + control.controlId() + "'.");
            }
        }
        positiveControlsById = Map.copyOf(indexedControls);
        LinkedHashMap<String, SerializationRejectionCase> indexed = new LinkedHashMap<>();
        for (SerializationRejectionCase rejectionCase : cases) {
            if (indexed.putIfAbsent(rejectionCase.caseId(), rejectionCase) != null) {
                throw new ConformanceValidationException(
                        "Duplicate serialization rejection caseId '" + rejectionCase.caseId() + "'.");
            }
        }
        casesById = Map.copyOf(indexed);
        validateCorpusCoverage();
    }

    /**
     * Returns valid positive controls in manifest order.
     *
     * @return immutable positive controls
     */
    public List<SerializationPositiveControl> positiveControls() {
        return positiveControls;
    }

    /**
     * Returns rejection cases in manifest order.
     *
     * @return immutable cases
     */
    public List<SerializationRejectionCase> cases() {
        return cases;
    }

    /**
     * Returns one required rejection case.
     *
     * @param caseId stable case identifier
     * @return matching case
     * @throws ConformanceValidationException when the case is absent
     */
    public SerializationRejectionCase requireCase(String caseId) {
        SerializationRejectionCase rejectionCase = casesById.get(caseId);
        if (rejectionCase == null) {
            throw new ConformanceValidationException("Unknown serialization rejection caseId '" + caseId + "'.");
        }
        return rejectionCase;
    }

    /**
     * Returns one required positive control.
     *
     * @param controlId stable control identifier
     * @return matching positive control
     * @throws ConformanceValidationException when the control is absent
     */
    public SerializationPositiveControl requirePositiveControl(String controlId) {
        SerializationPositiveControl control = positiveControlsById.get(controlId);
        if (control == null) {
            throw new ConformanceValidationException("Unknown serialization positive controlId '" + controlId + "'.");
        }
        return control;
    }

    /**
     * Reads an intentionally unsafe raw resource without parsing it and with a configured hard cap.
     *
     * @param rejectionCase registered case
     * @return raw bytes
     * @throws ConformanceValidationException when the resource is absent, unregistered, or too large
     */
    public byte[] readRaw(SerializationRejectionCase rejectionCase) {
        Objects.requireNonNull(rejectionCase, "rejectionCase");
        if (casesById.get(rejectionCase.caseId()) != rejectionCase) {
            throw new ConformanceValidationException(
                    "Serialization rejection case '" + rejectionCase.caseId() + "' is not registered in this corpus.");
        }
        return readRaw(rejectionCase.resource());
    }

    /**
     * Reads one valid positive-control resource without parsing it and with a configured hard cap.
     *
     * @param control registered positive control
     * @return raw bytes
     * @throws ConformanceValidationException when the resource is absent, unregistered, or too large
     */
    public byte[] readRaw(SerializationPositiveControl control) {
        Objects.requireNonNull(control, "control");
        if (positiveControlsById.get(control.controlId()) != control) {
            throw new ConformanceValidationException(
                    "Serialization positive control '" + control.controlId() + "' is not registered in this corpus.");
        }
        return readRaw(control.resource());
    }

    private byte[] readRaw(String resource) {
        try {
            InputStream opened = resolver.open(resource);
            if (opened == null) {
                throw new ConformanceValidationException(
                        "Raw serialization corpus resource '" + resource + "' was not found.");
            }
            try (InputStream input = opened) {
                byte[] bytes = input.readNBytes(maxRawResourceBytes + 1);
                if (bytes.length > maxRawResourceBytes) {
                    throw new ConformanceValidationException("Raw serialization corpus resource '"
                            + resource
                            + "' exceeds corpus safety cap "
                            + maxRawResourceBytes
                            + " bytes.");
                }
                return bytes;
            }
        } catch (IOException exception) {
            throw new ConformanceValidationException(
                    "Unable to read raw serialization corpus resource '" + resource + "'.", exception);
        }
    }

    private void validateCorpusCoverage() {
        for (SerializationDocumentKind kind : SerializationDocumentKind.values()) {
            boolean present = positiveControls.stream().anyMatch(control -> control.documentKind() == kind);
            if (!present) {
                throw new ConformanceValidationException(
                        "Serialization corpus must declare a positive control for " + kind.wireName() + ".");
            }
        }
        LinkedHashMap<String, String> resources = new LinkedHashMap<>();
        positiveControls.forEach(control -> registerResource(resources, control.resource(), control.controlId()));
        cases.forEach(rejectionCase -> registerResource(resources, rejectionCase.resource(), rejectionCase.caseId()));
        Set<String> positiveIds = positiveControlsById.keySet();
        for (String caseId : casesById.keySet()) {
            if (positiveIds.contains(caseId)) {
                throw new ConformanceValidationException(
                        "Serialization corpus identifier '" + caseId + "' is declared more than once.");
            }
        }
    }

    private static void registerResource(Map<String, String> resources, String resource, String id) {
        String previous = resources.putIfAbsent(resource, id);
        if (previous != null) {
            throw new ConformanceValidationException("Serialization corpus resource '"
                    + resource
                    + "' is registered by both "
                    + previous
                    + " and "
                    + id
                    + ".");
        }
    }
}
