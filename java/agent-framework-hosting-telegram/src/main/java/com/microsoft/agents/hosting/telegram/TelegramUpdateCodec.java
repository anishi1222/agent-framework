// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.telegram;

import com.microsoft.agents.core.SerializationException;
import com.microsoft.agents.core.StateValue;
import com.microsoft.agents.core.internal.StrictJsonCodec;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class TelegramUpdateCodec {
    private final TelegramWebhookOptions options;

    private final StrictJsonCodec json;

    TelegramUpdateCodec(TelegramWebhookOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        json = new StrictJsonCodec(
                options.maxUpdateBytes(),
                options.maxUpdateBytes(),
                options.maxNestingDepth(),
                options.maxStringLength(),
                64,
                options.maxCollectionEntries());
    }

    TelegramUpdateParseResult decode(byte[] body) {
        StateValue parsed;
        try {
            parsed = json.parse(Objects.requireNonNull(body, "body"));
        } catch (SerializationException exception) {
            throw new TelegramUpdateException(TelegramUpdateError.MALFORMED, "Telegram update JSON is malformed.");
        }
        if (!(parsed instanceof StateValue.ObjectValue update)) {
            throw new TelegramUpdateException(TelegramUpdateError.MALFORMED, "Telegram update must be an object.");
        }
        long updateId = requiredPositiveLong(update, "update_id");
        List<String> payloadMembers = new ArrayList<>();
        for (String member : update.values().keySet()) {
            if (!"update_id".equals(member)) {
                payloadMembers.add(member);
            }
        }
        if (payloadMembers.isEmpty()) {
            return TelegramUpdateParseResult.unsupported(updateId, "empty-update");
        }
        if (payloadMembers.size() != 1) {
            throw new TelegramUpdateException(
                    TelegramUpdateError.INVALID, "Telegram update contains multiple payload members.");
        }
        String updateType = payloadMembers.getFirst();
        if (!"message".equals(updateType)) {
            return TelegramUpdateParseResult.unsupported(updateId, updateType);
        }
        StateValue value = update.values().get("message");
        if (!(value instanceof StateValue.ObjectValue message)) {
            throw new TelegramUpdateException(TelegramUpdateError.INVALID, "Telegram message must be an object.");
        }
        StateValue textValue = message.values().get("text");
        if (textValue == null || textValue == StateValue.NullValue.INSTANCE) {
            return TelegramUpdateParseResult.unsupported(updateId, "message-without-text");
        }
        if (!(textValue instanceof StateValue.StringValue text)) {
            throw new TelegramUpdateException(TelegramUpdateError.INVALID, "Telegram message text must be a string.");
        }
        if (text.value().isEmpty()) {
            throw new TelegramUpdateException(TelegramUpdateError.INVALID, "Telegram message text must not be empty.");
        }
        if (text.value().length() > options.maxInboundTextLength()) {
            throw new TelegramUpdateException(
                    TelegramUpdateError.INVALID, "Telegram message text exceeds maxInboundTextLength.");
        }
        if (!TelegramValidation.isWellFormedUtf16(text.value())) {
            throw new TelegramUpdateException(
                    TelegramUpdateError.INVALID, "Telegram message text must contain well-formed UTF-16.");
        }
        StateValue senderValue = message.values().get("from");
        if (senderValue == null || senderValue == StateValue.NullValue.INSTANCE) {
            return TelegramUpdateParseResult.unsupported(updateId, "message-without-user");
        }
        StateValue.ObjectValue sender = requiredObject(senderValue, "Telegram user");
        StateValue.ObjectValue chat = requiredObject(message.values().get("chat"), "Telegram chat");
        long messageId = requiredPositiveLong(message, "message_id");
        long chatId = requiredNonZeroLong(chat, "id");
        String chatType = requiredString(chat, "type");
        long userId = requiredPositiveLong(sender, "id");
        return TelegramUpdateParseResult.supported(
                new TelegramInboundMessage(updateId, messageId, chatId, chatType, userId, text.value()));
    }

    private static StateValue.ObjectValue requiredObject(StateValue value, String name) {
        if (value instanceof StateValue.ObjectValue object) {
            return object;
        }
        throw new TelegramUpdateException(TelegramUpdateError.INVALID, name + " must be an object.");
    }

    private static String requiredString(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value instanceof StateValue.StringValue string
                && !string.value().isBlank()
                && TelegramValidation.isWellFormedUtf16(string.value())) {
            return string.value();
        }
        throw new TelegramUpdateException(
                TelegramUpdateError.INVALID, "Telegram member '" + name + "' must be a non-blank well-formed string.");
    }

    private static long requiredPositiveLong(StateValue.ObjectValue object, String name) {
        long value = requiredLong(object, name);
        if (value <= 0) {
            throw new TelegramUpdateException(
                    TelegramUpdateError.INVALID, "Telegram member '" + name + "' must be positive.");
        }
        return value;
    }

    private static long requiredNonZeroLong(StateValue.ObjectValue object, String name) {
        long value = requiredLong(object, name);
        if (value == 0) {
            throw new TelegramUpdateException(
                    TelegramUpdateError.INVALID, "Telegram member '" + name + "' must not be zero.");
        }
        return value;
    }

    private static long requiredLong(StateValue.ObjectValue object, String name) {
        StateValue value = object.values().get(name);
        if (value instanceof StateValue.NumberValue number && isInteger(number.value())) {
            try {
                return number.value().longValueExact();
            } catch (ArithmeticException ignored) {
                // Fall through to the stable error.
            }
        }
        throw new TelegramUpdateException(
                TelegramUpdateError.INVALID, "Telegram member '" + name + "' must be an integer.");
    }

    private static boolean isInteger(BigDecimal value) {
        return value.scale() <= 0;
    }
}
