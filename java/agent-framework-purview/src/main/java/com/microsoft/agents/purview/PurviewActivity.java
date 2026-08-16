// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.purview;

/** Identifies a Purview-protected user activity. */
public enum PurviewActivity {
    /** Text enters the application, such as a prompt. */
    UPLOAD_TEXT("uploadText"),
    /** Text leaves the application, such as a model response. */
    DOWNLOAD_TEXT("downloadText"),
    /** A file enters the application. */
    UPLOAD_FILE("uploadFile"),
    /** A file leaves the application. */
    DOWNLOAD_FILE("downloadFile");

    private final String graphValue;

    PurviewActivity(String graphValue) {
        this.graphValue = graphValue;
    }

    /** Returns the Microsoft Graph activity value. */
    public String graphValue() {
        return graphValue;
    }

    /** Resolves a Microsoft Graph activity value. */
    public static PurviewActivity fromGraphValue(String value) {
        for (PurviewActivity activity : values()) {
            if (activity.graphValue.equals(value)) {
                return activity;
            }
        }
        throw new IllegalArgumentException("Unknown Purview activity: " + value);
    }
}
