// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.chatkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.agents.core.Content;
import com.microsoft.agents.core.DataContent;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.Role;
import com.microsoft.agents.core.UriContent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class ChatKitItemConverterTest {
    @Test
    void convertAsync_shouldPreserveItemRolesAndTextOrder() {
        // Arrange
        ChatKitItemConverter converter = new ChatKitItemConverter();
        List<ChatKitThreadItem> items = List.of(
                user("u1", List.of("  Hello ", "world  "), List.of(), "old quote"),
                new ChatKitHiddenContextItem(
                        "h1", "thread", ChatKitHiddenContextType.HIDDEN_CONTEXT_ITEM, "private", null),
                new ChatKitAssistantMessageItem("a1", "thread", List.of("First", "Second"), null),
                user("u2", List.of("Question"), List.of(), "Important excerpt"));

        // Act
        List<Message> messages =
                converter.convertAsync(items).toCompletableFuture().join();

        // Assert
        assertThat(messages)
                .extracting(Message::role)
                .containsExactly(Role.USER, Role.SYSTEM, Role.ASSISTANT, Role.USER, Role.USER);
        assertThat(messages)
                .extracting(Message::text)
                .containsExactly(
                        "Hello world",
                        "<HIDDEN_CONTEXT>private</HIDDEN_CONTEXT>",
                        "First Second",
                        "The user is referring to this in particular:\nImportant excerpt",
                        "Question");
    }

    @Test
    void convertAsync_shouldIgnoreEmptySupportedMessages() {
        ChatKitItemConverter converter = new ChatKitItemConverter();

        List<Message> messages = converter
                .convertAsync(List.of(
                        user("u1", List.of(" \n "), List.of(), null),
                        new ChatKitAssistantMessageItem("a1", "thread", List.of(), null)))
                .toCompletableFuture()
                .join();

        assertThat(messages).isEmpty();
    }

    @Test
    void convertAsync_shouldKeepQuoteWhenLastUserMessageHasNoOtherContent() {
        ChatKitItemConverter converter = new ChatKitItemConverter();

        List<Message> messages = converter
                .convertAsync(List.of(user("u1", List.of(), List.of(), "Only the quote")))
                .toCompletableFuture()
                .join();

        assertThat(messages)
                .singleElement()
                .extracting(Message::text)
                .isEqualTo("The user is referring to this in particular:\nOnly the quote");
    }

    @Test
    void convertAsync_shouldFetchAttachmentBytesSequentiallyInInputOrder() {
        // Arrange
        CompletableFuture<ChatKitFetchedAttachment> first = new CompletableFuture<>();
        CompletableFuture<ChatKitFetchedAttachment> second = new CompletableFuture<>();
        ArrayList<String> calls = new ArrayList<>();
        ChatKitAttachmentFetcher fetcher = attachment -> {
            calls.add(attachment.id());
            return attachment.id().equals("one") ? first : second;
        };
        ChatKitItemConverter converter = new ChatKitItemConverter(ChatKitConversionOptions.defaults(), fetcher);
        ChatKitAttachment one = file("one", null);
        ChatKitAttachment two = file("two", null);

        // Act
        CompletionStage<List<Message>> conversion =
                converter.convertAsync(List.of(user("u1", List.of(), List.of(one, two), null)));

        // Assert
        assertThat(calls).containsExactly("one");
        first.complete(new ChatKitFetchedAttachment("first".getBytes(StandardCharsets.UTF_8), "text/plain"));
        assertThat(calls).containsExactly("one", "two");
        second.complete(new ChatKitFetchedAttachment("second".getBytes(StandardCharsets.UTF_8), "text/plain"));

        List<Content> contents =
                conversion.toCompletableFuture().join().getFirst().contents();
        assertThat(contents).hasSize(2).allMatch(DataContent.class::isInstance);
        assertThat(((DataContent) contents.get(0)).data()).isEqualTo("first".getBytes(StandardCharsets.UTF_8));
        assertThat(((DataContent) contents.get(1)).data()).isEqualTo("second".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void convertAsync_shouldPreferFetchedBytesOverAllowedPreviewUri() {
        // Arrange
        ChatKitConversionOptions options =
                options(ChatKitAttachmentUriPolicy.allowHttpsHosts(List.of("cdn.example.test")));
        ChatKitItemConverter converter = new ChatKitItemConverter(
                options,
                ignored -> CompletableFuture.completedFuture(
                        new ChatKitFetchedAttachment(new byte[] {1, 2, 3}, "image/png")));

        // Act
        List<Content> contents = converter
                .convertAsync(
                        List.of(user("u1", List.of(), List.of(image("image", "https://cdn.example.test/a.png")), null)))
                .toCompletableFuture()
                .join()
                .getFirst()
                .contents();

        // Assert
        assertThat(contents).singleElement().isInstanceOf(DataContent.class);
    }

    @Test
    void convertAsync_shouldFallBackToAllowedHttpsUriWhenFetchFails() {
        // Arrange
        ChatKitConversionOptions options =
                options(ChatKitAttachmentUriPolicy.allowHttpsHosts(List.of("cdn.example.test")));
        ChatKitItemConverter converter = new ChatKitItemConverter(
                options, ignored -> CompletableFuture.failedFuture(new IllegalStateException("credentials omitted")));

        // Act
        List<Content> contents = converter
                .convertAsync(
                        List.of(user("u1", List.of(), List.of(image("image", "https://cdn.example.test/a.png")), null)))
                .toCompletableFuture()
                .join()
                .getFirst()
                .contents();

        // Assert
        assertThat(contents).singleElement().isInstanceOf(UriContent.class);
        assertThat(((UriContent) contents.getFirst()).uri()).isEqualTo(URI.create("https://cdn.example.test/a.png"));
    }

    @Test
    void convertAsync_shouldUseAllowedHttpsUriWithoutFetcher() {
        // Arrange
        ChatKitConversionOptions options =
                options(ChatKitAttachmentUriPolicy.allowHttpsHosts(List.of("cdn.example.test")));
        ChatKitItemConverter converter = new ChatKitItemConverter(options);

        // Act
        List<Message> messages = converter
                .convertAsync(
                        List.of(user("u1", List.of(), List.of(image("image", "https://cdn.example.test/a.png")), null)))
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(messages.getFirst().contents().getFirst()).isInstanceOf(UriContent.class);
    }

    @Test
    void convertAsync_shouldSkipDisallowedOrInsecureAttachmentUris() {
        // Arrange
        ChatKitConversionOptions options =
                options(ChatKitAttachmentUriPolicy.allowHttpsHosts(List.of("cdn.example.test")));
        ChatKitItemConverter converter = new ChatKitItemConverter(options);

        // Act
        List<Message> messages = converter
                .convertAsync(List.of(user(
                        "u1",
                        List.of(),
                        List.of(
                                image("http", "http://cdn.example.test/a.png"),
                                image("host", "https://other.example.test/a.png"),
                                image("userinfo", "https://user@cdn.example.test/a.png")),
                        null)))
                .toCompletableFuture()
                .join();

        // Assert
        assertThat(messages).isEmpty();
    }

    @Test
    void convertAsync_shouldFailWithoutLeakingContentWhenAttachmentPolicyRequiresIt() {
        // Arrange
        ChatKitConversionOptions options = new ChatKitConversionOptions(
                ChatKitAttachmentUriPolicy.denyAll(), ChatKitUnsupportedItemPolicy.IGNORE, 2, true);
        ChatKitItemConverter converter = new ChatKitItemConverter(
                options,
                ignored -> CompletableFuture.completedFuture(
                        new ChatKitFetchedAttachment("secret".getBytes(StandardCharsets.UTF_8), "text/plain")));

        // Act and assert
        assertThatThrownBy(() -> converter
                        .convertAsync(List.of(user("u1", List.of(), List.of(file("attachment-1", null)), null)))
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .hasMessageNotContaining("secret")
                .hasRootCauseMessage("Fetched attachment exceeds the configured byte limit.");
    }

    @Test
    void convertAsync_shouldFailWhenRequiredAttachmentHasNoFetcherOrAllowedUri() {
        // Arrange
        ChatKitConversionOptions options = new ChatKitConversionOptions(
                ChatKitAttachmentUriPolicy.denyAll(), ChatKitUnsupportedItemPolicy.IGNORE, 1024, true);
        ChatKitItemConverter converter = new ChatKitItemConverter(options);

        // Act and assert
        assertThatThrownBy(() -> converter
                        .convertAsync(List.of(user("u1", List.of(), List.of(file("attachment-1", null)), null)))
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .hasRootCauseMessage("No attachment fetcher is configured.");
    }

    @Test
    void convertAsync_shouldApplyUnsupportedItemPolicy() {
        ChatKitUnsupportedThreadItem unsupported = new ChatKitUnsupportedThreadItem("x", "thread", "generated_image");

        assertThat(new ChatKitItemConverter()
                        .convertAsync(List.of(unsupported))
                        .toCompletableFuture()
                        .join())
                .isEmpty();

        ChatKitConversionOptions reject = new ChatKitConversionOptions(
                ChatKitAttachmentUriPolicy.denyAll(), ChatKitUnsupportedItemPolicy.REJECT, 1024, false);
        assertThatThrownBy(() -> new ChatKitItemConverter(reject)
                        .convertAsync(List.of(unsupported))
                        .toCompletableFuture()
                        .join())
                .isInstanceOf(CompletionException.class)
                .hasRootCauseMessage("Unsupported ChatKit thread-item type: generated_image");
    }

    @Test
    void uriPolicy_shouldRequireExactDefaultPortHttpsHost() {
        ChatKitAttachmentUriPolicy policy = ChatKitAttachmentUriPolicy.allowHttpsHosts(List.of("CDN.Example.Test"));

        assertThat(policy.allowedHosts()).containsExactly("cdn.example.test");
        assertThat(policy.isAllowed(URI.create("https://cdn.example.test/a"))).isTrue();
        assertThat(policy.isAllowed(URI.create("https://cdn.example.test:443/a")))
                .isTrue();
        assertThat(policy.isAllowed(URI.create("https://sub.cdn.example.test/a")))
                .isFalse();
        assertThat(policy.isAllowed(URI.create("https://cdn.example.test:8443/a")))
                .isFalse();
        assertThat(policy.isAllowed(URI.create("https://cdn.example.test/a#fragment")))
                .isFalse();
    }

    private static ChatKitUserMessageItem user(
            String id, List<String> text, List<ChatKitAttachment> attachments, String quote) {
        return new ChatKitUserMessageItem(id, "thread", text, attachments, quote, null);
    }

    private static ChatKitAttachment image(String id, String uri) {
        return new ChatKitAttachment(id, ChatKitAttachmentKind.IMAGE, id + ".png", "image/png", URI.create(uri));
    }

    private static ChatKitAttachment file(String id, String uri) {
        return new ChatKitAttachment(
                id, ChatKitAttachmentKind.FILE, id + ".txt", "text/plain", uri == null ? null : URI.create(uri));
    }

    private static ChatKitConversionOptions options(ChatKitAttachmentUriPolicy policy) {
        return new ChatKitConversionOptions(policy, ChatKitUnsupportedItemPolicy.IGNORE, 1024, false);
    }
}
