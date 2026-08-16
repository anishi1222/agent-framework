// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a public API as experimental and subject to incompatible change or removal.
 *
 * <p>The feature identifier is runtime-visible through {@link FeatureStages}. Java annotations do
 * not intercept arbitrary calls or construction, so frameworks and applications that want a
 * runtime warning should invoke {@link FeatureStages#warnOnce(java.lang.reflect.AnnotatedElement,
 * java.util.function.Consumer)} at their entry point.
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
public @interface Experimental {
    /**
     * Returns the stage-scoped feature identifier.
     *
     * @return non-blank feature identifier
     */
    String value();
}
