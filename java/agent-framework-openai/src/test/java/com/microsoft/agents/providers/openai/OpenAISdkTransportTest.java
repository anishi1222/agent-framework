// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.microsoft.agents.core.ChatResponseUpdate;
import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.StateValue;
import com.openai.client.OpenAIClientAsync;
import com.openai.core.ObjectMappers;
import com.openai.core.http.HttpResponseFor;
import com.openai.core.http.StreamResponse;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.services.async.ResponseServiceAsync;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Spliterators;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class OpenAISdkTransportTest {
    @Test
    void rawStreaming_shouldPropagateSanitizedRequestIdAndCloseResourcesExactlyOnce() throws Exception {
        // Arrange
        Fixture fixture = fixture("req_safe-123", Stream.of(createdEvent(), textDeltaEvent(), completedEvent()), 8);

        // Act
        List<OpenAITransport.StreamEvent> events = collect(
                        fixture.transport.completeStreaming(request(), new DefaultRunCancellation()))
                .join();
        OpenAIResponseMapper.StreamMapper mapper = new OpenAIResponseMapper.StreamMapper();
        List<ChatResponseUpdate> updates =
                events.stream().flatMap(event -> mapper.map(event).stream()).toList();

        // Assert
        assertThat(((OpenAITransport.ResponseStarted) events.getFirst()).requestId())
                .isEqualTo("req_safe-123");
        assertThat(((OpenAITransport.ResponseCompleted) events.getLast())
                        .response()
                        .requestId())
                .isEqualTo("req_safe-123");
        assertThat(updates)
                .allSatisfy(update -> assertThat(update.metadata())
                        .containsEntry("openai.requestId", StateValue.string("req_safe-123")));
        verify(fixture.stream, times(1)).close();
        verify(fixture.raw, times(1)).close();
    }

    @Test
    void rawStreaming_shouldRedactCrLfRequestIdFromUpdatesAndFailures() throws Exception {
        // Arrange
        Fixture fixture = fixture(
                "req_safe\r\nAuthorization: secret", Stream.of(createdEvent(), textDeltaEvent(), completedEvent()), 8);

        // Act
        List<OpenAITransport.StreamEvent> events = collect(
                        fixture.transport.completeStreaming(request(), new DefaultRunCancellation()))
                .join();
        OpenAIResponseMapper.StreamMapper mapper = new OpenAIResponseMapper.StreamMapper();
        List<ChatResponseUpdate> updates =
                events.stream().flatMap(event -> mapper.map(event).stream()).toList();

        // Assert
        assertThat(((OpenAITransport.ResponseStarted) events.getFirst()).requestId())
                .isNull();
        assertThat(((OpenAITransport.ResponseCompleted) events.getLast())
                        .response()
                        .requestId())
                .isNull();
        assertThat(updates)
                .allSatisfy(update -> assertThat(update.metadata())
                        .doesNotContainKey("openai.requestId")
                        .doesNotContainValue(StateValue.string("secret")));
    }

    @Test
    void rawStreaming_shouldCloseResourcesOnStreamError() {
        // Arrange
        Fixture fixture = fixture("req_error", null, 8);
        when(fixture.stream.stream()).thenThrow(new IllegalStateException("Authorization: secret"));

        // Act
        Throwable failure = collectFailure(fixture.transport.completeStreaming(request(), new DefaultRunCancellation()))
                .join();

        // Assert
        assertThat(failure).isInstanceOf(OpenAIProviderException.class);
        OpenAIProviderException providerFailure = (OpenAIProviderException) failure;
        assertThat(providerFailure.requestId()).contains("req_error");
        assertThat(providerFailure.getMessage()).doesNotContain("Authorization", "secret");
        verify(fixture.stream, times(1)).close();
        verify(fixture.raw, times(1)).close();
    }

    @Test
    void rawStreaming_shouldCloseResourcesOnCancellationAndOverflowWithoutDoubleClose() throws Exception {
        // Arrange
        CountDownLatch iteratorEntered = new CountDownLatch(1);
        CountDownLatch releaseIterator = new CountDownLatch(1);
        @SuppressWarnings("unchecked")
        Iterator<ResponseStreamEvent> blockingIterator = mock(Iterator.class);
        when(blockingIterator.hasNext()).thenAnswer(invocation -> {
            iteratorEntered.countDown();
            releaseIterator.await(5, TimeUnit.SECONDS);
            return false;
        });
        Stream<ResponseStreamEvent> blockingStream =
                StreamSupport.stream(Spliterators.spliteratorUnknownSize(blockingIterator, 0), false);
        Fixture cancelled = fixture("req_cancel", blockingStream, 8);
        doAnswer(invocation -> {
                    releaseIterator.countDown();
                    return null;
                })
                .when(cancelled.stream)
                .close();
        AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        cancelled
                .transport
                .completeStreaming(request(), new DefaultRunCancellation())
                .subscribe(new CapturingSubscriber(subscription, true));
        assertThat(iteratorEntered.await(5, TimeUnit.SECONDS)).isTrue();

        // Act
        subscription.get().cancel();
        Fixture overflow = fixture("req_overflow", Stream.of(createdEvent(), textDeltaEvent(), completedEvent()), 1);
        Throwable overflowFailure = collectFailureWithoutDemand(
                        overflow.transport.completeStreaming(request(), new DefaultRunCancellation()))
                .join();

        // Assert
        verify(cancelled.stream, times(1)).close();
        verify(cancelled.raw, times(1)).close();
        assertThat(overflowFailure).isInstanceOf(OpenAIStreamingBufferOverflowException.class);
        verify(overflow.stream, times(1)).close();
        verify(overflow.raw, times(1)).close();
    }

    private static Fixture fixture(String requestId, Stream<ResponseStreamEvent> events, int maxBufferedUpdates) {
        OpenAIClientAsync client = mock(OpenAIClientAsync.class);
        ResponseServiceAsync responses = mock(ResponseServiceAsync.class);
        ResponseServiceAsync.WithRawResponse rawService = mock(ResponseServiceAsync.WithRawResponse.class);
        @SuppressWarnings("unchecked")
        HttpResponseFor<StreamResponse<ResponseStreamEvent>> raw = mock(HttpResponseFor.class);
        @SuppressWarnings("unchecked")
        StreamResponse<ResponseStreamEvent> stream = mock(StreamResponse.class);
        when(client.responses()).thenReturn(responses);
        when(responses.withRawResponse()).thenReturn(rawService);
        when(rawService.createStreaming(any(ResponseCreateParams.class)))
                .thenReturn(CompletableFuture.completedFuture(raw));
        when(raw.requestId()).thenReturn(Optional.ofNullable(requestId));
        when(raw.parse()).thenReturn(stream);
        if (events != null) {
            when(stream.stream()).thenReturn(events);
        }
        return new Fixture(new OpenAISdkTransport(client, maxBufferedUpdates), raw, stream);
    }

    private static OpenAITransport.Request request() {
        return new OpenAITransport.Request(
                "gpt-test",
                List.of(new OpenAITransport.MessageInput(
                        OpenAITransport.InputRole.USER, List.of(new OpenAITransport.TextInput("hello")))),
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                null,
                OpenAIResponseOptions.defaults());
    }

    private static ResponseStreamEvent createdEvent() throws Exception {
        return event("""
                {
                  "type": "response.created",
                  "sequence_number": 0,
                  "response": {
                    "id": "resp_stream",
                    "created_at": 1,
                    "metadata": {},
                    "model": "gpt-test",
                    "output": [],
                    "status": "in_progress"
                  }
                }
                """);
    }

    private static ResponseStreamEvent textDeltaEvent() throws Exception {
        return event("""
                {
                  "type": "response.output_text.delta",
                  "sequence_number": 1,
                  "item_id": "msg_stream",
                  "output_index": 0,
                  "content_index": 0,
                  "delta": "hello",
                  "logprobs": []
                }
                """);
    }

    private static ResponseStreamEvent completedEvent() throws Exception {
        return event("""
                {
                  "type": "response.completed",
                  "sequence_number": 2,
                  "response": {
                    "id": "resp_stream",
                    "created_at": 1,
                    "metadata": {},
                    "model": "gpt-test",
                    "output": [
                      {
                        "id": "msg_stream",
                        "type": "message",
                        "role": "assistant",
                        "status": "completed",
                        "content": [
                          {"type": "output_text", "text": "hello", "annotations": []}
                        ]
                      }
                    ],
                    "status": "completed"
                  }
                }
                """);
    }

    private static ResponseStreamEvent event(String json) throws Exception {
        return ObjectMappers.jsonMapper().readValue(json, ResponseStreamEvent.class);
    }

    private static CompletableFuture<List<OpenAITransport.StreamEvent>> collect(
            Flow.Publisher<OpenAITransport.StreamEvent> publisher) {
        ArrayList<OpenAITransport.StreamEvent> events = new ArrayList<>();
        CompletableFuture<List<OpenAITransport.StreamEvent>> result = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(OpenAITransport.StreamEvent item) {
                events.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                result.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                result.complete(List.copyOf(events));
            }
        });
        return result;
    }

    private static CompletableFuture<Throwable> collectFailure(Flow.Publisher<OpenAITransport.StreamEvent> publisher) {
        CompletableFuture<Throwable> result = new CompletableFuture<>();
        publisher.subscribe(new CapturingSubscriber(result, true));
        return result;
    }

    private static CompletableFuture<Throwable> collectFailureWithoutDemand(
            Flow.Publisher<OpenAITransport.StreamEvent> publisher) {
        CompletableFuture<Throwable> result = new CompletableFuture<>();
        publisher.subscribe(new CapturingSubscriber(result, false));
        return result;
    }

    private record Fixture(
            OpenAISdkTransport transport,
            HttpResponseFor<StreamResponse<ResponseStreamEvent>> raw,
            StreamResponse<ResponseStreamEvent> stream) {}

    private static final class CapturingSubscriber implements Flow.Subscriber<OpenAITransport.StreamEvent> {
        private final CompletableFuture<Throwable> failure;

        private final AtomicReference<Flow.Subscription> subscription;

        private final boolean request;

        private CapturingSubscriber(CompletableFuture<Throwable> failure, boolean request) {
            this.failure = failure;
            this.subscription = null;
            this.request = request;
        }

        private CapturingSubscriber(AtomicReference<Flow.Subscription> subscription, boolean request) {
            this.failure = null;
            this.subscription = subscription;
            this.request = request;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            if (subscription != null) {
                subscription.set(value);
            }
            if (request) {
                value.request(Long.MAX_VALUE);
            }
        }

        @Override
        public void onNext(OpenAITransport.StreamEvent item) {}

        @Override
        public void onError(Throwable throwable) {
            if (failure != null) {
                failure.complete(throwable);
            }
        }

        @Override
        public void onComplete() {
            if (failure != null) {
                failure.completeExceptionally(new AssertionError("Expected stream failure."));
            }
        }
    }
}
