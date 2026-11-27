plugins {
	java
}

version = providers.gradleProperty("mod_version").get()
base.archivesName = "btainstaller"

// Plain Java 8 with no Minecraft or loader code, so the one jar loads on any Minecraft version and loader
// (Fabric, Quilt, Forge, NeoForge). "stubs" only exists to compile against the loaders' entry annotations/interfaces and is never packaged.
val stubs = sourceSets.create("stubs")
sourceSets.main { compileClasspath += stubs.output }

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
		vendor = JvmVendorSpec.ADOPTIUM
	}
}

tasks {
	withType<JavaCompile>().configureEach {
		options.encoding = "UTF-8"
		options.release = 8
	}
	processResources {
		val map = mapOf("version" to project.version.toString())
		inputs.properties(map)
		filesMatching("fabric.mod.json") { expand(map) }
	}
	jar {
		manifest { attributes("Bta-Version" to providers.gradleProperty("bta_version").get()) }
	}
}
