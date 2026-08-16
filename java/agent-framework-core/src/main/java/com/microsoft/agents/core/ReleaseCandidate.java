// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a public API as a release candidate that may receive minor refinements before general
 * availability.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({
    ElementType.TYPE,
    ElementType.METHOD,
    ElementType.CONSTRUCTOR,
    ElementType.FIELD,
    ElementType.PACKAGE,
    ElementType.ANNOTATION_TYPE,
    ElementType.RECORD_COMPONENT
})
public @interface ReleaseCandidate {
    /**
     * Returns the stage-scoped feature identifier.
     *
     * @return non-blank feature identifier
     */
    String value();
}
