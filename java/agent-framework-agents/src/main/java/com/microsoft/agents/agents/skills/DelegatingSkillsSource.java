// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.agents.skills;

import java.util.Objects;

/** Provides a base class for source decorators. */
public abstract class DelegatingSkillsSource implements SkillsSource {
    private final SkillsSource innerSource;

    /**
     * Creates a source decorator.
     *
     * @param innerSource decorated source
     */
    protected DelegatingSkillsSource(SkillsSource innerSource) {
        this.innerSource = Objects.requireNonNull(innerSource, "innerSource");
    }

    /**
     * Returns the decorated source.
     *
     * @return inner source
     */
    public final SkillsSource innerSource() {
        return innerSource;
    }
}
