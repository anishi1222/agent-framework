// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.core.SerializationError;
import com.microsoft.agents.core.SerializationException;
import com.microsoft.agents.core.StateCodec;
import com.microsoft.agents.core.StateValue;

/** Provides small framework-owned codecs for common workflow payload and state values. */
public final class WorkflowCodecs {
    private static final StateCodec<String> STRING = new StringCodec();

    private static final StateCodec<Integer> INTEGER = new IntegerCodec();

    private static final StateCodec<Long> LONG = new LongCodec();

    private static final StateCodec<Boolean> BOOLEAN = new BooleanCodec();

    private static final StateCodec<StateValue> STATE_VALUE = new StateValueCodec();

    private WorkflowCodecs() {}

    /**
     * Returns the stable string codec.
     *
     * @return string codec
     */
    public static StateCodec<String> stringCodec() {
        return STRING;
    }

    /**
     * Returns the stable integer codec.
     *
     * @return integer codec
     */
    public static StateCodec<Integer> integerCodec() {
        return INTEGER;
    }

    /**
     * Returns the stable long codec.
     *
     * @return long codec
     */
    public static StateCodec<Long> longCodec() {
        return LONG;
    }

    /**
     * Returns the stable Boolean codec.
     *
     * @return Boolean codec
     */
    public static StateCodec<Boolean> booleanCodec() {
        return BOOLEAN;
    }

    /**
     * Returns the identity codec for framework-owned JSON-shaped values.
     *
     * @return state-value codec
     */
    public static StateCodec<StateValue> stateValueCodec() {
        return STATE_VALUE;
    }

    private abstract static class VersionOneCodec<T> implements StateCodec<T> {
        @Override
        public int currentVersion() {
            return 1;
        }

        @Override
        public StateValue migrate(StateValue value, int fromVersion, int toVersion) {
            throw new UnsupportedOperationException("Version 1 codecs have no earlier migration.");
        }

        SerializationException malformed(String expected) {
            return new SerializationException(
                    SerializationError.MALFORMED_DOCUMENT, "Workflow codec expected " + expected + ".");
        }
    }

    private static final class StringCodec extends VersionOneCodec<String> {
        @Override
        public String typeId() {
            return "com.microsoft.agents.workflows.string";
        }

        @Override
        public StateValue encode(String value) {
            return StateValue.string(value);
        }

        @Override
        public String decode(StateValue value, int version) {
            if (value instanceof StateValue.StringValue string) {
                return string.value();
            }
            throw malformed("a string");
        }
    }

    private static final class IntegerCodec extends VersionOneCodec<Integer> {
        @Override
        public String typeId() {
            return "com.microsoft.agents.workflows.integer";
        }

        @Override
        public StateValue encode(Integer value) {
            return StateValue.integer(value.longValue());
        }

        @Override
        public Integer decode(StateValue value, int version) {
            if (value instanceof StateValue.NumberValue number && number.value().scale() <= 0) {
                try {
                    return number.value().intValueExact();
                } catch (ArithmeticException exception) {
                    throw malformed("an integer");
                }
            }
            throw malformed("an integer");
        }
    }

    private static final class LongCodec extends VersionOneCodec<Long> {
        @Override
        public String typeId() {
            return "com.microsoft.agents.workflows.long";
        }

        @Override
        public StateValue encode(Long value) {
            return StateValue.integer(value);
        }

        @Override
        public Long decode(StateValue value, int version) {
            if (value instanceof StateValue.NumberValue number && number.value().scale() <= 0) {
                try {
                    return number.value().longValueExact();
                } catch (ArithmeticException exception) {
                    throw malformed("a long integer");
                }
            }
            throw malformed("a long integer");
        }
    }

    private static final class BooleanCodec extends VersionOneCodec<Boolean> {
        @Override
        public String typeId() {
            return "com.microsoft.agents.workflows.boolean";
        }

        @Override
        public StateValue encode(Boolean value) {
            return StateValue.bool(value);
        }

        @Override
        public Boolean decode(StateValue value, int version) {
            if (value instanceof StateValue.BooleanValue bool) {
                return bool.value();
            }
            throw malformed("a Boolean");
        }
    }

    private static final class StateValueCodec extends VersionOneCodec<StateValue> {
        @Override
        public String typeId() {
            return "com.microsoft.agents.workflows.state-value";
        }

        @Override
        public StateValue encode(StateValue value) {
            return value;
        }

        @Override
        public StateValue decode(StateValue value, int version) {
            return value;
        }
    }
}
