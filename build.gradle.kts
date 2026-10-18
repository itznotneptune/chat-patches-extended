plugins {
    id("net.fabricmc.fabric-loom-remap") version "1.18.4"
}

version = project.property("mod_version") as String
group = project.property("maven_group") as String
base.archivesName.set("cpextended")

val minecraftVersion = project.property("minecraft_version") as String
val loaderVersion = project.property("loader_version") as String
val fabricApiVersion = project.property("fabric_api_version") as String
val chatPatchesVersion = project.property("chatpatches_version") as String
val yaclVersion = project.property("yacl_version") as String
val cpVariant = (project.findProperty("cpVariant") as String?) ?: "real"

// Fabric API modules the mod actually uses (P15, V-15). Each is declared in fabric.mod.json with a lower bound at the
// version tested here. Nothing else from Fabric API is on the main compile classpath.
val fabricModules = listOf("fabric-lifecycle-events-v1", "fabric-rendering-v1")

repositories {
    mavenCentral()
    maven("https://maven.quiltmc.org/repository/release/")
    maven("https://api.modrinth.com/maven") {
        content { includeGroup("maven.modrinth") }
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

fabricApi {
    configureTests {
        createSourceSet.set(true)
        modId.set("cpextended_test")
        enableGameTests.set(false)
        enableClientGameTests.set(true)
        eula.set(true)
        // Restore tests (V-02, V-03) need Chat Patches' logs/chatlog.json to survive between launches.
        clearRunDirectory.set(false)
    }
}

// Verification runs: -PcpxSysProps=a=b,c=d adds JVM system properties (e.g. cpextended.debug=true).
loom {
    runs.configureEach {
        val props = (project.findProperty("cpxSysProps") as String?)?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        for (p in props) vmArg("-D$p")
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    for (m in fabricModules) modImplementation(fabricApi.module(m, fabricApiVersion))

    // Chat Patches is never bundled (Q5): compile against it, and vary its runtime presence for V-35.
    modCompileOnly("maven.modrinth:chatpatches:$chatPatchesVersion")

    // Acceptance tests (gametest source set, never shipped) compile against the client gametest API.
    "modGametestImplementation"(fabricApi.module("fabric-client-gametest-api-v1", fabricApiVersion))

    // Dev runtime only. YACL (Chat Patches' dependency) requires the whole Fabric API, so the dev runtime has it.
    modLocalRuntime("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    when (cpVariant) {
        "real" -> {
            modLocalRuntime("maven.modrinth:chatpatches:$chatPatchesVersion")
            modLocalRuntime("maven.modrinth:yacl:$yaclVersion")
            // YACL's nested jars aren't loaded by the dev runtime; provide them explicitly.
            localRuntime("org.quiltmc.parsers:json:0.2.1")
            localRuntime("org.quiltmc.parsers:gson:0.2.1")
            for (a in listOf("common-image", "common-io", "common-lang")) localRuntime("com.twelvemonkeys.common:$a:3.12.0")
            for (a in listOf("imageio-core", "imageio-metadata", "imageio-webp")) localRuntime("com.twelvemonkeys.imageio:$a:3.12.0")
        }
        "none" -> {}
        else -> localRuntime(files("build/stubs/chatpatches-$cpVariant.jar"))
    }
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version,
        "loader_version" to loaderVersion,
        "lifecycle_version" to fabricApi.moduleVersion("fabric-lifecycle-events-v1", fabricApiVersion),
        "rendering_version" to fabricApi.moduleVersion("fabric-rendering-v1", fabricApiVersion),
    )
    inputs.properties(props)
    filesMatching("fabric.mod.json") { expand(props) }
}

tasks.jar {
    from("LICENSE", "COPYING")
}
