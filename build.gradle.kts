plugins {
    alias(libs.plugins.fabric.loom)
}

base {
    archivesName = properties["archives_base_name"] as String
    version = libs.versions.mod.version.get()
    group = properties["maven_group"] as String
}

repositories {
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }
    maven {
        name = "meteor-maven-snapshots"
        url = uri("https://maven.meteordev.org/snapshots")
    }
}

loom {
    accessWidenerPath = file("src/main/resources/dw-addons.accesswidener")
}

dependencies {
    minecraft(libs.minecraft)
    mappings("${libs.yarn.mappings.get()}:v2")
    modImplementation(libs.fabric.loader)
    modImplementation(libs.meteor.client)

    // Baritone API (used by ElytraFlyPlusPlus' obstacle passer). Baritone is a separately
    // installed mod, so we compile against its API only and never bundle it. The jar ships
    // intermediary-mapped, so it must go through a `mod*` configuration for Loom to remap it
    // to the project's Yarn mappings — a plain `compileOnly(files(...))` would NOT remap.
    modCompileOnly(files("libs/baritone-api-fabric-1.15.0.jar"))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.jdk.get().toInt()))
    }
}

fun toMinecraftCompat(version: String): String {
    val parts = version.split(".")
    return "~${parts[0]}.${parts[1]}"
}

tasks {
    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "minecraft_version" to toMinecraftCompat(libs.versions.minecraft.get()),
            "jdk_version" to libs.versions.jdk.get(),
        )
        inputs.properties(propertyMap)
        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }
    
    jar {
        inputs.property("archivesName", project.base.archivesName.get())
        from("LICENSE") {
            rename { "${it}_${inputs.properties["archivesName"]}" }
        }
    }
    
    withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(
            listOf(
                "-Xlint:deprecation",
                "-Xlint:unchecked"
            )
        )
    }
}
