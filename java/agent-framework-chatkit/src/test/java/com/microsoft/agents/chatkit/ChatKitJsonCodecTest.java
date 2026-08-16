// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.chatkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatKitJsonCodecTest {
    private static final Instant CREATED_AT = Instant.parse("2026-08-14T00:00:00Z");

    @Test
    void decodeThreadItems_shouldPreserveSupportedItemAndContentOrder() {
        // Arrange
        ChatKitJsonCodec codec = new ChatKitJsonCodec();
        String json = """
                [
                  {
                    "id": "user-1",
                    "thread_id": "thread-1",
                    "type": "user_message",
                    "content": [
                      {"type": "text", "text": "Hello "},
                      {"type": "text", "text": "world"}
                    ],
                    "attachments": [
                      {
                        "id": "image-1",
                        "type": "image",
                        "name": "chart.png",
                        "mime_type": "image/png",
                        "preview_url": "https://cdn.example.test/chart.png"
                      }
                    ],
                    "quoted_text": "Earlier text",
                    "created_at": "2026-08-14T00:00:00Z"
                  },
                  {
                    "id": "assistant-1",
                    "thread_id": "thread-1",
                    "type": "assistant_message",
                    "content": [
                      {"type": "output_text", "text": "First", "annotations": []},
                      {"type": "output_text", "text": "Second", "annotations": []}
                    ]
                  },
                  {
                    "id": "hidden-1",
                    "thread_id": "thread-1",
                    "type": "sdk_hidden_context",
                    "content": "private context"
                  }
                ]
                """;

        // Act
        List<ChatKitThreadItem> items = codec.decodeThreadItems(json);

        // Assert
        assertThat(items)
                .containsExactly(
                        new ChatKitUserMessageItem(
                                "user-1",
                                "thread-1",
                                List.of("Hello ", "world"),
                                List.of(new ChatKitAttachment(
                                        "image-1",
                                        ChatKitAttachmentKind.IMAGE,
                                        "chart.png",
                                        "image/png",
                                        URI.create("https://cdn.example.test/chart.png"))),
                                "Earlier text",
                                CREATED_AT),
                        new ChatKitAssistantMessageItem("assistant-1", "thread-1", List.of("First", "Second"), null),
                        new ChatKitHiddenContextItem(
                                "hidden-1",
                                "thread-1",
                                ChatKitHiddenContextType.SDK_HIDDEN_CONTEXT,
                                "private context",
                                null));
    }

    @Test
    void encodeThreadItem_shouldUseCanonicalRecursiveOrdering() {
        // Arrange
        ChatKitJsonCodec codec = new ChatKitJsonCodec();
        ChatKitAttachment attachment =
                new ChatKitAttachment("file-1", ChatKitAttachmentKind.FILE, "notes.txt", "text/plain", null);
        ChatKitUserMessageItem item = new ChatKitUserMessageItem(
                "user-1", "thread-1", List.of("Hello"), List.of(attachment), "Quote", CREATED_AT);
        String expected = "{\"attachments\":[{\"id\":\"file-1\",\"mime_type\":\"text/plain\","
                + "\"name\":\"notes.txt\",\"type\":\"file\"}],"
                + "\"content\":[{\"text\":\"Hello\",\"type\":\"text\"}],"
                + "\"created_at\":\"2026-08-14T00:00:00Z\",\"id\":\"user-1\","
                + "\"quoted_text\":\"Quote\",\"thread_id\":\"thread-1\","
                + "\"type\":\"user_message\"}";

        // Act
        String json = codec.encodeThreadItem(item);

        // Assert
        assertThat(json).isEqualTo(expected);
    }

    @Test
    void encodeThreadEvent_shouldMatchSupportedAddedUpdatedAndDoneShapes() {
        // Arrange
        ChatKitJsonCodec codec = new ChatKitJsonCodec();
        ChatKitAssistantMessageItem empty = new ChatKitAssistantMessageItem("msg_1", "thread-1", List.of(), CREATED_AT);
        ChatKitAssistantMessageItem complete =
                new ChatKitAssistantMessageItem("msg_1", "thread-1", List.of("Hello"), CREATED_AT);
        String addedJson = "{\"item\":{\"content\":[],\"created_at\":\"2026-08-14T00:00:00Z\","
                + "\"id\":\"msg_1\",\"thread_id\":\"thread-1\","
                + "\"type\":\"assistant_message\"},\"type\":\"thread.item.added\"}";
        String updatedJson = "{\"item_id\":\"msg_1\",\"type\":\"thread.item.updated\","
                + "\"update\":{\"content_index\":0,\"delta\":\"Hello\"}}";
        String doneJson = "{\"item\":{\"content\":[{\"annotations\":[],\"text\":\"Hello\","
                + "\"type\":\"output_text\"}],"
                + "\"created_at\":\"2026-08-14T00:00:00Z\",\"id\":\"msg_1\","
                + "\"thread_id\":\"thread-1\",\"type\":\"assistant_message\"},"
                + "\"type\":\"thread.item.done\"}";

        // Act and assert
        assertThat(codec.encodeThreadEvent(new ChatKitThreadItemAddedEvent(empty)))
                .isEqualTo(addedJson);
        assertThat(codec.encodeThreadEvent(new ChatKitThreadItemUpdatedEvent("msg_1", 0, "Hello")))
                .isEqualTo(updatedJson);
        assertThat(codec.encodeThreadEvent(new ChatKitThreadItemDoneEvent(complete)))
                .isEqualTo(doneJson);
    }

    @Test
    void decodeThreadItem_shouldRejectDuplicateKeys() {
        ChatKitJsonCodec codec = new ChatKitJsonCodec();

        assertThatThrownBy(() -> codec.decodeThreadItem("""
                                        {
                                          "id": "one",
                                          "id": "two",
                                          "thread_id": "t",
                                          "type": "user_message",
                                          "content": [],
                                          "attachments": []
                                        }
                                        """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid ChatKit JSON.");
    }

    @Test
    void decodeThreadItem_shouldRejectUnknownFieldsByDefault() {
        ChatKitJsonCodec codec = new ChatKitJsonCodec();

        assertThatThrownBy(() -> codec.decodeThreadItem("""
                                        {
                                          "id": "one",
                                          "thread_id": "t",
                                          "type": "user_message",
                                          "content": [],
                                          "attachments": [],
                                          "secret": "value"
                                        }
                                        """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown field")
                .hasMessageNotContaining("value");
    }

    @Test
    void decodeThreadItem_shouldIgnoreUnknownFieldsWhenConfigured() {
        // Arrange
        ChatKitJsonCodec codec = codecIgnoringUnknown();

        // Act
        ChatKitThreadItem item = codec.decodeThreadItem("""
                        {
                          "id": "one",
                          "thread_id": "t",
                          "type": "user_message",
                          "content": [],
                          "attachments": [],
                          "future": {"nested": true}
                        }
                        """);

        // Assert
        assertThat(item).isEqualTo(new ChatKitUserMessageItem("one", "t", List.of(), List.of(), null, null));
    }

    @Test
    void decodeThreadItem_shouldRetainBoundedUnsupportedMarkerWhenConfigured() {
        // Arrange
        ChatKitJsonCodec codec = new ChatKitJsonCodec(
                ChatKitJsonLimits.defaults(), ChatKitUnknownFieldPolicy.REJECT, ChatKitUnsupportedItemPolicy.IGNORE);

        // Act
        ChatKitThreadItem item = codec.decodeThreadItem("""
                        {"id":"end-1","thread_id":"t","type":"end_of_turn","future":{"nested":true}}
                        """);

        // Assert
        assertThat(item).isEqualTo(new ChatKitUnsupportedThreadItem("end-1", "t", "end_of_turn"));
        assertThatThrownBy(() -> codec.encodeThreadItem(item))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be encoded");
    }

    @Test
    void decodeThreadItem_shouldRejectUnsupportedItemWhenConfigured() {
        ChatKitJsonCodec codec = new ChatKitJsonCodec();

        assertThatThrownBy(() -> codec.decodeThreadItem("""
                                        {"id":"end-1","thread_id":"t","type":"end_of_turn"}
                                        """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("end_of_turn");
    }

    @Test
    void decodeThreadItem_shouldRejectTrailingTokensAndNonFiniteNumbers() {
        ChatKitJsonCodec codec = codecIgnoringUnknown();

        assertThatThrownBy(() -> codec.decodeThreadItem("""
                                        {"id":"one","thread_id":"t","type":"user_message"} {}
                                        """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid ChatKit JSON.");
        assertThatThrownBy(() -> codec.decodeThreadItem("""
                                        {"id":"one","thread_id":"t","type":"user_message","score":NaN}
                                        """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid ChatKit JSON.");
    }

    @Test
    void decodeThreadItems_shouldEnforceDocumentStringCollectionAndDepthLimits() {
        ChatKitJsonCodec documentCodec = codecWithLimits(new ChatKitJsonLimits(20, 100, 100, 10, 20));
        ChatKitJsonCodec stringCodec = codecWithLimits(new ChatKitJsonLimits(1_000, 20, 100, 10, 20));
        ChatKitJsonCodec collectionCodec = codecWithLimits(new ChatKitJsonLimits(1_000, 100, 2, 10, 20));
        ChatKitJsonCodec depthCodec = codecWithLimits(new ChatKitJsonLimits(1_000, 100, 100, 3, 20));

        assertThatThrownBy(() -> documentCodec.decodeThreadItem("""
                                        {"id":"one","thread_id":"t","type":"user_message"}
                                        """)).hasMessageContaining("document-size");
        assertThatThrownBy(() -> stringCodec.decodeThreadItem("""
                                        {
                                          "id": "one",
                                          "thread_id": "t",
                                          "type": "user_message",
                                          "content": [
                                            {"type": "text", "text": "123456789012345678901"}
                                          ]
                                        }
                                        """)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> collectionCodec.decodeThreadItems("[1,2,3]")).hasMessageContaining("collection-size");
        assertThatThrownBy(() -> depthCodec.decodeThreadItem("""
                                        {
                                          "id": "one",
                                          "thread_id": "t",
                                          "type": "user_message",
                                          "future": {"a": {"b": {"c": true}}}
                                        }
                                        """)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void encodeThreadEvent_shouldEnforceNumericTokenLimit() {
        // Arrange
        ChatKitJsonCodec codec = codecWithLimits(new ChatKitJsonLimits(1_000, 100, 100, 10, 1));

        // Act and assert
        assertThatThrownBy(() -> codec.encodeThreadEvent(new ChatKitThreadItemUpdatedEvent("msg_1", 10, "delta")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("numeric-token");
    }

    private static ChatKitJsonCodec codecWithLimits(ChatKitJsonLimits limits) {
        return new ChatKitJsonCodec(limits, ChatKitUnknownFieldPolicy.IGNORE, ChatKitUnsupportedItemPolicy.REJECT);
    }

    private static ChatKitJsonCodec codecIgnoringUnknown() {
        return codecWithLimits(ChatKitJsonLimits.defaults());
    }
}
