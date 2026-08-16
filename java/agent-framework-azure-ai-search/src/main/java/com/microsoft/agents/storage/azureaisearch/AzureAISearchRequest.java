// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.azureaisearch;

import com.microsoft.agents.agents.memory.EmbeddingVector;
import com.microsoft.agents.agents.memory.MemoryScope;

record AzureAISearchRequest(MemoryScope scope, String query, EmbeddingVector embedding) {}
