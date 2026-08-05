// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

/**
 * Adapts production session and checkpoint readers to the portable serialization corpus.
 *
 * <p>Expected parser and envelope rejections must be mapped to a typed
 * {@link SerializationReadResult.Rejected} result. Unexpected adapter failures may still be
 * thrown.
 */
@FunctionalInterface
public interface SerializationReaderAdapter {
    /**
     * Attempts to read one raw state document.
     *
     * @param documentKind reader that must be selected
     * @param utf8Json raw JSON bytes
     * @param limits explicit limits that must be enforced before or during tokenization
     * @return typed acceptance or rejection outcome
     * @throws Exception when the adapter fails outside an expected rejection path
     */
    SerializationReadResult read(SerializationDocumentKind documentKind, byte[] utf8Json, SerializationLimits limits)
            throws Exception;
}
