// Phase 0 spike build (architecture §13). Throwaway; mod id "cpx_spike".
plugins {
    id("net.fabricmc.fabric-loom-remap") version "1.18.4"
}

version = "0.0.0-spike"
group = "io.github.itznotneptune"

val loaderVersion = project.property("loader_version") as String
val fabricApiVersion = project.property("fabric_api_version") as String
val chatPatchesVersion = project.property("chatpatches_version") as String
val yaclVersion = project.property("yacl_version") as String
val cpVariant = (project.findProperty("cpVariant") as String?) ?: "real"

repositories {
    mavenCentral()
    maven("https://maven.quiltmc.org/repository/release/")
    maven("https://api.modrinth.com/maven") {
        content { includeGroup("maven.modrinth") }
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

fabricApi {
    configureTests {
        createSourceSet.set(true)
        modId.set("cpx_spike_test")
        enableGameTests.set(false)
        enableClientGameTests.set(true)
        eula.set(true)
        // Keep run/ between invocations: Chat Patches' logs/chatlog.json must survive for restore tests (V-02/V-03/V-27f).
        clearRunDirectory.set(false)
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // Chat Patches is compiled against always; its runtime presence is varied for V-35.
    modCompileOnly("maven.modrinth:chatpatches:$chatPatchesVersion")
    when (cpVariant) {
        "real" -> {
            modLocalRuntime("maven.modrinth:chatpatches:$chatPatchesVersion")
            modLocalRuntime("maven.modrinth:yacl:$yaclVersion")
            // YACL's nested jars aren't picked up by the dev runtime; provide them explicitly (dev environment only).
            localRuntime("org.quiltmc.parsers:json:0.2.1")
            localRuntime("org.quiltmc.parsers:gson:0.2.1")
            for (a in listOf("common-image", "common-io", "common-lang")) localRuntime("com.twelvemonkeys.common:$a:3.12.0")
            for (a in listOf("imageio-core", "imageio-metadata", "imageio-webp")) localRuntime("com.twelvemonkeys.imageio:$a:3.12.0")
        }
        "none" -> {}
        else -> localRuntime(files("stubs/chatpatches-$cpVariant.jar"))
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") { expand("version" to project.version) }
}
