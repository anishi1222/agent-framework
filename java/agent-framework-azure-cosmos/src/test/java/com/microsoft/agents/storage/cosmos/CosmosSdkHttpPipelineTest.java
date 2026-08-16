// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.cosmos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpMethod;
import com.azure.core.http.HttpPipeline;
import com.azure.core.http.HttpPipelineBuilder;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.util.BinaryData;
import com.azure.cosmos.CosmosAsyncClient;
import com.azure.cosmos.CosmosAsyncContainer;
import com.azure.cosmos.CosmosAsyncDatabase;
import com.azure.cosmos.models.PartitionKey;
import com.azure.cosmos.models.SqlParameter;
import com.azure.cosmos.models.SqlQuerySpec;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class CosmosSdkHttpPipelineTest {
    private static final HttpHeaderName PARTITION_KEY = HttpHeaderName.fromString("x-ms-documentdb-partitionkey");

    private static final HttpHeaderName IS_QUERY = HttpHeaderName.fromString("x-ms-documentdb-isquery");

    private static final HttpHeaderName CONTINUATION = HttpHeaderName.fromString("x-ms-continuation");

    @Test
    void strictPipeline_shouldUseSdkResourcePathsHeadersBodiesAndResponseSerializer() {
        // Arrange
        CosmosAsyncContainer container = realSdkContainer("db", "items");
        String containerLink = sdkContainerLink(container);
        CosmosSessionDocument document = new CosmosSessionDocument();
        document.id = "session-item";
        document.partitionKey = "tenant-partition";
        document.kind = "agent-session";
        document.schemaVersion = 1;
        document.revision = 1L;
        document.deleted = false;
        document.payload = "cGF5bG9hZA==";
        document.payloadDigest = "digest";
        CosmosNullOmittingItemSerializer serializer = new CosmosNullOmittingItemSerializer();
        Map<String, Object> itemBody = serializer.serialize(document);
        StrictHttpClient http = new StrictHttpClient();
        HttpPipeline pipeline = new HttpPipelineBuilder().httpClient(http).build();
        String itemPath = containerLink + "/docs/" + document.id;
        HttpRequest create = new HttpRequest(
                        HttpMethod.POST, "https://account.documents.azure.com" + containerLink + "/docs")
                .setHeader(PARTITION_KEY, new PartitionKey(document.partitionKey).toString())
                .setHeader(HttpHeaderName.IF_NONE_MATCH, "*")
                .setHeader(HttpHeaderName.CONTENT_TYPE, "application/json")
                .setBody(BinaryData.fromObject(itemBody));
        SqlQuerySpec query = new SqlQuerySpec(
                "SELECT * FROM c WHERE c.kind = @kind ORDER BY c.sequence ASC",
                List.of(new SqlParameter("@kind", "history-message")));
        String queryBody = sdkQueryJson(query);
        HttpRequest queryRequest = new HttpRequest(
                        HttpMethod.POST, "https://account.documents.azure.com" + containerLink + "/docs")
                .setHeader(PARTITION_KEY, new PartitionKey(document.partitionKey).toString())
                .setHeader(IS_QUERY, "True")
                .setHeader(CONTINUATION, "opaque-continuation")
                .setHeader(HttpHeaderName.CONTENT_TYPE, "application/query+json")
                .setBody(BinaryData.fromString(queryBody));

        // Act
        HttpResponse createResponse = pipeline.send(create).block();
        HttpResponse queryResponse = pipeline.send(queryRequest).block();
        @SuppressWarnings("unchecked")
        Map<String, Object> parsedBody = createResponse.getBodyAsBinaryData().toObject(Map.class);
        CosmosSessionDocument parsed = serializer.deserialize(parsedBody, CosmosSessionDocument.class);

        // Assert
        assertThat(container.getDatabase().getId()).isEqualTo("db");
        assertThat(container.getId()).isEqualTo("items");
        assertThat(containerLink).isEqualTo("/dbs/db/colls/items");
        assertThat(itemPath).isEqualTo("/dbs/db/colls/items/docs/session-item");
        assertThat(http.requests).hasSize(2);
        assertThat(http.requests.getFirst().getUrl().getPath()).isEqualTo("/dbs/db/colls/items/docs");
        assertThat(http.requests.getFirst().getHeaders().getValue(PARTITION_KEY))
                .isEqualTo(new PartitionKey("tenant-partition").toString());
        assertThat(http.requests.getFirst().getHeaders().getValue(HttpHeaderName.IF_NONE_MATCH))
                .isEqualTo("*");
        assertThat(http.requests.getFirst().getBodyAsBinaryData().toString())
                .contains("\"id\":\"session-item\"")
                .contains("\"partitionKey\":\"tenant-partition\"")
                .doesNotContain("\"ttl\"");
        assertThat(parsed.id).isEqualTo("session-item");
        assertThat(createResponse.getHeaderValue(HttpHeaderName.ETAG)).isEqualTo("etag-1");
        assertThat(queryResponse.getStatusCode()).isEqualTo(200);
        assertThat(http.requests.get(1).getHeaders().getValue(IS_QUERY)).isEqualTo("True");
        assertThat(http.requests.get(1).getHeaders().getValue(CONTINUATION)).isEqualTo("opaque-continuation");
        assertThat(http.requests.get(1).getBodyAsBinaryData().toString())
                .contains("\"query\":\"SELECT * FROM c WHERE c.kind = @kind")
                .contains("\"name\":\"@kind\"")
                .contains("\"value\":\"history-message\"");
    }

    private static CosmosAsyncContainer realSdkContainer(String databaseId, String containerId) {
        try {
            CosmosAsyncClient client = mock(CosmosAsyncClient.class);
            Constructor<CosmosAsyncDatabase> databaseConstructor =
                    CosmosAsyncDatabase.class.getDeclaredConstructor(String.class, CosmosAsyncClient.class);
            databaseConstructor.setAccessible(true);
            CosmosAsyncDatabase database = databaseConstructor.newInstance(databaseId, client);
            Constructor<CosmosAsyncContainer> containerConstructor =
                    CosmosAsyncContainer.class.getDeclaredConstructor(String.class, CosmosAsyncDatabase.class);
            containerConstructor.setAccessible(true);
            return containerConstructor.newInstance(containerId, database);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String sdkContainerLink(CosmosAsyncContainer container) {
        try {
            Field link = CosmosAsyncContainer.class.getDeclaredField("link");
            link.setAccessible(true);
            return (String) link.get(container);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String sdkQueryJson(SqlQuerySpec query) {
        try {
            var applySerializer = SqlQuerySpec.class.getDeclaredMethod(
                    "applySerializerToParameters", com.azure.cosmos.CosmosItemSerializer.class);
            applySerializer.setAccessible(true);
            applySerializer.invoke(query, com.azure.cosmos.CosmosItemSerializer.DEFAULT_SERIALIZER);
            var populatePropertyBag = SqlQuerySpec.class.getDeclaredMethod("populatePropertyBag");
            populatePropertyBag.setAccessible(true);
            populatePropertyBag.invoke(query);
            var getJsonSerializable = SqlQuerySpec.class.getDeclaredMethod("getJsonSerializable");
            getJsonSerializable.setAccessible(true);
            Object jsonSerializable = getJsonSerializable.invoke(query);
            return (String) jsonSerializable.getClass().getMethod("toJson").invoke(jsonSerializable);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class StrictHttpClient implements com.azure.core.http.HttpClient {
        private final List<HttpRequest> requests = new ArrayList<>();

        @Override
        public Mono<HttpResponse> send(HttpRequest request) {
            requests.add(request);
            if ("True".equals(request.getHeaders().getValue(IS_QUERY))) {
                return Mono.just(new StringHttpResponse(
                        request,
                        200,
                        "{\"Documents\":[],\"_count\":0}",
                        new HttpHeaders().set(CONTINUATION, "next-continuation")));
            }
            return Mono.just(new StringHttpResponse(
                    request,
                    201,
                    request.getBodyAsBinaryData().toString(),
                    new HttpHeaders()
                            .set(HttpHeaderName.ETAG, "etag-1")
                            .set(HttpHeaderName.fromString("x-ms-request-charge"), "4.5")
                            .set(HttpHeaderName.fromString("x-ms-activity-id"), "request-1")));
        }
    }

    private static final class StringHttpResponse extends HttpResponse {
        private final int statusCode;

        private final byte[] body;

        private final HttpHeaders headers;

        private StringHttpResponse(HttpRequest request, int statusCode, String body, HttpHeaders headers) {
            super(request);
            this.statusCode = statusCode;
            this.body = body.getBytes(StandardCharsets.UTF_8);
            this.headers = headers;
        }

        @Override
        public int getStatusCode() {
            return statusCode;
        }

        @Override
        @SuppressWarnings("deprecation")
        public String getHeaderValue(String name) {
            return headers.getValue(HttpHeaderName.fromString(name));
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public Flux<ByteBuffer> getBody() {
            return Flux.just(ByteBuffer.wrap(body));
        }

        @Override
        public Mono<byte[]> getBodyAsByteArray() {
            return Mono.just(body.clone());
        }

        @Override
        public Mono<String> getBodyAsString() {
            return Mono.just(new String(body, StandardCharsets.UTF_8));
        }

        @Override
        public Mono<String> getBodyAsString(Charset charset) {
            return Mono.just(new String(body, charset));
        }
    }
}
