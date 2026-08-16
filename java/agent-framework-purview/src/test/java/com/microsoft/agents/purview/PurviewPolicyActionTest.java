// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.purview;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PurviewPolicyActionTest {
    @Test
    void blocksAccess_shouldMatchDotNetAndPythonIndependentOrSemantics() {
        assertThat(new PurviewPolicyAction("blockAccess", null).blocksAccess()).isTrue();
        assertThat(new PurviewPolicyAction("warn", "block").blocksAccess()).isTrue();
        assertThat(new PurviewPolicyAction("restrictAccessAction", "block").blocksAccess())
                .isTrue();
        assertThat(new PurviewPolicyAction(null, "block").blocksAccess()).isTrue();
        assertThat(new PurviewPolicyAction("restrictAccessAction", "audit").blocksAccess())
                .isFalse();
        assertThat(new PurviewPolicyAction("allow", null).blocksAccess()).isFalse();
        assertThat(new PurviewPolicyAction("warn", null).blocksAccess()).isFalse();
        assertThat(new PurviewPolicyAction("futureAction", "futureRestriction").blocksAccess())
                .isFalse();
    }
}
