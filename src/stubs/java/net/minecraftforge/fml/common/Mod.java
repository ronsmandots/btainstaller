package net.minecraftforge.fml.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Compile-time stand-in for Forge's own {@code @Mod} (never packaged: Forge supplies the real one at runtime).
 * It has the attributes of both the 1.12 and the 1.13+ versions, so one annotated class is found by both.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mod {
	String value() default "";

	String modid() default "";

	String name() default "";

	String version() default "";

	String acceptableRemoteVersions() default "";

	boolean clientSideOnly() default false;
}
