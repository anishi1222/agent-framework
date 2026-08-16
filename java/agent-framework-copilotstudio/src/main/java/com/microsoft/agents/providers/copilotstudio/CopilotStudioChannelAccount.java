// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.copilotstudio;

/**
 * Identifies an Activity protocol participant.
 *
 * @param id optional participant identity
 * @param name optional display name
 * @param role optional participant role
 */
public record CopilotStudioChannelAccount(String id, String name, String role) {}
