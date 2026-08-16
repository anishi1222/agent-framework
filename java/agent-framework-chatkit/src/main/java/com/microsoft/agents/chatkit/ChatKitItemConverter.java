// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.chatkit;

import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.DataContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.TextContent;
import com.microsoft.agents.core.UriContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * Converts the supported ChatKit thread subset into provider-neutral Agent Framework messages.
 *
 * <p>Items and attachments are processed sequentially so output ordering does not depend on
 * asynchronous attachment completion timing.
 */
public final class ChatKitItemConverter {
    private static final String QUOTED_TEXT_PREFIX = "The user is referring to this in particular:\n";

    private final ChatKitConversionOptions options;
    private final ChatKitAttachmentFetcher attachmentFetcher;

    /** Creates a converter with secure defaults and no byte fetcher. */
    public ChatKitItemConverter() {
        this(ChatKitConversionOptions.defaults(), null);
    }

    /**
     * Creates a converter with explicit options and no byte fetcher.
     *
     * @param options conversion options
     */
    public ChatKitItemConverter(ChatKitConversionOptions options) {
        this(options, null);
    }

    /**
     * Creates a converter with explicit options and an optional asynchronous byte fetcher.
     *
     * @param options conversion options
     * @param attachmentFetcher optional trusted byte fetcher
     */
    public ChatKitItemConverter(ChatKitConversionOptions options, ChatKitAttachmentFetcher attachmentFetcher) {
        this.options = Objects.requireNonNull(options, "options");
        this.attachmentFetcher = attachmentFetcher;
    }

    /**
     * Converts thread items into messages in deterministic input and content order.
     *
     * @param items ordered ChatKit thread items
     * @return a finite asynchronous immutable message list
     */
    public CompletionStage<List<Message>> convertAsync(List<? extends ChatKitThreadItem> items) {
        Objects.requireNonNull(items, "items");
        List<? extends ChatKitThreadItem> snapshot = List.copyOf(items);
        CompletionStage<ArrayList<Message>> result = CompletableFuture.completedFuture(new ArrayList<>());
        for (int index = 0; index < snapshot.size(); index++) {
            ChatKitThreadItem item = snapshot.get(index);
            boolean isLast = index == snapshot.size() - 1;
            result = result.thenCompose(
                    messages -> convertItemAsync(item, isLast).thenApply(converted -> {
                        messages.addAll(converted);
                        return messages;
                    }));
        }
        return result.thenApply(List::copyOf);
    }

    private CompletionStage<List<Message>> convertItemAsync(ChatKitThreadItem item, boolean isLast) {
        Objects.requireNonNull(item, "items contains null");
        if (item instanceof ChatKitUserMessageItem user) {
            return convertUserMessageAsync(user, isLast);
        }
        if (item instanceof ChatKitAssistantMessageItem assistant) {
            return CompletableFuture.completedFuture(convertAssistantMessage(assistant));
        }
        if (item instanceof ChatKitHiddenContextItem hidden) {
            return CompletableFuture.completedFuture(
                    List.of(Message.text(Role.SYSTEM, "<HIDDEN_CONTEXT>" + hidden.content() + "</HIDDEN_CONTEXT>")));
        }
        if (item instanceof ChatKitUnsupportedThreadItem unsupported) {
            if (options.unsupportedItemPolicy() == ChatKitUnsupportedItemPolicy.IGNORE) {
                return CompletableFuture.completedFuture(List.of());
            }
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Unsupported ChatKit thread-item type: " + unsupported.type()));
        }
        return CompletableFuture.failedFuture(
                new IllegalArgumentException("Unsupported ChatKit thread-item implementation."));
    }

    private CompletionStage<List<Message>> convertUserMessageAsync(ChatKitUserMessageItem item, boolean isLast) {
        return convertAttachmentsAsync(item.attachments()).thenApply(attachments -> {
            ArrayList<Message> messages = new ArrayList<>(2);
            if (isLast && item.quotedText() != null && !item.quotedText().isEmpty()) {
                messages.add(Message.text(Role.USER, QUOTED_TEXT_PREFIX + item.quotedText()));
            }

            String text = String.join("", item.textParts()).strip();
            ArrayList<Content> contents = new ArrayList<>(attachments.size() + 1);
            if (!text.isEmpty()) {
                contents.add(new TextContent(text));
            }
            contents.addAll(attachments);
            if (!contents.isEmpty()) {
                messages.add(new Message(Role.USER, contents));
            }
            return List.copyOf(messages);
        });
    }

    private List<Message> convertAssistantMessage(ChatKitAssistantMessageItem item) {
        if (item.outputTextParts().isEmpty()) {
            return List.of();
        }
        ArrayList<Content> contents = new ArrayList<>(item.outputTextParts().size());
        for (String text : item.outputTextParts()) {
            contents.add(new TextContent(text));
        }
        return List.of(new Message(Role.ASSISTANT, contents));
    }

    private CompletionStage<List<Content>> convertAttachmentsAsync(List<ChatKitAttachment> attachments) {
        CompletionStage<ArrayList<Content>> result = CompletableFuture.completedFuture(new ArrayList<>());
        for (ChatKitAttachment attachment : attachments) {
            result = result.thenCompose(
                    contents -> convertAttachmentAsync(attachment).thenApply(resolved -> {
                        resolved.ifPresent(contents::add);
                        return contents;
                    }));
        }
        return result.thenApply(List::copyOf);
    }

    private CompletionStage<Optional<Content>> convertAttachmentAsync(ChatKitAttachment attachment) {
        if (attachmentFetcher == null) {
            return completeFallback(attachment, new IllegalStateException("No attachment fetcher is configured."));
        }

        CompletionStage<ChatKitFetchedAttachment> fetched;
        try {
            fetched =
                    Objects.requireNonNull(attachmentFetcher.fetchAsync(attachment), "attachmentFetcher returned null");
        } catch (RuntimeException exception) {
            return completeFallback(attachment, exception);
        }

        return fetched.handle((value, failure) -> {
            if (failure == null && value != null) {
                byte[] data = value.data();
                if (data.length <= options.maxAttachmentBytes()) {
                    return Optional.<Content>of(new DataContent(data, value.mediaType()));
                }
                failure = new IllegalArgumentException("Fetched attachment exceeds the configured byte limit.");
            } else if (failure == null) {
                failure = new IllegalArgumentException("Attachment fetcher completed with null.");
            }
            return fallback(attachment, failure);
        });
    }

    private CompletionStage<Optional<Content>> completeFallback(ChatKitAttachment attachment, Throwable failure) {
        try {
            return CompletableFuture.completedFuture(fallback(attachment, failure));
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private Optional<Content> fallback(ChatKitAttachment attachment, Throwable failure) {
        if (options.attachmentUriPolicy().isAllowed(attachment.previewUri())) {
            return Optional.of(new UriContent(attachment.previewUri(), attachment.mediaType()));
        }
        if (failure != null && options.failOnAttachmentError()) {
            throw new CompletionException(new IllegalArgumentException(
                    "Unable to resolve ChatKit attachment: " + attachment.id(), unwrap(failure)));
        }
        return Optional.empty();
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
