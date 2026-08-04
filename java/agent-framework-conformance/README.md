# Java conformance test support

`agent-framework-conformance` is a non-published Gradle module. It contains language-neutral
behavior fixtures and reusable Java test-support APIs; it is never a runtime dependency or a BOM
constraint.

## Fixture contract

- `src/main/resources/conformance/manifest-v1.json` registers every case exactly once.
- Concrete fixtures live under `conformance/v1/<area>/` and carry `schemaVersion`, `caseId`,
  `kind`, and non-empty `expected` data.
- Stable suite prefixes use `JCF-<AREA>` and concrete cases add a three-digit suffix, such as
  `JCF-TOOLS-007`.
- `ConformanceFixtureLoader` uses explicit kind dispatch and rejects duplicate keys, unknown fields,
  unknown kinds, unsupported versions, and trailing JSON. Jackson default typing is not enabled.
- Session fixtures describe observable behavior plus the Java version 1 state envelope. They do not
  claim compatibility with .NET or Python session/checkpoint files.

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
