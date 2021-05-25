package com.kt.codegen;


import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/**
 * Specifies that a new class should be generated off the annotated class. The target class name is specified
 * by {@link #target()} and string replacements (plain or regex) are specified by {@link #replace()}.
 */
@Repeatable(Transforms.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface Transform {
    /**
     * The simple (i.e., package-stripped) target class name to generate.
     *
     * @return The simple (i.e., package-stripped) target class name to generate.
     */
    String target();

    /**
     * An optional list of string replacements (plain or regex) to apply on top of the generic type replacements.
     * This can be useful to e.g. replace generic array construction of generic type {@code T1} with
     * primitive array construction:
     * <br><br>
     *
     * {@code @Replace(from = "(T1[]) new Object[", to = "new double[" }
     *
     * @return An optional list of string replacements (plain or regex) to apply on top of the generic type replacements.
     */
    Replace[] replace() default {};
}
