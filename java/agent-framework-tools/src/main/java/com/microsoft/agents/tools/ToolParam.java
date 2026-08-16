// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Supplies a stable JSON property name and description for a function parameter.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ToolParam {
    /**
     * Returns the stable JSON property name.
     *
     * @return non-blank property name
     */
    String value();

    /**
     * Returns the property description.
     *
     * @return property description
     */
    String description() default "";
}
