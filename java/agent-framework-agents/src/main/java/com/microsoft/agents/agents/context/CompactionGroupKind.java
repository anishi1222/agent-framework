// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.context;

/** Classifies one atomic history group. */
public enum CompactionGroupKind {
    /** System or developer instructions, which compaction always preserves. */
    INSTRUCTION,
    /** A user-authored group. */
    USER,
    /** An assistant-authored text or summary group. */
    ASSISTANT,
    /** A function-call/result group. */
    TOOL
}
