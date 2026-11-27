package cpw.mods.fml.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Compile-time stand-in for the {@code @Mod} of Forge 1.7.10 and older (never packaged). */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mod {
	String modid();

	String name() default "";

	String version() default "";

	String acceptableRemoteVersions() default "";

	boolean clientSideOnly() default false;
}
