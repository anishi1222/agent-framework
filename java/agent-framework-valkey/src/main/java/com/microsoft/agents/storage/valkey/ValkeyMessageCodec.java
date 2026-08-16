// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.valkey;

import com.microsoft.agents.core.DocumentKind;
import com.microsoft.agents.core.JsonStateSerializer;
import com.microsoft.agents.core.Message;
import com.microsoft.agents.core.SerializationError;
import com.microsoft.agents.core.SerializationException;
import com.microsoft.agents.core.SerializationLimits;
import com.microsoft.agents.core.StateEnvelope;
import com.microsoft.agents.core.internal.MessageStateCodec;

final class ValkeyMessageCodec {
    static final int PAYLOAD_VERSION = 1;

    private final int maxMessageBytes;

    private final JsonStateSerializer serializer;

    private final MessageStateCodec messageCodec = new MessageStateCodec();

    ValkeyMessageCodec(int maxMessageBytes) {
        this.maxMessageBytes = maxMessageBytes;
        serializer =
                new JsonStateSerializer(new SerializationLimits(maxMessageBytes, 64, maxMessageBytes, 128, 100_000));
    }

    byte[] encode(Message message) {
        try {
            return serializer.write(
                    StateEnvelope.of(DocumentKind.HISTORY_MESSAGE, PAYLOAD_VERSION, messageCodec.encode(message)));
        } catch (SerializationException exception) {
            throw incompatible("A history message could not be encoded within configured limits.");
        }
    }

    Message decode(byte[] encoded) {
        if (encoded == null || encoded.length > maxMessageBytes) {
            throw incompatible("A stored history message exceeds configured limits.");
        }
        try {
            StateEnvelope envelope = serializer.read(encoded, DocumentKind.HISTORY_MESSAGE);
            if (envelope.payloadVersion() != PAYLOAD_VERSION) {
                throw new SerializationException(
                        SerializationError.UNSUPPORTED_PAYLOAD_VERSION, "Unsupported history-message payload version.");
            }
            return messageCodec.decode(envelope.payload());
        } catch (SerializationException exception) {
            throw incompatible("A stored history message is malformed or unsupported.");
        }
    }

    private static ValkeyStorageException incompatible(String message) {
        return new ValkeyStorageException(message, null, ValkeyStorageException.Kind.INCOMPATIBLE_DATA);
    }
}
