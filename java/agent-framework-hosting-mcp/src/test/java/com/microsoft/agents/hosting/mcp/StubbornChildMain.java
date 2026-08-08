// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.hosting.mcp;

/** Keeps a descendant alive through graceful termination until forcibly stopped. */
public final class StubbornChildMain {
    private StubbornChildMain() {}

    /**
     * Blocks until the process is forcibly terminated.
     *
     * @param arguments ignored
     * @throws InterruptedException when interrupted
     */
    public static void main(String[] arguments) throws InterruptedException {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }));
        new java.util.concurrent.CountDownLatch(1).await();
    }
}
