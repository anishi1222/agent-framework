// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FeatureStagesTest {
    @AfterEach
    void clearWarnings() {
        FeatureStages.clearWarningsForTesting();
    }

    @Test
    void annotations_shouldExposeRuntimeRetentionAndSupportedTargets() {
        // Arrange
        EnumSet<ElementType> expectedTargets = EnumSet.of(
                ElementType.TYPE,
                ElementType.METHOD,
                ElementType.CONSTRUCTOR,
                ElementType.FIELD,
                ElementType.PACKAGE,
                ElementType.ANNOTATION_TYPE,
                ElementType.RECORD_COMPONENT);

        // Act and assert
        for (Class<?> annotation : List.of(Experimental.class, ReleaseCandidate.class)) {
            assertThat(annotation.getAnnotation(Retention.class).value()).isEqualTo(RetentionPolicy.RUNTIME);
            assertThat(Arrays.asList(annotation.getAnnotation(Target.class).value()))
                    .containsExactlyInAnyOrderElementsOf(expectedTargets);
        }
    }

    @Test
    void describe_shouldPreserveStageFeatureIdAndInheritedTypeMetadata() throws Exception {
        // Act
        FeatureStageMetadata experimental =
                FeatureStages.describe(ExperimentalChild.class).orElseThrow();
        FeatureStageMetadata releaseCandidate = FeatureStages.describe(
                        StagedMethods.class.getDeclaredMethod("releaseCandidate"))
                .orElseThrow();

        // Assert
        assertThat(experimental).isEqualTo(new FeatureStageMetadata(FeatureStage.EXPERIMENTAL, "EXPERIMENTAL_FEATURE"));
        assertThat(releaseCandidate).isEqualTo(new FeatureStageMetadata(FeatureStage.RELEASE_CANDIDATE, "RC_FEATURE"));
        assertThat(FeatureStages.describe(String.class)).isEmpty();
    }

    @Test
    void describe_shouldRejectConflictingStages() {
        assertThatThrownBy(() -> FeatureStages.describe(ConflictingStages.class))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("both experimental and release-candidate");
    }

    @Test
    void warnOnce_shouldDeduplicateByStageAndFeatureId() throws Exception {
        // Arrange
        List<String> warnings = new ArrayList<>();

        // Act
        boolean first = FeatureStages.warnOnce(ExperimentalBase.class, warnings::add);
        boolean duplicate = FeatureStages.warnOnce(ExperimentalSibling.class, warnings::add);
        boolean otherStage =
                FeatureStages.warnOnce(StagedMethods.class.getDeclaredMethod("releaseCandidate"), warnings::add);

        // Assert
        assertThat(first).isTrue();
        assertThat(duplicate).isFalse();
        assertThat(otherStage).isTrue();
        assertThat(warnings)
                .hasSize(2)
                .allSatisfy(warning -> assertThat(warning).contains("["))
                .anySatisfy(warning -> assertThat(warning).contains("[EXPERIMENTAL_FEATURE]"))
                .anySatisfy(warning -> assertThat(warning).contains("[RC_FEATURE]"));
    }

    @Test
    void experimentalFeature_shouldMirrorCurrentPythonStageInventory() {
        assertThat(Arrays.stream(ExperimentalFeature.values()).map(ExperimentalFeature::id))
                .containsExactly(
                        "DECLARATIVE_AGENTS",
                        "EVALS",
                        "FILE_HISTORY",
                        "FIDES",
                        "FOUNDRY_TOOLS",
                        "FOUNDRY_PREVIEW_TOOLS",
                        "FUNCTIONAL_WORKFLOWS",
                        "HARNESS",
                        "MCP_LONG_RUNNING_TASKS",
                        "MCP_SKILLS",
                        "PROGRESSIVE_TOOLS",
                        "SESSION_STORE",
                        "TO_PROMPT_AGENT");
        assertThat(ReleaseCandidateFeature.values()).isEmpty();
    }

    @Experimental("EXPERIMENTAL_FEATURE")
    private static class ExperimentalBase {}

    private static final class ExperimentalChild extends ExperimentalBase {}

    @Experimental("EXPERIMENTAL_FEATURE")
    private static final class ExperimentalSibling {}

    private static final class StagedMethods {
        @ReleaseCandidate("RC_FEATURE")
        private void releaseCandidate() {}
    }

    @Experimental("CONFLICT")
    @ReleaseCandidate("CONFLICT")
    private static final class ConflictingStages {}
}
