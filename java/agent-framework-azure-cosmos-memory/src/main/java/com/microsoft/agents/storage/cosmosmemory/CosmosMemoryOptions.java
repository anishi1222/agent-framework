// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmosmemory;

import com.microsoft.agents.core.ValidationException;
import com.microsoft.agents.storage.cosmos.CosmosStorageOptions;

/**
 * Configures tenant-isolated Cosmos memory CRUD and search.
 *
 * @param storage shared Cosmos client/container/partition/limit options
 * @param vector exact vector policy and index contract
 * @param fullTextEnabled whether full-text/hybrid server queries are enabled
 * @param fullTextLanguage Cosmos full-text policy language
 * @param timeToLiveSeconds optional positive memory TTL
 * @param pageSize bounded list/search page size
 * @param maxQueryTerms maximum parameterized full-text terms
 * @param maxFilterTerms maximum metadata equality predicates
 * @param fallback explicit fallback policy
 * @param fallbackMaxDocuments bounded single-partition scan limit
 */
public record CosmosMemoryOptions(
        CosmosStorageOptions storage,
        CosmosMemoryVectorOptions vector,
        boolean fullTextEnabled,
        String fullTextLanguage,
        Integer timeToLiveSeconds,
        int pageSize,
        int maxQueryTerms,
        int maxFilterTerms,
        CosmosMemoryFallback fallback,
        int fallbackMaxDocuments) {
    /** Maximum UTF-8 bytes accepted for one memory or query string. */
    public static final int MAX_STRING_BYTES = 250_000;

    /** Maximum nested metadata depth accepted from stored documents. */
    public static final int MAX_METADATA_DEPTH = 64;

    /** Maximum metadata entries accepted across one record. */
    public static final int MAX_METADATA_ENTRIES = 10_000;

    /** Fixed vector property and policy path. */
    public static final String VECTOR_PATH = "/vector";

    /** Fixed full-text property and policy path. */
    public static final String FULL_TEXT_PATH = "/content";

    /** Creates validated bounded memory options. */
    public CosmosMemoryOptions {
        if (storage == null || vector == null) {
            throw new ValidationException("storage and vector are required.");
        }
        if (fullTextEnabled && (fullTextLanguage == null || fullTextLanguage.isBlank())) {
            throw new ValidationException("fullTextLanguage is required when full text is enabled.");
        }
        if (timeToLiveSeconds != null && timeToLiveSeconds <= 0) {
            throw new ValidationException("timeToLiveSeconds must be positive when present.");
        }
        if (pageSize <= 0 || pageSize > storage.maxPageSize()) {
            throw new ValidationException("pageSize must be positive and not exceed storage.maxPageSize.");
        }
        if (maxQueryTerms <= 0 || maxQueryTerms > 32) {
            throw new ValidationException("maxQueryTerms must be between 1 and 32.");
        }
        if (maxFilterTerms < 0 || maxFilterTerms > 32) {
            throw new ValidationException("maxFilterTerms must be between 0 and 32.");
        }
        fallback = fallback == null ? CosmosMemoryFallback.DISABLED : fallback;
        if (fallbackMaxDocuments <= 0 || fallbackMaxDocuments > 10_000) {
            throw new ValidationException("fallbackMaxDocuments must be between 1 and 10000.");
        }
        if (storage.container().provisioning().enabled()
                && timeToLiveSeconds != null
                && storage.container().provisioning().defaultTimeToLiveSeconds() == null) {
            throw new ValidationException("Provisioned containers must enable TTL when memory TTL is configured.");
        }
    }
}
