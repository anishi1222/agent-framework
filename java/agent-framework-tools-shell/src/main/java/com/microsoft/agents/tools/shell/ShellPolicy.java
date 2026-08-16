// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools.shell;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Applies deny-first regular-expression filtering before shell execution.
 *
 * <p>This policy is a user-experience guardrail, not a security boundary. Shell expansion,
 * interpreter escapes, and alternate tools can bypass spelling-based patterns. Human approval and
 * process or container isolation remain the security boundaries.
 */
public final class ShellPolicy {
    private final List<Pattern> denyPatterns;
    private final List<Pattern> allowPatterns;
    private final boolean allowListEnabled;
    private final Function<ShellRequest, ShellDecision> custom;

    /** Creates a default policy that allows every non-empty command. */
    public ShellPolicy() {
        this(List.of(), null, null);
    }

    /**
     * Creates a layered policy.
     *
     * <p>Deny patterns run first. A non-null allow-pattern list is exclusive, including an empty
     * list, which denies every command. The custom callback runs only after both pattern layers pass
     * and may return {@code null} to retain the default allow decision.
     *
     * @param denyPatterns case-insensitive deny regular expressions
     * @param allowPatterns optional exclusive case-insensitive allow regular expressions
     * @param custom optional final decision callback
     */
    public ShellPolicy(
            List<String> denyPatterns, List<String> allowPatterns, Function<ShellRequest, ShellDecision> custom) {
        this.denyPatterns = compile(denyPatterns, "denyPatterns");
        this.allowListEnabled = allowPatterns != null;
        this.allowPatterns = allowPatterns == null ? List.of() : compile(allowPatterns, "allowPatterns");
        this.custom = custom;
    }

    /**
     * Evaluates one shell request.
     *
     * @param request command request
     * @return deterministic policy decision
     */
    public ShellDecision evaluate(ShellRequest request) {
        Objects.requireNonNull(request, "request");
        String command = request.command().trim();
        if (command.isEmpty()) {
            return ShellDecision.deny("command is empty");
        }
        for (Pattern pattern : denyPatterns) {
            if (pattern.matcher(command).find()) {
                return ShellDecision.deny("matches deny pattern: " + pattern.pattern());
            }
        }
        if (allowListEnabled
                && allowPatterns.stream()
                        .noneMatch(pattern -> pattern.matcher(command).find())) {
            return ShellDecision.deny("command does not match allow list");
        }
        if (custom != null) {
            ShellDecision decision = custom.apply(request);
            if (decision != null) {
                return decision;
            }
        }
        return ShellDecision.allow();
    }

    private static List<Pattern> compile(List<String> expressions, String name) {
        Objects.requireNonNull(expressions, name);
        ArrayList<Pattern> patterns = new ArrayList<>(expressions.size());
        for (String expression : expressions) {
            Objects.requireNonNull(expression, name + " entry");
            if (expression.isBlank()) {
                throw new IllegalArgumentException(name + " entries must not be blank.");
            }
            patterns.add(Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        }
        return List.copyOf(patterns);
    }
}
