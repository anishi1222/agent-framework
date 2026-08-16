// Copyright (c) Microsoft. All rights reserved.

/**
 * Provides approval-gated bounded CodeAct execution over the framework shell runtime.
 *
 * <p>The package intentionally does not provide an unrestricted local-execution mode. Approval,
 * caller policy, output and step bounds, timeout, cancellation, and workspace anchoring are always
 * applied. These controls are defense in depth and do not replace an external process or container
 * security boundary for untrusted code.
 */
package com.microsoft.agents.codeact;
