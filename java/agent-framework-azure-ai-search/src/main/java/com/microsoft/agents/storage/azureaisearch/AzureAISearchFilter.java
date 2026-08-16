// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.azureaisearch;

import com.microsoft.agents.agents.memory.MemoryScope;

final class AzureAISearchFilter {
    private AzureAISearchFilter() {}

    static String forScope(AzureAISearchOptions options, MemoryScope scope) {
        AzureAISearchFieldMapping fields = options.fieldMapping();
        String isolation = fields.tenantIdField()
                + " eq '"
                + literal(scope.tenantId())
                + "' and "
                + fields.scopeIdField()
                + " eq '"
                + literal(scope.scopeId())
                + "'";
        return options.staticFilter() == null ? isolation : "(" + isolation + ") and (" + options.staticFilter() + ")";
    }

    static String literal(String value) {
        return value.replace("'", "''");
    }
}
