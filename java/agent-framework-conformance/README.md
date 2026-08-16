# Java conformance test support

`agent-framework-conformance` is a non-published Gradle module. It contains language-neutral
behavior fixtures and reusable Java test-support APIs; it is never a runtime dependency or a BOM
constraint.

## Fixture contract

- `src/main/resources/conformance/manifest-v1.json` registers every case exactly once.
- Concrete fixtures live under `conformance/v1/<area>/` and carry `schemaVersion`, `caseId`,
  `kind`, and non-empty `expected` data.
- Successful tool-loop fixtures require one correlated terminal `functionResult` for every actionable
  call. Rejected approvals use an explicit `rejected` result outcome and never count as tool-body
  invocations.
- Stable suite prefixes use `JCF-<AREA>` and concrete cases add a three-digit suffix, such as
  `JCF-TOOLS-007`.
- `ConformanceFixtureLoader` uses explicit kind dispatch and rejects duplicate keys, unknown fields,
  unknown kinds, unsupported versions, and trailing JSON. Jackson default typing is not enabled.
- Main-manifest fixture resources must be unique, normalized `conformance/v1/` JSON paths. Absolute
  paths, backslashes, empty segments, dot or dot-dot segments, and paths outside that root are
  rejected before a custom resolver is called.
- Session fixtures describe observable behavior plus the Java version 1 state envelope. They do not
  claim compatibility with .NET or Python session/checkpoint files.
- Response usage aggregation sequentially folds updates from an empty aggregate with Python
  `add_usage_details`: missing or `null` values contribute zero, integral JSON numbers are summed
  without narrowing, and a key is omitted for a fold when either side is non-integral and non-null.
  A later fold can reintroduce an omitted key. Nested maps are values, not recursively aggregated.
  The fixture schema intentionally remains stricter than Python by rejecting negative numeric usage
  values, including nested values.
- `conformance/rejections/manifest-v1.json` indexes raw session/checkpoint parser-rejection inputs.
  `SerializationRejectionCorpusLoader` validates only this safe metadata; raw invalid JSON is opened
  through the bounded `SerializationRejectionCorpus.readRaw` API and is never parsed by the corpus
  loader. Every resource uses an explicit named `SerializationLimits` profile. Production-reader
  test adapters implement `SerializationReaderAdapter` and return a typed `SerializationReadResult`;
  `SerializationRejectionAssertions.assertConforms` requires a valid session and valid checkpoint
  positive control to be accepted, then requires every malformed case to be rejected with its
  declared `SerializationRejectionReason`.
- Production module bindings currently cover `JCF-CORE-001` through `JCF-CORE-007`,
  `JCF-TOOLS-001` through `JCF-TOOLS-014`, `JCF-AGENTS-001` through `JCF-AGENTS-006`, and
  `JCF-CONTEXT-001`, `JCF-SKILLS-001` through `JCF-SKILLS-003`,
  `JCF-SESSIONS-001` through `JCF-SESSIONS-003`, and
  `JCF-WORKFLOWS-001` through `JCF-WORKFLOWS-005`. Session and checkpoint create-only operations use
  expected revision `-1`; positive revisions are opaque compare-and-set versions.

To add a case, add the fixture and manifest entry, then put the exact case ID on its
`initial-scope` matrix row when applicable. `ConformanceManifestCoverageTest` fails for unregistered
fixtures, missing manifest resources, or matrix/manifest drift.

## Validation

Run from `java/`:

```bash
./gradlew :agent-framework-conformance:test
./gradlew clean build checkArchitecture
./gradlew publishToTestRepository
```

The publication command must not create an `agent-framework-conformance` artifact.
