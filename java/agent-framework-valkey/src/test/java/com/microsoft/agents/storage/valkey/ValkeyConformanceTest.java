// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.valkey;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.conformance.BehaviorFixture;
import com.microsoft.agents.conformance.ConformanceFixtureLoader;
import com.microsoft.agents.conformance.ConformanceValue;
import com.microsoft.agents.conformance.FixtureKind;
import org.junit.jupiter.api.Test;

class ValkeyConformanceTest {
    @Test
    void productionModule_shouldBindJcfSessions005() {
        // Arrange
        BehaviorFixture fixture =
                (BehaviorFixture) new ConformanceFixtureLoader().loadDefault().requireCase("JCF-SESSIONS-005");

        // Act
        ConformanceValue sdk = fixture.input().require("sdk");
        ConformanceValue appendAtomic = fixture.expected().require("appendAtomic");
        ConformanceValue corruptRejected = fixture.expected().require("corruptEntriesRejected");

        // Assert
        assertThat(fixture.kind()).isEqualTo(FixtureKind.CONTRACT);
        assertThat(sdk).isEqualTo(new ConformanceValue.StringValue("io.valkey:valkey-glide:2.5.1"));
        assertThat(appendAtomic).isEqualTo(new ConformanceValue.BooleanValue(true));
        assertThat(corruptRejected).isEqualTo(new ConformanceValue.BooleanValue(true));
        assertThat(ValkeyScript.APPEND.source())
                .contains("redis.call('HGET'", "redis.call('LTRIM'", "redis.call('HDEL'");
    }
}
