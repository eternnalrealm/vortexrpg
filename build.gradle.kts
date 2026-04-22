import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    id("com.gradleup.shadow") version "9.4.0"
}

group = "io.vortexcore"
version = "1.2.1-alpha"
description = "VortexRPG — A full-scale MMO engine by Eternal Realm™"

val pluginProps = mapOf(
    "name" to project.name,
    "version" to project.version,
    "description" to project.description
)

val spigotPluginYmlDir = layout.buildDirectory.dir("generated/spigot-plugin")

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/")
    maven("https://maven.citizensnpcs.co/repo")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://jitpack.io")
    mavenCentral()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${property("paperApiVersion")}")
    testCompileOnly("io.papermc.paper:paper-api:${property("paperApiVersion")}")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:${property("worldGuardVersion")}")
    compileOnly("net.citizensnpcs:citizensapi:${property("citizensApiVersion")}")
    compileOnly("me.clip:placeholderapi:${property("placeholderApiVersion")}")
    compileOnly("com.github.MilkBowl:VaultAPI:${property("vaultApiVersion")}") {
        exclude(group = "org.bukkit", module = "bukkit")
    }

    implementation("org.bstats:bstats-bukkit:${property("bstatsVersion")}")
    implementation("com.github.ben-manes.caffeine:caffeine:${property("caffeineVersion")}")
    implementation("com.fasterxml.jackson.core:jackson-databind:${property("jacksonVersion")}")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:${property("jacksonVersion")}")

    testImplementation(platform("org.junit:junit-bom:5.12.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    inputs.properties(pluginProps)
    filesMatching("plugin.yml") {
        expand(pluginProps)
    }
}

val generateSpigotPluginYml by tasks.registering {
    inputs.file("src/main/resources/plugin.yml")
    inputs.properties(pluginProps)
    outputs.dir(spigotPluginYmlDir)

    doLast {
        val source = file("src/main/resources/plugin.yml").readText(Charsets.UTF_8)
        val expanded = pluginProps.entries.fold(source) { content, (key, value) ->
            content.replace("\${" + key + "}", value.toString())
        }
        val filtered = expanded.lineSequence()
            .filterNot { it.trim() == "folia-supported: true" }
            .joinToString(System.lineSeparator()) + System.lineSeparator()

        val target = spigotPluginYmlDir.get().file("plugin.yml").asFile
        target.parentFile.mkdirs()
        target.writeText(filtered, Charsets.UTF_8)
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("org.bstats", "io.vortexcore.libs.bstats")
    relocate("com.fasterxml.jackson", "io.vortexcore.libs.jackson")
    relocate("com.github.benmanes.caffeine", "io.vortexcore.libs.caffeine")
}

val spigotJar by tasks.registering(ShadowJar::class) {
    group = "build"
    description = "Builds the Spigot-only VortexRPG jar without bundled Paper/Folia bridge classes."
    archiveClassifier.set("spigot")
    dependsOn(generateSpigotPluginYml)

    from(sourceSets.main.get().output) {
        exclude("plugin.yml")
        exclude("io/vortexcore/diagnostics/PaperTickListener.class")
        exclude("io/vortexcore/scheduling/FoliaOrchestrator.class")
        exclude("io/vortexcore/scheduling/PaperVortexTask.class")
    }
    from(spigotPluginYmlDir) {
        include("plugin.yml")
    }

    configurations = listOf(project.configurations.runtimeClasspath.get())

    relocate("org.bstats", "io.vortexcore.libs.bstats")
    relocate("com.fasterxml.jackson", "io.vortexcore.libs.jackson")
    relocate("com.github.benmanes.caffeine", "io.vortexcore.libs.caffeine")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
    dependsOn(spigotJar)
}
