// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** Composes Agent Framework User-Agent values and destination-scoped feature tokens. */
public final class UserAgentUtil {
    /** Canonical HTTP header name. */
    public static final String USER_AGENT_HEADER = "User-Agent";

    /** Environment variable that disables the complete Agent Framework User-Agent contribution. */
    public static final String USER_AGENT_TELEMETRY_DISABLED_ENV_VAR = "AGENT_FRAMEWORK_USER_AGENT_DISABLED";

    /** Foundry-hosting environment marker recognized by the Java runtime. */
    public static final String FOUNDRY_HOSTING_ENV_VAR = "FOUNDRY_HOSTING_ENVIRONMENT";

    /** Framework User-Agent product name. */
    public static final String FRAMEWORK_PRODUCT = "agent-framework-java";

    /** System property that overrides the development-time framework version. */
    public static final String FRAMEWORK_VERSION_PROPERTY = "com.microsoft.agents.framework.version";

    private static final String HOSTED_USER_AGENT_PREFIX = "foundry-hosting";

    private static final String DEVELOPMENT_VERSION = "0.0.0-development";

    private static final Pattern PRODUCT_COMPONENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+\\-]*");

    private static final Pattern FEATURE_COMMENT = Pattern.compile("(?:^|\\s+)\\(feat=v\\d+\\.[0-9a-fA-F]+\\)");

    private static final ConcurrentSkipListSet<String> USER_AGENT_PREFIXES = new ConcurrentSkipListSet<>();

    private static final AtomicBoolean HOSTED_ENVIRONMENT_DETECTED = new AtomicBoolean();

    private static final String FRAMEWORK_VERSION = resolveFrameworkVersion();

    private UserAgentUtil() {}

    /**
     * Returns the framework User-Agent contribution with registered prefixes.
     *
     * @return base User-Agent value without a feature token
     */
    public static String getUserAgent() {
        detectHostedEnvironment();
        return frameworkUserAgent(FRAMEWORK_VERSION, USER_AGENT_PREFIXES);
    }

    /**
     * Composes a deterministic framework User-Agent for a version and prefix collection.
     *
     * @param version framework version
     * @param prefixes product prefixes, sorted and deduplicated before composition
     * @return base User-Agent value without a feature token
     */
    public static String frameworkUserAgent(String version, Collection<String> prefixes) {
        String safeVersion = requireProductComponent(version, "version");
        ConcurrentSkipListSet<String> stablePrefixes = new ConcurrentSkipListSet<>();
        for (String prefix : Objects.requireNonNull(prefixes, "prefixes")) {
            if (prefix == null || prefix.isBlank()) {
                continue;
            }
            stablePrefixes.add(requireProductComponent(prefix, "prefix"));
        }
        String base = FRAMEWORK_PRODUCT + "/" + safeVersion;
        return stablePrefixes.isEmpty() ? base : String.join("/", stablePrefixes) + "/" + base;
    }

    /**
     * Permanently adds a process-wide User-Agent prefix.
     *
     * @param prefix product prefix; blank values are ignored
     */
    public static void addUserAgentPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return;
        }
        USER_AGENT_PREFIXES.add(requireProductComponent(prefix, "prefix"));
    }

    /**
     * Prepends the framework contribution to an existing User-Agent value.
     *
     * <p>The operation is idempotent and returns the original value when whole-User-Agent telemetry
     * is disabled.
     *
     * @param existingUserAgent existing value, or {@code null}
     * @return composed User-Agent value
     */
    public static String prependFrameworkUserAgent(String existingUserAgent) {
        String existing = safeHeaderValue(existingUserAgent);
        if (!isTelemetryEnabled()) {
            return existing;
        }
        if (existing.contains(FRAMEWORK_PRODUCT + "/")) {
            return existing;
        }
        String frameworkUserAgent = getUserAgent();
        return existing.isEmpty() ? frameworkUserAgent : frameworkUserAgent + " " + existing;
    }

    /**
     * Returns an immutable header map with the framework User-Agent contribution prepended.
     *
     * @param headers existing headers, or {@code null}
     * @return immutable copied headers
     */
    public static Map<String, String> withFrameworkUserAgent(Map<String, String> headers) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        if (headers != null) {
            result.putAll(headers);
        }
        String existingKey = result.keySet().stream()
                .filter(key -> USER_AGENT_HEADER.equalsIgnoreCase(key))
                .findFirst()
                .orElse(USER_AGENT_HEADER);
        String existingValue = result.get(existingKey);
        String composed = prependFrameworkUserAgent(existingValue);
        if (!composed.isEmpty()) {
            result.put(existingKey, composed);
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Appends or refreshes the live feature token on a User-Agent value.
     *
     * @param userAgent base or previously stamped User-Agent
     * @param registry feature registry
     * @return User-Agent with at most one current feature comment
     */
    public static String applyFeatureToken(String userAgent, FeatureUsageRegistry registry) {
        String base = removeFeatureToken(userAgent);
        return Objects.requireNonNull(registry, "registry")
                .token()
                .map(token -> base.isEmpty() ? "(feat=" + token + ")" : base + " (feat=" + token + ")")
                .orElse(base);
    }

    /**
     * Removes an Agent Framework feature token while preserving unrelated comments.
     *
     * @param userAgent User-Agent value
     * @return value without a feature token
     */
    public static String removeFeatureToken(String userAgent) {
        return FEATURE_COMMENT
                .matcher(safeHeaderValue(userAgent))
                .replaceAll("")
                .trim();
    }

    /**
     * Applies the live token only for an approved HTTPS destination and strips it otherwise.
     *
     * @param userAgent current User-Agent value
     * @param requestUri actual request URI
     * @param approvedOriginSuffixes reviewed DNS suffixes
     * @param registry feature registry
     * @return destination-safe User-Agent value
     */
    public static String stampFeatureToken(
            String userAgent,
            URI requestUri,
            Collection<String> approvedOriginSuffixes,
            FeatureUsageRegistry registry) {
        if (!isApprovedHttpsOrigin(requestUri, approvedOriginSuffixes)) {
            return removeFeatureToken(userAgent);
        }
        return applyFeatureToken(userAgent, registry);
    }

    /**
     * Returns whether a URI is HTTPS and its normalized host matches an approved DNS suffix.
     *
     * @param requestUri actual request URI
     * @param approvedOriginSuffixes reviewed DNS suffixes
     * @return {@code true} only for an exact or subdomain suffix match
     */
    public static boolean isApprovedHttpsOrigin(URI requestUri, Collection<String> approvedOriginSuffixes) {
        URI uri = Objects.requireNonNull(requestUri, "requestUri");
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            return false;
        }
        String host = trimTrailingDot(uri.getHost().toLowerCase(Locale.ROOT));
        for (String rawSuffix : Objects.requireNonNull(approvedOriginSuffixes, "approvedOriginSuffixes")) {
            if (rawSuffix == null || rawSuffix.isBlank()) {
                continue;
            }
            String suffix = normalizeSuffix(rawSuffix);
            if (host.equals(suffix) || host.endsWith("." + suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether the complete framework User-Agent contribution is enabled.
     *
     * @return {@code false} only when the opt-out environment variable is {@code true} or {@code 1}
     */
    public static boolean isTelemetryEnabled() {
        return !isEnvironmentFlagEnabled(USER_AGENT_TELEMETRY_DISABLED_ENV_VAR);
    }

    static boolean isEnvironmentFlagEnabled(String name) {
        String value = System.getenv(Objects.requireNonNull(name, "name"));
        return value != null && ("true".equalsIgnoreCase(value) || "1".equals(value));
    }

    static void clearUserAgentPrefixesForTesting() {
        USER_AGENT_PREFIXES.clear();
        HOSTED_ENVIRONMENT_DETECTED.set(false);
    }

    private static void detectHostedEnvironment() {
        if (!HOSTED_ENVIRONMENT_DETECTED.compareAndSet(false, true)) {
            return;
        }
        String hostedEnvironment = System.getenv(FOUNDRY_HOSTING_ENV_VAR);
        if (hostedEnvironment != null && !hostedEnvironment.isBlank()) {
            addUserAgentPrefix(HOSTED_USER_AGENT_PREFIX);
        }
    }

    private static String resolveFrameworkVersion() {
        ArrayList<String> candidates = new ArrayList<>();
        candidates.add(System.getProperty(FRAMEWORK_VERSION_PROPERTY));
        candidates.add(UserAgentUtil.class.getPackage().getImplementationVersion());
        for (String candidate : candidates) {
            if (candidate != null && PRODUCT_COMPONENT.matcher(candidate).matches()) {
                return candidate;
            }
        }
        return DEVELOPMENT_VERSION;
    }

    private static String requireProductComponent(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!PRODUCT_COMPONENT.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a valid User-Agent product component.");
        }
        return value;
    }

    private static String safeHeaderValue(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException("User-Agent values must not contain control characters.");
            }
        }
        return value.trim();
    }

    private static String normalizeSuffix(String suffix) {
        String normalized = trimTrailingDot(suffix.trim().toLowerCase(Locale.ROOT));
        while (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static String trimTrailingDot(String value) {
        String result = value;
        while (result.endsWith(".")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
