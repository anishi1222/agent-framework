// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.azureaisearch;

import com.microsoft.agents.core.ValidationException;
import java.util.LinkedHashSet;
import java.util.List;

/** Maps framework retrieval roles to strict Azure AI Search field names. */
public final class AzureAISearchFieldMapping {
    private final String keyField;

    private final String contentField;

    private final String titleField;

    private final String sourceUrlField;

    private final String tenantIdField;

    private final String scopeIdField;

    private final String vectorField;

    private AzureAISearchFieldMapping(Builder builder) {
        keyField = requiredField(builder.keyField, "keyField");
        contentField = requiredField(builder.contentField, "contentField");
        titleField = optionalField(builder.titleField, "titleField");
        sourceUrlField = optionalField(builder.sourceUrlField, "sourceUrlField");
        tenantIdField = requiredField(builder.tenantIdField, "tenantIdField");
        scopeIdField = requiredField(builder.scopeIdField, "scopeIdField");
        vectorField = optionalField(builder.vectorField, "vectorField");
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        List.of(keyField, contentField, tenantIdField, scopeIdField).forEach(distinct::add);
        if (titleField != null) {
            distinct.add(titleField);
        }
        if (sourceUrlField != null) {
            distinct.add(sourceUrlField);
        }
        if (vectorField != null) {
            distinct.add(vectorField);
        }
        int configured =
                4 + (titleField == null ? 0 : 1) + (sourceUrlField == null ? 0 : 1) + (vectorField == null ? 0 : 1);
        if (distinct.size() != configured) {
            throw new ValidationException("Mapped Azure AI Search fields must be distinct.");
        }
    }

    /**
     * Creates a field-mapping builder with conventional names.
     *
     * @return new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns conventional field mapping.
     *
     * @return default mapping
     */
    public static AzureAISearchFieldMapping defaults() {
        return builder().build();
    }

    /** Returns the document key field. */
    public String keyField() {
        return keyField;
    }

    /** Returns the retrievable content field. */
    public String contentField() {
        return contentField;
    }

    /** Returns the optional title field. */
    public String titleField() {
        return titleField;
    }

    /** Returns the optional source-URL field. */
    public String sourceUrlField() {
        return sourceUrlField;
    }

    /** Returns the mandatory filterable tenant field. */
    public String tenantIdField() {
        return tenantIdField;
    }

    /** Returns the mandatory filterable scope field. */
    public String scopeIdField() {
        return scopeIdField;
    }

    /** Returns the configured vector field, or {@code null} for strict schema discovery. */
    public String vectorField() {
        return vectorField;
    }

    List<String> desiredSelectFields() {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        fields.add(keyField);
        fields.add(contentField);
        if (titleField != null) {
            fields.add(titleField);
        }
        if (sourceUrlField != null) {
            fields.add(sourceUrlField);
        }
        return List.copyOf(fields);
    }

    private static String requiredField(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(name + " must not be blank.");
        }
        return validateField(value, name);
    }

    private static String optionalField(String value, String name) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new ValidationException(name + " must be null or non-blank.");
        }
        return validateField(value, name);
    }

    private static String validateField(String value, String name) {
        if (value.length() > 128
                || !isAsciiLetter(value.charAt(0))
                || value.chars()
                        .skip(1)
                        .anyMatch(character -> !(isAsciiLetter(character)
                                || character >= '0' && character <= '9'
                                || character == '_'))) {
            throw new ValidationException(name + " must be a simple Azure AI Search field identifier.");
        }
        return value;
    }

    private static boolean isAsciiLetter(int character) {
        return character >= 'A' && character <= 'Z' || character >= 'a' && character <= 'z';
    }

    /** Builds immutable {@link AzureAISearchFieldMapping} instances. */
    public static final class Builder {
        private String keyField = "id";

        private String contentField = "content";

        private String titleField = "title";

        private String sourceUrlField = "sourceUrl";

        private String tenantIdField = "tenantId";

        private String scopeIdField = "scopeId";

        private String vectorField;

        private Builder() {}

        /** Sets the key field. */
        public Builder keyField(String value) {
            keyField = value;
            return this;
        }

        /** Sets the content field. */
        public Builder contentField(String value) {
            contentField = value;
            return this;
        }

        /** Sets the optional title field. */
        public Builder titleField(String value) {
            titleField = value;
            return this;
        }

        /** Sets the optional source URL field. */
        public Builder sourceUrlField(String value) {
            sourceUrlField = value;
            return this;
        }

        /** Sets the mandatory tenant filter field. */
        public Builder tenantIdField(String value) {
            tenantIdField = value;
            return this;
        }

        /** Sets the mandatory scope filter field. */
        public Builder scopeIdField(String value) {
            scopeIdField = value;
            return this;
        }

        /** Sets an explicit vector field, or {@code null} to discover exactly one safe candidate. */
        public Builder vectorField(String value) {
            vectorField = value;
            return this;
        }

        /**
         * Creates the mapping.
         *
         * @return immutable mapping
         */
        public AzureAISearchFieldMapping build() {
            return new AzureAISearchFieldMapping(this);
        }
    }
}
