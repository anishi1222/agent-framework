// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.valkey;

import java.util.List;
import java.util.concurrent.CompletionStage;

interface ValkeyCommandAdapter extends AutoCloseable {
    CompletionStage<Object> invokeScript(ValkeyScript script, List<byte[]> keys, List<byte[]> arguments);

    CompletionStage<Long> listLength(byte[] key);

    @Override
    void close();
}
