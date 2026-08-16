// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.valkey;

import com.microsoft.agents.core.DefaultRunCancellation;
import com.microsoft.agents.core.RunCancellation;
import com.microsoft.agents.core.RunCancelledException;
import glide.api.GlideClient;
import glide.api.models.configuration.GlideClientConfiguration;
import glide.api.models.configuration.NodeAddress;
import glide.api.models.configuration.ServerCredentials;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

final class GlideValkeyClientFactory {
    private static final System.Logger LOGGER = System.getLogger(GlideValkeyClientFactory.class.getName());

    private GlideValkeyClientFactory() {}

    static CompletionStage<ValkeyCommandAdapter> createAsync(ValkeyClientOptions options) {
        return createAsync(options, new DefaultRunCancellation());
    }

    static CompletionStage<ValkeyCommandAdapter> createAsync(
            ValkeyClientOptions options, RunCancellation cancellation) {
        ValkeyValidation.requireNonNull(options, "options");
        ValkeyValidation.requireNonNull(cancellation, "cancellation");
        if (cancellation.isCancellationRequested()) {
            return CompletableFuture.failedStage(new RunCancelledException());
        }

        CompletionStage<GlideClient> created = createGlideClientAsync(options);
        return ValkeyAsyncSupport.race(
                        created, options.operationTimeout(), cancellation, GlideValkeyClientFactory::closeLateClient)
                .thenApply(GlideValkeyClientFactory::adapter);
    }

    static CompletionStage<GlideClient> createGlideClientAsync(ValkeyClientOptions options) {
        ValkeyValidation.requireNonNull(options, "options");
        GlideClientConfiguration.GlideClientConfigurationBuilder<?, ?> builder = GlideClientConfiguration.builder()
                .address(NodeAddress.builder()
                        .host(options.endpoint().host())
                        .port(options.endpoint().port())
                        .build())
                .useTLS(options.useTls())
                .requestTimeout(Math.toIntExact(options.operationTimeout().toMillis()))
                .libName("agent-framework-valkey");
        if (options.clientName() != null) {
            builder.clientName(options.clientName());
        }
        if (options.authentication().kind() == ValkeyAuthentication.Kind.ACL) {
            builder.credentials(ServerCredentials.builder()
                    .username(options.authentication().username().orElseThrow())
                    .password(options.authentication().password().orElseThrow().secretValue())
                    .build());
        }

        try {
            return GlideValkeyFailureMapper.mapCreationStage(GlideClient.createClient(builder.build()));
        } catch (RuntimeException exception) {
            return CompletableFuture.failedStage(GlideValkeyFailureMapper.mapCreation(exception));
        }
    }

    private static ValkeyCommandAdapter adapter(GlideClient client) {
        try {
            return new GlideValkeyCommandAdapter(client);
        } catch (RuntimeException exception) {
            closeLateClient(client);
            throw GlideValkeyFailureMapper.map(exception);
        } catch (Error error) {
            closeLateClient(client);
            throw error;
        }
    }

    static void closeLateClient(GlideClient client) {
        try {
            client.close();
        } catch (RuntimeException | ExecutionException exception) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "A late Valkey client could not be closed after cancellation or timeout.");
        }
    }
}
