// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.conformance;

import java.util.Objects;

/** Executes production readers against positive controls and portable raw rejection cases. */
public final class SerializationRejectionAssertions {
    private SerializationRejectionAssertions() {}

    /**
     * Asserts that one independently addressable raw case is rejected.
     *
     * @param corpus loaded corpus
     * @param caseId case to execute
     * @param reader production reader adapter returning typed outcomes
     * @throws Exception when the adapter fails outside its expected rejection path
     * @throws AssertionError when the reader accepts the unsafe input
     */
    public static void assertRejected(
            SerializationRejectionCorpus corpus, String caseId, SerializationReaderAdapter reader) throws Exception {
        Objects.requireNonNull(corpus, "corpus");
        Objects.requireNonNull(reader, "reader");
        SerializationRejectionCase rejectionCase = corpus.requireCase(caseId);
        byte[] raw = corpus.readRaw(rejectionCase);
        SerializationReadResult result = Objects.requireNonNull(
                reader.read(rejectionCase.documentKind(), raw, rejectionCase.limits()), "reader result");
        if (result instanceof SerializationReadResult.Rejected rejected) {
            if (rejected.reason() == rejectionCase.reason()) {
                return;
            }
            throw new AssertionError("Serialization rejection case "
                    + rejectionCase.caseId()
                    + " expected reason "
                    + rejectionCase.reason().wireName()
                    + " but reader reported "
                    + rejected.reason().wireName()
                    + ".");
        }
        throw new AssertionError("Serialization rejection case "
                + rejectionCase.caseId()
                + " ("
                + rejectionCase.reason().wireName()
                + ") was accepted by the "
                + rejectionCase.documentKind().wireName()
                + " reader.");
    }

    /**
     * Asserts that one valid positive control is accepted.
     *
     * @param corpus loaded corpus
     * @param controlId positive control to execute
     * @param reader production reader adapter returning typed outcomes
     * @throws Exception when an adapter fails outside its expected rejection path
     * @throws AssertionError when the valid input is rejected
     */
    public static void assertAccepted(
            SerializationRejectionCorpus corpus, String controlId, SerializationReaderAdapter reader) throws Exception {
        Objects.requireNonNull(corpus, "corpus");
        Objects.requireNonNull(reader, "reader");
        SerializationPositiveControl control = corpus.requirePositiveControl(controlId);
        byte[] raw = corpus.readRaw(control);
        SerializationReadResult result =
                Objects.requireNonNull(reader.read(control.documentKind(), raw, control.limits()), "reader result");
        if (result instanceof SerializationReadResult.Rejected rejected) {
            throw new AssertionError("Serialization positive control "
                    + control.controlId()
                    + " for the "
                    + control.documentKind().wireName()
                    + " reader was rejected as "
                    + rejected.reason().wireName()
                    + ".");
        }
    }

    /**
     * Asserts the complete adapter contract: every valid reader control is accepted and every
     * unsafe case is rejected for its declared reason.
     *
     * @param corpus loaded corpus
     * @param reader production reader adapter returning typed outcomes
     * @throws Exception when an adapter fails outside its expected rejection path
     * @throws AssertionError when any positive or negative corpus assertion fails
     */
    public static void assertConforms(SerializationRejectionCorpus corpus, SerializationReaderAdapter reader)
            throws Exception {
        Objects.requireNonNull(corpus, "corpus");
        Objects.requireNonNull(reader, "reader");
        for (SerializationPositiveControl control : corpus.positiveControls()) {
            assertAccepted(corpus, control.controlId(), reader);
        }
        for (SerializationRejectionCase rejectionCase : corpus.cases()) {
            assertRejected(corpus, rejectionCase.caseId(), reader);
        }
    }
}
