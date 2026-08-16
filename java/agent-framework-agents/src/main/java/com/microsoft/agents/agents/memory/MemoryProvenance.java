// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.memory;

/**
 * Describes the origin of one retrieved memory without assigning instruction privilege.
 *
 * @param source stable store or provider identifier
 * @param recordId stable memory identifier
 * @param citation human-readable citation
 */
public record MemoryProvenance(String source, String recordId, String citation) {
    /** Creates validated provenance. */
    public MemoryProvenance {
        source = MemoryValidation.requireNonBlank(source, "source");
        recordId = MemoryValidation.requireNonBlank(recordId, "recordId");
        citation = MemoryValidation.requireNonBlank(citation, "citation");
    }
}
