// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.valkey;

import java.util.Optional;

record ValkeyAclAuthentication(String aclUsername, ValkeyPassword aclPassword) implements ValkeyAuthentication {
    ValkeyAclAuthentication {
        aclUsername = ValkeyValidation.boundedIdentifier(aclUsername, "username", 4096);
        aclPassword = ValkeyValidation.requireNonNull(aclPassword, "password");
    }

    @Override
    public Kind kind() {
        return Kind.ACL;
    }

    @Override
    public Optional<String> username() {
        return Optional.of(aclUsername);
    }

    @Override
    public Optional<ValkeyPassword> password() {
        return Optional.of(aclPassword);
    }

    @Override
    public String toString() {
        return "ValkeyAuthentication.acl([REDACTED])";
    }
}
