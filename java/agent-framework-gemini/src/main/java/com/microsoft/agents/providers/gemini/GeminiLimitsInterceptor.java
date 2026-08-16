// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.providers.gemini;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import okio.Source;

final class GeminiLimitsInterceptor implements Interceptor {
    private final GeminiChatClientOptions options;

    GeminiLimitsInterceptor(GeminiChatClientOptions options) {
        this.options = options;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        validateUrl(request.url().uri());
        if (request.body() != null) {
            long length = request.body().contentLength();
            if (length > options.maxRequestBytes()) {
                throw new IOException("Gemini request exceeded the configured byte limit.");
            }
        }
        Response response = chain.proceed(request);
        if (response.isRedirect()) {
            response.close();
            throw new IOException("Gemini redirects are disabled.");
        }
        ResponseBody body = response.body();
        if (body == null) {
            return response;
        }
        if (body.contentLength() > options.maxResponseBytes()) {
            response.close();
            throw new IOException("Gemini response exceeded the configured byte limit.");
        }
        boolean eventStream = body.contentType() != null
                && body.contentType().toString().toLowerCase(Locale.ROOT).contains("text/event-stream");
        ResponseBody bounded = new BoundedBody(body, options.maxResponseBytes(), options.maxEventBytes(), eventStream);
        return response.newBuilder().body(bounded).build();
    }

    private void validateUrl(URI uri) throws IOException {
        URI endpoint = options.endpoint();
        if (!Objects.equals(uri.getScheme(), endpoint.getScheme())
                || !Objects.equals(uri.getHost(), endpoint.getHost())
                || uri.getPort() != endpoint.getPort()
                || uri.getRawUserInfo() != null
                || !withinEndpointPath(uri.getRawPath(), endpoint.getRawPath())) {
            throw new IOException("Gemini request escaped the configured endpoint.");
        }
    }

    private static boolean withinEndpointPath(String requestPath, String endpointPath) {
        String base = endpointPath == null || endpointPath.isEmpty() ? "/" : endpointPath;
        if (!base.endsWith("/")) {
            base += "/";
        }
        return requestPath != null && ("/".equals(base) || requestPath.startsWith(base));
    }

    private static final class BoundedBody extends ResponseBody {
        private final ResponseBody delegate;

        private final BufferedSource source;

        private BoundedBody(ResponseBody delegate, long maximum, long eventMaximum, boolean eventStream) {
            this.delegate = delegate;
            Source bounded = new BoundedSource(delegate.source(), maximum, eventMaximum, eventStream);
            source = Okio.buffer(bounded);
        }

        @Override
        public MediaType contentType() {
            return delegate.contentType();
        }

        @Override
        public long contentLength() {
            return delegate.contentLength();
        }

        @Override
        public BufferedSource source() {
            return source;
        }
    }

    private static final class BoundedSource extends ForwardingSource {
        private final long maximum;

        private final long eventMaximum;

        private final boolean eventStream;

        private long total;

        private long eventBytes;

        private int previous = -1;

        private int beforePrevious = -1;

        private int threeBack = -1;

        private BoundedSource(Source delegate, long maximum, long eventMaximum, boolean eventStream) {
            super(delegate);
            this.maximum = maximum;
            this.eventMaximum = eventMaximum;
            this.eventStream = eventStream;
        }

        @Override
        public long read(Buffer sink, long byteCount) throws IOException {
            long before = sink.size();
            long read = super.read(sink, byteCount);
            if (read <= 0) {
                return read;
            }
            if (read > maximum - total) {
                throw new IOException("Gemini response exceeded the configured byte limit.");
            }
            total += read;
            if (eventStream) {
                Buffer copy = new Buffer();
                sink.copyTo(copy, before, read);
                byte[] bytes = copy.readByteArray();
                for (byte value : bytes) {
                    int current = value & 0xff;
                    eventBytes++;
                    if (eventBytes > eventMaximum) {
                        throw new IOException("Gemini SSE event exceeded the configured byte limit.");
                    }
                    if (previous == '\n' && current == '\n'
                            || threeBack == '\r' && beforePrevious == '\n' && previous == '\r' && current == '\n') {
                        eventBytes = 0;
                    }
                    threeBack = beforePrevious;
                    beforePrevious = previous;
                    previous = current;
                }
            }
            return read;
        }
    }
}
