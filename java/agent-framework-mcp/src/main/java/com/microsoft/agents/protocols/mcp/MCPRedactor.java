// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.protocols.mcp;

import java.util.regex.Pattern;

final class MCPRedactor {
    private static final Pattern AUTHORIZATION =
            Pattern.compile("(?i)(authorization\\s*[:=]\\s*)(bearer\\s+)?[^\\s,;]+");

    private static final Pattern SECRET =
            Pattern.compile("(?i)(api[_-]?key|token|secret|password)(\\s*[:=]\\s*)[^\\s,;]+");

    private MCPRedactor() {}

    static String redact(String message) {
        if (message == null || message.isBlank()) {
            return "unspecified failure";
        }
        String redacted = AUTHORIZATION.matcher(message).replaceAll("$1<redacted>");
        return SECRET.matcher(redacted).replaceAll("$1$2<redacted>");
    }
}
