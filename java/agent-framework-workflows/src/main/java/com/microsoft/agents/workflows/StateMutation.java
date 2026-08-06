// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.workflows;

import com.microsoft.agents.core.EncodedState;
import java.util.Objects;

record StateMutation(StateKey<?> key, EncodedState value) {
    StateMutation {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
    }
}
