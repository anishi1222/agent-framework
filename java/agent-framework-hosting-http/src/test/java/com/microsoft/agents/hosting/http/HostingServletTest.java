// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.hosting.HostingApprovalRequest;
import com.microsoft.agents.hosting.HostingContinuationDescriptor;
import com.microsoft.agents.hosting.HostingContinuationType;
import com.microsoft.agents.hosting.HostingDispatcher;
import com.microsoft.agents.hosting.HostingJsonCodec;
import com.microsoft.agents.hosting.HostingLimits;
import com.microsoft.agents.hosting.HostingOutcome;
import com.microsoft.agents.hosting.HostingRun;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HostingServletTest {
    private static final int RACE_ATTEMPTS = 100;

    @Test
    void finiteWrite_shouldDiscardExactlyOnceAcrossRuntimeFailureTimeoutAndErrorRace() throws Exception {
        HostingHttpHandler handler = mock(HostingHttpHandler.class);
        HostingDispatcher dispatcher = mock(HostingDispatcher.class);
        when(handler.options()).thenReturn(HostingHttpServerOptions.builder().build());
        HostingServlet servlet = new HostingServlet(handler);
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> uncaught.compareAndSet(null, failure));
        try {
            for (int attempt = 0; attempt < RACE_ATTEMPTS; attempt++) {
                HostingHttpResponse result = HostingHttpResponse.finiteOutcome(
                        200,
                        Map.of("Content-Type", List.of("application/json")),
                        "{}".getBytes(StandardCharsets.UTF_8),
                        approvalOutcome(attempt),
                        dispatcher::discardUndeliveredOutcome);
                when(handler.handleAsync(any())).thenReturn(CompletableFuture.completedFuture(result));
                BlockingFailureOutputStream output = new BlockingFailureOutputStream(false, true);
                ServletInvocation invocation = invocation(output, true);

                servlet.service(invocation.request(), invocation.response());
                assertThat(output.failureReached.await(5, TimeUnit.SECONDS)).isTrue();
                raceTimeoutAndError(invocation);
                output.releaseFailure.countDown();

                assertThat(invocation.completed().await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(servlet.activeExchangeCount()).isZero();
                verify(invocation.async(), times(1)).complete();
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }

        assertThat(uncaught.get()).isNull();
        verify(dispatcher, times(RACE_ATTEMPTS)).discardUndeliveredOutcome(any());
    }

    @Test
    void failureWrite_shouldFinishExactlyOnceAcrossCompletedContextTimeoutAndErrorRace() throws Exception {
        HostingHttpHandler handler = mock(HostingHttpHandler.class);
        when(handler.options()).thenReturn(HostingHttpServerOptions.builder().build());
        when(handler.encodeError(any())).thenReturn("{}".getBytes(StandardCharsets.UTF_8));
        when(handler.handleAsync(any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("failed")));
        HostingServlet servlet = new HostingServlet(handler);
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> uncaught.compareAndSet(null, failure));
        try {
            for (int attempt = 0; attempt < RACE_ATTEMPTS; attempt++) {
                CountDownLatch writeReached = new CountDownLatch(1);
                CountDownLatch releaseWrite = new CountDownLatch(1);
                ServletInvocation invocation = invocation(new NoOpServletOutputStream(), true);
                doAnswer(ignored -> {
                            writeReached.countDown();
                            await(releaseWrite);
                            throw new IllegalStateException("Async response is already complete.");
                        })
                        .when(invocation.response())
                        .setStatus(anyInt());

                servlet.service(invocation.request(), invocation.response());
                assertThat(writeReached.await(5, TimeUnit.SECONDS)).isTrue();
                raceTimeoutAndError(invocation);
                releaseWrite.countDown();

                assertThat(invocation.completed().await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(servlet.activeExchangeCount()).isZero();
                verify(invocation.async(), times(1)).complete();
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }

        assertThat(uncaught.get()).isNull();
    }

    @Test
    void finiteWrite_shouldNotDiscardAfterConfirmedDelivery() throws Exception {
        HostingHttpHandler handler = mock(HostingHttpHandler.class);
        HostingDispatcher dispatcher = mock(HostingDispatcher.class);
        when(handler.options()).thenReturn(HostingHttpServerOptions.builder().build());
        HostingHttpResponse result = HostingHttpResponse.finiteOutcome(
                200,
                Map.of("Content-Type", List.of("application/json")),
                "{}".getBytes(StandardCharsets.UTF_8),
                approvalOutcome(1),
                dispatcher::discardUndeliveredOutcome);
        when(handler.handleAsync(any())).thenReturn(CompletableFuture.completedFuture(result));
        HostingServlet servlet = new HostingServlet(handler);
        ServletInvocation invocation = invocation(new NoOpServletOutputStream(), false);

        servlet.service(invocation.request(), invocation.response());

        assertThat(invocation.completed().await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(servlet.activeExchangeCount()).isZero();
        verify(invocation.async(), times(1)).complete();
        verify(dispatcher, times(0)).discardUndeliveredOutcome(any());
    }

    @Test
    void sseWrite_shouldDiscardTerminalOutcomeExactlyOnceAcrossDisconnectRace() throws Exception {
        HostingHttpHandler handler = mock(HostingHttpHandler.class);
        HostingDispatcher dispatcher = mock(HostingDispatcher.class);
        HostingLimits limits = HostingLimits.defaults();
        when(handler.options())
                .thenReturn(HostingHttpServerOptions.builder().limits(limits).build());
        when(handler.codec()).thenReturn(new HostingJsonCodec(limits));
        when(handler.encodeOutcome(any(), any())).thenReturn("{}".getBytes(StandardCharsets.UTF_8));
        HostingServlet servlet = new HostingServlet(handler);
        for (int attempt = 0; attempt < RACE_ATTEMPTS; attempt++) {
            CompletableFuture<HostingOutcome> terminal = CompletableFuture.completedFuture(approvalOutcome(attempt));
            HostingRun run =
                    new HostingRun("run-" + attempt, completingPublisher(), terminal, new DefaultRunCancellation());
            HostingHttpResponse result =
                    HostingHttpResponse.trackedSse(Map.of(), run, dispatcher::discardUndeliveredOutcome);
            when(handler.handleAsync(any())).thenReturn(CompletableFuture.completedFuture(result));
            BlockingFailureOutputStream output = new BlockingFailureOutputStream(true, false);
            ServletInvocation invocation = invocation(output, true);

            servlet.service(invocation.request(), invocation.response());
            assertThat(output.failureReached.await(5, TimeUnit.SECONDS)).isTrue();
            raceTimeoutAndError(invocation);
            output.releaseFailure.countDown();

            assertThat(invocation.completed().await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(servlet.activeExchangeCount()).isZero();
            verify(invocation.async(), times(1)).complete();
        }

        verify(dispatcher, times(RACE_ATTEMPTS)).discardUndeliveredOutcome(any());
    }

    private static ServletInvocation invocation(ServletOutputStream output, boolean completeThrows) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        AsyncContext async = mock(AsyncContext.class);
        AtomicReference<AsyncListener> listener = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        when(request.getContentLengthLong()).thenReturn(0L);
        try {
            when(request.getInputStream()).thenReturn(new EmptyServletInputStream());
            when(response.getOutputStream()).thenReturn(output);
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
        when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/v1");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getRemotePort()).thenReturn(12345);
        when(request.startAsync()).thenReturn(async);
        doAnswer(invocation -> {
                    listener.set(invocation.getArgument(0));
                    return null;
                })
                .when(async)
                .addListener(any());
        doAnswer(ignored -> {
                    completed.countDown();
                    if (completeThrows) {
                        throw new IllegalStateException("Async context is already complete.");
                    }
                    return null;
                })
                .when(async)
                .complete();
        return new ServletInvocation(request, response, async, listener, completed);
    }

    private static void raceTimeoutAndError(ServletInvocation invocation) throws InterruptedException {
        AsyncListener listener = invocation.listener().get();
        assertThat(listener).isNotNull();
        Thread timeout = Thread.startVirtualThread(
                () -> invokeListener(() -> listener.onTimeout(new AsyncEvent(invocation.async()))));
        Thread error = Thread.startVirtualThread(() -> invokeListener(
                () -> listener.onError(new AsyncEvent(invocation.async(), new IOException("peer disconnected")))));
        timeout.join();
        error.join();
    }

    private static void invokeListener(ListenerInvocation invocation) {
        try {
            invocation.invoke();
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static HostingOutcome approvalOutcome(int attempt) {
        HostingApprovalRequest approval =
                new HostingApprovalRequest("approval-" + attempt, "write", StateValue.object(Map.of()));
        HostingContinuationDescriptor continuation = new HostingContinuationDescriptor(
                "continuation-" + attempt,
                HostingContinuationType.APPROVAL,
                Instant.parse("2030-01-01T00:00:00Z"),
                List.of(approval));
        return HostingOutcome.approvalRequired("run-" + attempt, continuation);
    }

    private static Flow.Publisher<com.microsoft.agents.hosting.HostingEvent> completingPublisher() {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private final AtomicBoolean complete = new AtomicBoolean();

            @Override
            public void request(long count) {
                if (count > 0 && complete.compareAndSet(false, true)) {
                    subscriber.onComplete();
                }
            }

            @Override
            public void cancel() {
                complete.set(true);
            }
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test race.");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private record ServletInvocation(
            HttpServletRequest request,
            HttpServletResponse response,
            AsyncContext async,
            AtomicReference<AsyncListener> listener,
            CountDownLatch completed) {}

    @FunctionalInterface
    private interface ListenerInvocation {
        void invoke() throws IOException;
    }

    private static final class EmptyServletInputStream extends ServletInputStream {
        @Override
        public int read() {
            return -1;
        }

        @Override
        public boolean isFinished() {
            return true;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {}
    }

    private static class NoOpServletOutputStream extends ServletOutputStream {
        @Override
        public void write(int value) throws IOException {}

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener listener) {}
    }

    private static final class BlockingFailureOutputStream extends NoOpServletOutputStream {
        private final boolean terminalOnly;

        private final boolean runtimeFailure;

        private final CountDownLatch failureReached = new CountDownLatch(1);

        private final CountDownLatch releaseFailure = new CountDownLatch(1);

        private BlockingFailureOutputStream(boolean terminalOnly, boolean runtimeFailure) {
            this.terminalOnly = terminalOnly;
            this.runtimeFailure = runtimeFailure;
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            String value = new String(data, offset, length, StandardCharsets.UTF_8);
            if (!terminalOnly || value.startsWith("event: terminal")) {
                failureReached.countDown();
                await(releaseFailure);
                if (runtimeFailure) {
                    throw new IllegalStateException("Async response is already complete.");
                }
                throw new IOException("Peer disconnected.");
            }
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[] {(byte) value}, 0, 1);
        }
    }
}
