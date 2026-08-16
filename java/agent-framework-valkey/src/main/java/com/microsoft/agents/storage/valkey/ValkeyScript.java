// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.storage.valkey;

enum ValkeyScript {
    APPEND("""
            local operation = ARGV[1]
            local digest = ARGV[2]
            local maxStored = tonumber(ARGV[3])
            local maxDedup = tonumber(ARGV[4])
            local ttlMillis = tonumber(ARGV[5])
            local messageCount = tonumber(ARGV[6])
            if #KEYS ~= 3 or #ARGV < 6
                or not maxStored or maxStored < 1 or maxStored > 100000
                or maxStored ~= math.floor(maxStored)
                or not maxDedup or maxDedup < 1 or maxDedup > 100000
                or maxDedup ~= math.floor(maxDedup)
                or not ttlMillis or ttlMillis < 0 or ttlMillis > 315360000000
                or ttlMillis ~= math.floor(ttlMillis)
                or not messageCount or messageCount < 0 or messageCount > 10000
                or messageCount ~= math.floor(messageCount)
                or messageCount ~= (#ARGV - 6) then
              return redis.error_reply('AF_VALKEY_INVALID_APPEND_ARGS')
            end
            local function keyType(key)
              local result = redis.call('TYPE', key)
              if type(result) == 'table' then
                return result.ok
              end
              return result
            end
            local messagesType = keyType(KEYS[1])
            if messagesType ~= 'none' and messagesType ~= 'list' then
              return redis.error_reply('AF_VALKEY_WRONG_TYPE_MESSAGES')
            end
            local dedupType = keyType(KEYS[2])
            if dedupType ~= 'none' and dedupType ~= 'hash' then
              return redis.error_reply('AF_VALKEY_WRONG_TYPE_DEDUP')
            end
            local dedupOrderType = keyType(KEYS[3])
            if dedupOrderType ~= 'none' and dedupOrderType ~= 'list' then
              return redis.error_reply('AF_VALKEY_WRONG_TYPE_DEDUP_ORDER')
            end
            local function refreshTtl()
              if ttlMillis > 0 then
                redis.call('PEXPIRE', KEYS[1], ttlMillis)
                redis.call('PEXPIRE', KEYS[2], ttlMillis)
                redis.call('PEXPIRE', KEYS[3], ttlMillis)
              else
                redis.call('PERSIST', KEYS[1])
                redis.call('PERSIST', KEYS[2])
                redis.call('PERSIST', KEYS[3])
              end
            end
            local existing = redis.call('HGET', KEYS[2], operation)
            local messagesLength = redis.call('LLEN', KEYS[1])
            if existing then
              if existing == digest then
                refreshTtl()
                return {1, messagesLength}
              end
              return {2, messagesLength}
            end
            local dedupLength = redis.call('LLEN', KEYS[3])
            local retainedLength = math.min(messagesLength + messageCount, maxStored)
            for index = 1, messageCount do
              redis.call('RPUSH', KEYS[1], ARGV[6 + index])
            end
            redis.call('LTRIM', KEYS[1], -maxStored, -1)
            redis.call('HSET', KEYS[2], operation, digest)
            redis.call('RPUSH', KEYS[3], operation)
            dedupLength = dedupLength + 1
            while dedupLength > maxDedup do
              local oldest = redis.call('LPOP', KEYS[3])
              if oldest then
                redis.call('HDEL', KEYS[2], oldest)
              end
              dedupLength = dedupLength - 1
            end
            refreshTtl()
            return {0, retainedLength}
            """),
    LOAD("""
            local maxLoaded = tonumber(ARGV[1])
            local maxMessageBytes = tonumber(ARGV[2])
            local maxDocumentBytes = tonumber(ARGV[3])
            if #KEYS ~= 1 or #ARGV ~= 3
                or not maxLoaded or maxLoaded < 1 or maxLoaded > 10000
                or maxLoaded ~= math.floor(maxLoaded)
                or not maxMessageBytes or maxMessageBytes < 1 or maxMessageBytes > 16777216
                or maxMessageBytes ~= math.floor(maxMessageBytes)
                or not maxDocumentBytes or maxDocumentBytes < maxMessageBytes
                or maxDocumentBytes > 67108864
                or maxDocumentBytes ~= math.floor(maxDocumentBytes) then
              return redis.error_reply('AF_VALKEY_INVALID_LOAD_ARGS')
            end
            local function keyType(key)
              local result = redis.call('TYPE', key)
              if type(result) == 'table' then
                return result.ok
              end
              return result
            end
            local messagesType = keyType(KEYS[1])
            if messagesType ~= 'none' and messagesType ~= 'list' then
              return redis.error_reply('AF_VALKEY_WRONG_TYPE_MESSAGES')
            end
            local length = redis.call('LLEN', KEYS[1])
            local count = math.min(length, maxLoaded)
            local first = length - count
            local values = redis.call('LRANGE', KEYS[1], first, -1)
            if #values ~= count then
              return redis.error_reply('AF_VALKEY_INVALID_LIST_ENTRY')
            end
            local totalBytes = 0
            for index = 1, #values do
              local item = values[index]
              if type(item) ~= 'string' then
                return redis.error_reply('AF_VALKEY_INVALID_LIST_ENTRY')
              end
              local messageBytes = string.len(item)
              if messageBytes > maxMessageBytes then
                return redis.error_reply('AF_VALKEY_MESSAGE_BYTES')
              end
              totalBytes = totalBytes + messageBytes
              if totalBytes > maxDocumentBytes then
                return redis.error_reply('AF_VALKEY_DOCUMENT_BYTES')
              end
            end
            return values
            """),
    CLEAR("""
            return redis.call('DEL', KEYS[1], KEYS[2], KEYS[3])
            """);

    private final String source;

    ValkeyScript(String source) {
        this.source = source;
    }

    String source() {
        return source;
    }
}
