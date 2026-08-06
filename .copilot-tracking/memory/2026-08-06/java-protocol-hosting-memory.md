<!-- markdownlint-disable-file -->
# Memory: java-protocol-hosting

**Created:** 2026-08-06T21:17:10+09:00 | **Last Updated:** 2026-08-06T21:17:10+09:00

## Task Overview

Pause and preserve the Java implementation program before a planned network disconnection. On a later request such as `再開して`, resume directly at **java-protocol-hosting** without repeating completed implementation, review, cleanup, or validation work.

Success criteria for the next task:

1. Implement protocol support in this order:
   1. Official MCP Java SDK client/tool/hosting support first.
   2. A2A support.
   3. AG-UI support.
   4. HTTP, SSE, and WebSocket hosting.
2. Integrate with the existing Java Agent Framework design and tests.
3. Preserve all existing uncommitted work; do not reset, clean, stash, or overwrite it.

## Current State

### Completed and independently reviewed

* Java context compaction and OpenTelemetry work.
* Sequential orchestration.
* Concurrent orchestration.
* Handoff orchestration.
* Group Chat orchestration.
* Magentic orchestration.
* Orchestration resume APIs and follow-up review fixes.
* Cleanup and diff check after orchestration review.

These areas are complete. Do **not** redo them when resuming unless a new failure directly implicates them.

### Validation

* Full Java validation was last reported as **590 tests passing**.
* This checkpoint did not rerun tests because the user requested a pause before network disconnection.

### Protocol hosting

* **No protocol-hosting implementation has begun.**
* The next work item is `java-protocol-hosting`.
* Begin with the official MCP Java SDK and implement client/tool/hosting capabilities before moving to A2A, AG-UI, and transport hosting.

### Repository state at checkpoint

* Repository: `/Users/logico_jp/sources/repos/agent-framework`
* Branch: `main`
* Baseline HEAD: `7bf14644c8824ab90157f3beea7977cf9c1f5d37`
* Baseline subject: `Save uncommitted changes`
* All implementation changes remain **uncommitted**.
* `git status --porcelain` reported 270 paths: 52 modified, 1 deleted, and 217 untracked.
* No staged diff was reported.
* Tracked diff summary: 53 files changed, 2,450 insertions, 397 deletions. This excludes untracked files.
* Existing work includes extensive new Java orchestration and context sources/tests under:
  * `java/agent-framework-agents/src/main/java/com/microsoft/agents/agents/context/`
  * `java/agent-framework-orchestrations/src/main/java/com/microsoft/agents/orchestrations/`
  * `java/agent-framework-orchestrations/src/test/`
  * `java/agent-framework-orchestrations/README.md`
* The working tree is intentionally dirty. Treat every existing change as user work.

## Important Discoveries

* **Decision:** Protocol work starts with the official MCP Java SDK client/tool/hosting implementation — this is the explicit priority and foundation for subsequent protocol work.
* **Decision:** A2A follows MCP, then AG-UI, then HTTP/SSE/WebSocket hosting.
* **Decision:** Completed orchestration/context/telemetry work is the accepted baseline — it was independently reviewed and should not be re-investigated merely to regain context.
* **Decision:** Preserve the dirty working tree exactly — all repository changes are intentionally uncommitted.
* **Failed Approaches:** None recorded for protocol hosting because implementation has not started.

## Next Steps

1. On resume, inspect only the Java module/build boundaries and existing feature-parity/API documentation needed to place protocol support; do not redo completed reviews.
2. Confirm the current working tree still contains the uncommitted baseline and avoid destructive Git operations.
3. Design and implement official MCP Java SDK integration:
   * SDK dependency/module placement.
   * MCP client integration.
   * Tool discovery/invocation adaptation into Agent Framework tools.
   * MCP server/hosting integration.
   * Focused unit/integration tests and documentation.
4. Run focused MCP tests, then the full Java validation suite.
5. Implement and validate A2A support.
6. Implement and validate AG-UI support.
7. Implement and validate HTTP, SSE, and WebSocket hosting.
8. Independently review each completed protocol slice and perform a final cleanup/diff check.

## Context to Preserve

* **User preference:** A later `再開して` means continue directly from `java-protocol-hosting`.
* **Constraint:** Do not modify or discard completed source work while restoring context.
* **Constraint:** Do not claim the 590-test result is newly verified; it is the last reported validation result.
* **Source:** User checkpoint request — authoritative completion state, ordering, and validation result.
* **Source:** `git status --porcelain` at checkpoint — 270 dirty paths (52 modified, 1 deleted, 217 untracked).
* **Source:** `git diff --stat` at checkpoint — 53 tracked files changed, 2,450 insertions, 397 deletions.
* **Agents:** Independent reviewers were used for completed work, but no specific custom-agent identity was provided in this checkpoint.
* **Questions:** Exact Java module/package boundaries for MCP, A2A, AG-UI, and hosting still need to be determined from the repository when implementation begins.
