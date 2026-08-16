// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.valkey;

import java.util.Optional;

enum ValkeyNoAuthentication implements ValkeyAuthentication {
    INSTANCE;

    @Override
    public Kind kind() {
        return Kind.NONE;
    }

    @Override
    public Optional<String> username() {
        return Optional.empty();
    }

    @Override
    public Optional<ValkeyPassword> password() {
        return Optional.empty();
    }

    @Override
    public String toString() {
        return "ValkeyAuthentication.none()";
    }
}
