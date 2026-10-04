import groovy.json.JsonSlurper
import java.net.URI
import java.security.MessageDigest

plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "dev"
version = providers.gradleProperty("version").get()

val minecraftVersion = providers.gradleProperty("minecraft_version").get()
val serverSoftware = providers.gradleProperty("server_software").get()
val minecraftRepo = layout.projectDirectory.dir("agent_sources/minecraft-repo")

val commitCount = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.get().trim()

val commitHash = providers.exec {
    commandLine("git", "rev-parse", "--short=7", "HEAD")
}.standardOutput.asText.get().trim()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven {
        name = "luck-repo"
        url = uri("https://repo.lucko.me/")
        content {
            includeModule("me.lucko", "spark-api")
        }
    }
    maven { url = uri("agent_sources/minecraft-repo") }
}

val minecraft = configurations.create("minecraft") {
    description = "The Mojang-mapped server artifact that paper-api is compiled against."
    isCanBeConsumed = false
}

val providedApi = configurations.create("providedApi") {
    description = "Provided-scope APIs; the server supplies them at runtime."
    isCanBeConsumed = false
    isCanBeResolved = false
}

configurations {
    compileClasspath { extendsFrom(providedApi) }
    testCompileClasspath { extendsFrom(providedApi) }
    testRuntimeClasspath { extendsFrom(providedApi) }
}

dependencies {
    providedApi("io.papermc.paper:paper-api:" + providers.gradleProperty("paper_api_version").get())
    providedApi("me.lucko:spark-api:" + providers.gradleProperty("spark_api_version").get())

    minecraft("com.mojang:minecraft:$minecraftVersion")

    testImplementation(platform("org.junit:junit-bom:" + providers.gradleProperty("junit_bom_version").get()))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}


tasks {
    compileJava {
        options.encoding = "UTF-8"
    }
    compileTestJava {
        options.encoding = "UTF-8"
    }
    test {
        useJUnitPlatform()
    }
    processResources {
        filteringCharset = "UTF-8"
        val expansionVersion = version.toString()
        filesMatching("paper-plugin.yml") {
            expand("project" to mapOf("version" to expansionVersion))
        }
        from(rootProject.file("LICENSE.txt")) {
            into("META-INF")
        }
        from(rootProject.file("THIRD_PARTY_NOTICES.md")) {
            into("META-INF")
        }
    }
    jar {
        enabled = false
    }
    shadowJar {
        archiveClassifier = ""
        archiveFileName =
            "ReplenishPlusPlus-${project.version}+build.${commitCount}-${commitHash}-mc${minecraftVersion}-${serverSoftware}.jar"
        minimize()
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
}

val sourcesJarView = configurations.compileClasspath.map { config ->
    config.incoming.artifactView {
        withVariantReselection()
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION))
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
            attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType.SOURCES))
        }
        isLenient = true
    }.files
}

tasks.register<Copy>("agentSources") {
    group = "build"
    description = "Unpacks compile-classpath dependency sources into agent_sources/src."
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    from(sourcesJarView.map { jars -> jars.map { zipTree(it) } })
    include("**/*.java")
    into(layout.projectDirectory.dir("agent_sources/src"))
}

tasks.register("installMinecraftArtifact") {
    group = "build"
    description = "Downloads com.mojang:minecraft:<minecraft_version> from Mojang and installs it into minecraft-repo."
    val repoDir = minecraftRepo
    val mv = minecraftVersion
    val metaUrl = "https://raw.githubusercontent.com/PrismLauncher/meta-launcher/master/net.minecraft/$mv.json"
    outputs.dir(repoDir)
    doLast {
        val meta = JsonSlurper().parseText(URI(metaUrl).toURL().openStream().use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        }) as Map<*, *>
        val artifact = (((meta["mainJar"] as Map<*, *>)["downloads"] as Map<*, *>)["artifact"] as Map<*, *>)
        val jarUrl = artifact["url"].toString()
        val expectedSha1 = artifact["sha1"].toString()
        val versionDir = repoDir.dir("com/mojang/minecraft/$mv").asFile
        versionDir.mkdirs()
        val jarFile = versionDir.resolve("minecraft-$mv.jar")
        URI(jarUrl).toURL().openStream().use { input ->
            jarFile.outputStream().use { output -> input.copyTo(output) }
        }
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(jarFile.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }
        if (digest != expectedSha1) {
            jarFile.delete()
            throw GradleException("Downloaded minecraft-$mv.jar failed its SHA-1 check ($digest != $expectedSha1).")
        }
        versionDir.resolve("minecraft-$mv.pom").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.mojang</groupId>
              <artifactId>minecraft</artifactId>
              <version>$mv</version>
              <packaging>jar</packaging>
            </project>
            """.trimIndent()
        )
        println("Installed com.mojang:minecraft:$mv into minecraft-repo (SHA-1 verified).")
    }
}
