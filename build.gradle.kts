plugins {
    alias(libs.plugins.fabric.loom)
}

val archivesBaseName = providers.gradleProperty("archives_base_name").get()
val jdkVersion = libs.versions.jdk.get().toInt()

base {
    archivesName = archivesBaseName
    version = libs.versions.mod.version.get()
    group = providers.gradleProperty("maven_group").get()
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
    mappings(variantOf(libs.yarn) { classifier("v2") })
    modImplementation(libs.fabric.loader)
    modImplementation(libs.meteor.client)
}

loom {
    accessWidenerPath = file("src/main/resources/coyduck.accesswidener")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(jdkVersion)
    }
}

tasks {
    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "mc_version" to libs.versions.minecraft.get(),
            "jdk_version" to jdkVersion
        )

        inputs.properties(propertyMap)
        filteringCharset = "UTF-8"

        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    jar {
        inputs.property("archivesName", archivesBaseName)

        from("LICENSE") {
            rename("LICENSE", "LICENSE_$archivesBaseName")
        }
    }

    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = jdkVersion
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
    }
}
