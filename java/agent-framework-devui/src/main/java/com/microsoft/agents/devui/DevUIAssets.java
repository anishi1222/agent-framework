// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.devui;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class DevUIAssets {
    private static final int MAX_ASSET_BYTES = 256 * 1024;

    private static final int MAX_TOTAL_ASSET_BYTES = 512 * 1024;

    private static final String RESOURCE_ROOT = "/com/microsoft/agents/devui/assets/";

    private final Map<String, Asset> assets;

    private DevUIAssets(Map<String, Asset> assets) {
        this.assets = Map.copyOf(assets);
    }

    static DevUIAssets load() {
        Asset index = loadAsset("index.html", "text/html; charset=utf-8");
        Asset styles = loadAsset("app.css", "text/css; charset=utf-8");
        Asset script = loadAsset("app.js", "text/javascript; charset=utf-8");
        long total = (long) index.body().length + styles.body().length + script.body().length;
        if (total > MAX_TOTAL_ASSET_BYTES) {
            throw new IllegalStateException("Embedded developer UI assets exceed the total byte bound.");
        }
        LinkedHashMap<String, Asset> result = new LinkedHashMap<>();
        result.put("/devui", index);
        result.put("/devui/", index);
        result.put("/devui/index.html", index);
        result.put("/devui/app.css", styles);
        result.put("/devui/app.js", script);
        return new DevUIAssets(result);
    }

    Asset find(String path) {
        return assets.get(path);
    }

    private static Asset loadAsset(String name, String contentType) {
        String resource = RESOURCE_ROOT + name;
        try (InputStream input = DevUIAssets.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Embedded developer UI resource is missing: " + name);
            }
            byte[] bytes = input.readNBytes(MAX_ASSET_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_ASSET_BYTES) {
                throw new IllegalStateException("Embedded developer UI resource violates its byte bound: " + name);
            }
            return new Asset(contentType, bytes);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load embedded developer UI resource: " + name, exception);
        }
    }

    record Asset(String contentType, byte[] body) {
        Asset {
            Objects.requireNonNull(contentType, "contentType");
            body = Objects.requireNonNull(body, "body").clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }
}
