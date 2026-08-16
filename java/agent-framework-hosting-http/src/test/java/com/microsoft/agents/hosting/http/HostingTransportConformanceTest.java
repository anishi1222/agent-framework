// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.agents.conformance.BehaviorFixture;
import com.microsoft.agents.conformance.ConformanceFixtureLoader;
import com.microsoft.agents.conformance.ConformanceValue;
import com.microsoft.agents.hosting.HostingJsonCodec;
import java.util.List;
import org.junit.jupiter.api.Test;

class HostingTransportConformanceTest {
    @Test
    void productionTransport_shouldBindGenericHostingConformanceContract() {
        BehaviorFixture fixture =
                (BehaviorFixture) new ConformanceFixtureLoader().loadDefault().requireCase("JCF-HOSTING-TRANSPORT-001");

        assertThat(text(fixture.input(), "wireVersion")).isEqualTo(HostingJsonCodec.WIRE_VERSION);
        assertThat(text(fixture.input(), "basePath")).isEqualTo(HostingHttpHandler.BASE_PATH);
        assertThat(text(fixture.input(), "webSocketPath")).isEqualTo(HostingHttpHandler.WEBSOCKET_PATH);
        assertThat(text(fixture.input(), "webSocketSubprotocol")).isEqualTo(HostingWebSocketProtocol.SUBPROTOCOL);
        assertThat(texts(fixture.input(), "collections")).containsExactly("agents", "workflows", "orchestrations");
        assertThat(texts(fixture.input(), "webSocketClientFrames"))
                .containsExactly("start", "resume", "cancel", "demand", "close");
        assertThat(bool(fixture.expected(), "lastEventIdReplayClaimed")).isFalse();
        assertThat(bool(fixture.expected(), "crossProcessResumeClaimed")).isFalse();
        assertThat(bool(fixture.expected(), "directTlsTerminationClaimed")).isFalse();
        assertThat(HostingHttpServerOptions.builder().build().corsEnabled()).isFalse();
    }

    private static String text(ConformanceValue.ObjectValue object, String name) {
        return ((ConformanceValue.StringValue) object.require(name)).value();
    }

    private static List<String> texts(ConformanceValue.ObjectValue object, String name) {
        return ((ConformanceValue.ArrayValue) object.require(name))
                .values().stream()
                        .map(ConformanceValue.StringValue.class::cast)
                        .map(ConformanceValue.StringValue::value)
                        .toList();
    }

    private static boolean bool(ConformanceValue.ObjectValue object, String name) {
        return ((ConformanceValue.BooleanValue) object.require(name)).value();
    }
}
