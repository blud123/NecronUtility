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
dependencies {
    minecraft(libs.minecraft)
    mappings(loom.officialMojangMappings())
    modImplementation(libs.fabric.loader)
    modImplementation(libs.meteor.client)
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
                "--enable-preview",
                "-Xlint:deprecation",
                "-Xlint:unchecked"
            )
        )
    }
}
