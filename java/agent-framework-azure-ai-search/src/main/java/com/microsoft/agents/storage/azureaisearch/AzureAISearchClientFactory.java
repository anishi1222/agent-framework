// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.azureaisearch;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.KeyCredential;
import com.azure.core.credential.TokenCredential;
import com.azure.core.http.HttpClient;
import com.azure.search.documents.SearchAsyncClient;
import com.azure.search.documents.SearchAudience;
import com.azure.search.documents.SearchClientBuilder;
import com.azure.search.documents.indexes.SearchIndexAsyncClient;
import com.azure.search.documents.indexes.SearchIndexClientBuilder;
import com.azure.search.documents.knowledgebases.KnowledgeBaseRetrievalAsyncClient;
import com.azure.search.documents.knowledgebases.KnowledgeBaseRetrievalClientBuilder;
import com.microsoft.agents.azure.AzureAuthenticationProvider;
import com.microsoft.agents.azure.AzureTokenRequest;
import com.microsoft.agents.core.DefaultRunCancellation;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import reactor.core.publisher.Mono;

final class AzureAISearchClientFactory {
    private AzureAISearchClientFactory() {}

    static Clients create(AzureAISearchOptions options) {
        return create(options, null);
    }

    static Clients create(AzureAISearchOptions options, HttpClient httpClient) {
        SearchIndexClientBuilder indexBuilder = new SearchIndexClientBuilder()
                .endpoint(options.endpoint().toString())
                .audience(audience(options.audience()));
        if (httpClient != null) {
            indexBuilder.httpClient(httpClient);
        }
        credential(indexBuilder, options.authentication());
        SearchIndexAsyncClient indexClient = indexBuilder.buildAsyncClient();

        if (options.mode() == AzureAISearchQueryMode.AGENTIC) {
            KnowledgeBaseRetrievalClientBuilder retrievalBuilder = new KnowledgeBaseRetrievalClientBuilder()
                    .endpoint(options.endpoint().toString())
                    .knowledgeBaseName(options.knowledgeBaseName())
                    .audience(audience(options.audience()));
            if (httpClient != null) {
                retrievalBuilder.httpClient(httpClient);
            }
            credential(retrievalBuilder, options.authentication());
            return new Clients(null, indexClient, retrievalBuilder.buildAsyncClient());
        }

        SearchClientBuilder searchBuilder = new SearchClientBuilder()
                .endpoint(options.endpoint().toString())
                .indexName(options.indexName())
                .audience(audience(options.audience()));
        if (httpClient != null) {
            searchBuilder.httpClient(httpClient);
        }
        credential(searchBuilder, options.authentication());
        return new Clients(searchBuilder.buildAsyncClient(), indexClient, null);
    }

    private static SearchAudience audience(AzureAISearchAudience audience) {
        return switch (audience) {
            case AZURE_PUBLIC_CLOUD -> SearchAudience.AZURE_PUBLIC_CLOUD;
            case AZURE_GOVERNMENT -> SearchAudience.AZURE_GOVERNMENT;
            case AZURE_CHINA -> SearchAudience.AZURE_CHINA;
        };
    }

    private static void credential(SearchClientBuilder builder, AzureAISearchAuthentication authentication) {
        if (authentication.kind() == AzureAISearchAuthentication.Kind.RBAC) {
            builder.credential(tokenCredential(authentication.provider()));
        } else {
            builder.credential(new KeyCredential(authentication.apiKey().secretValue()));
        }
    }

    private static void credential(SearchIndexClientBuilder builder, AzureAISearchAuthentication authentication) {
        if (authentication.kind() == AzureAISearchAuthentication.Kind.RBAC) {
            builder.credential(tokenCredential(authentication.provider()));
        } else {
            builder.credential(new KeyCredential(authentication.apiKey().secretValue()));
        }
    }

    private static void credential(
            KnowledgeBaseRetrievalClientBuilder builder, AzureAISearchAuthentication authentication) {
        if (authentication.kind() == AzureAISearchAuthentication.Kind.RBAC) {
            builder.credential(tokenCredential(authentication.provider()));
        } else {
            builder.credential(new KeyCredential(authentication.apiKey().secretValue()));
        }
    }

    private static TokenCredential tokenCredential(AzureAuthenticationProvider authentication) {
        return context -> Mono.create(sink -> {
            DefaultRunCancellation cancellation = new DefaultRunCancellation();
            sink.onCancel(cancellation::cancel);
            CompletionStage<com.microsoft.agents.azure.AzureAccessToken> stage;
            try {
                stage = authentication.getTokenAsync(
                        new AzureTokenRequest(context.getScopes(), context.getTenantId()), cancellation);
            } catch (RuntimeException failure) {
                sink.error(failure);
                return;
            }
            if (stage == null) {
                sink.error(new IllegalStateException("AzureAuthenticationProvider.getTokenAsync returned null."));
                return;
            }
            stage.whenComplete((token, failure) -> {
                if (failure != null) {
                    sink.error(unwrap(failure));
                } else if (token == null) {
                    sink.error(new IllegalStateException("AzureAuthenticationProvider returned no token."));
                } else {
                    sink.success(new AccessToken(
                            token.token(), OffsetDateTime.ofInstant(token.expiresAt(), ZoneOffset.UTC)));
                }
            });
        });
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
    }

    record Clients(
            SearchAsyncClient searchClient,
            SearchIndexAsyncClient indexClient,
            KnowledgeBaseRetrievalAsyncClient retrievalClient) {}
}
