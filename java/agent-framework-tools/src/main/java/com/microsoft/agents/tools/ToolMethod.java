// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.agents.tools;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a public Java method as a function tool.
 *
 * <p>Runtime discovery inspects only public methods on public classes and uses public method handles.
 * It never makes inaccessible members accessible.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ToolMethod {
    /**
     * Returns the explicit tool name, or an empty string to use the method name.
     *
     * @return tool name override
     */
    String name() default "";

    /**
     * Returns the tool description.
     *
     * @return tool description
     */
    String description() default "";

    /**
     * Returns the approval policy.
     *
     * @return approval policy
     */
    ToolApprovalMode approvalMode() default ToolApprovalMode.NEVER_REQUIRE;
}
