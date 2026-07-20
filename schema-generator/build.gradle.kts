import groovy.json.JsonSlurper
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    base
}

val sourceRevision = providers.gradleProperty("vanillaMcdocCommit").get()
val expectedArchiveSha256 = providers.gradleProperty("vanillaMcdocSha256").get().lowercase()
val archiveFile = layout.buildDirectory.file("downloads/vanilla-mcdoc-$sourceRevision.zip")
val sourceDirectory = layout.buildDirectory.dir("source/$sourceRevision")
val generatedSchema = layout.buildDirectory.file("generated/vanilla-nbt-schemas.json")
val canonicalSchema = rootProject.layout.projectDirectory.file("schema/vanilla/vanilla-nbt-schemas.json")
val generatorScript = rootProject.layout.projectDirectory.file("tools/generate-vanilla-nbt-schemas.mjs")
val npmExecutable = if (System.getProperty("os.name").lowercase().contains("windows")) "npm.cmd" else "npm"
val nodeExecutable = if (System.getProperty("os.name").lowercase().contains("windows")) "node.exe" else "node"

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun validateSchema(file: File) {
    require(file.isFile && file.length() > 0L) { "Missing generated vanilla NBT schema: ${file.absolutePath}" }
    @Suppress("UNCHECKED_CAST")
    val root = JsonSlurper().parse(file) as? Map<String, Any?>
        ?: error("Vanilla NBT schema root must be an object: ${file.absolutePath}")
    require(root["format"] == "mcdoc-nbt-schema-v3") { "Unsupported vanilla NBT schema format: ${root["format"]}" }
    require(root["sourceRevision"] == sourceRevision) {
        "Vanilla NBT schema revision ${root["sourceRevision"]} does not match pinned revision $sourceRevision"
    }
    require((root["fileCount"] as? Number)?.toInt()?.let { it >= 100 } == true) {
        "Vanilla NBT schema must contain at least 100 source files; found ${root["fileCount"]}"
    }
    val schemaSets = root["schemaSets"] as? Map<*, *> ?: error("Vanilla NBT schema is missing schemaSets")
    val vanilla = schemaSets["vanilla"] as? Map<*, *> ?: error("Vanilla NBT schema is missing schemaSets.vanilla")
    val entitySchemas = vanilla["entitySchemas"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val blockEntitySchemas = vanilla["blockEntitySchemas"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val blockMappings = vanilla["blockToBlockEntity"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
    require(entitySchemas.size >= 100) { "Vanilla NBT schema has only ${entitySchemas.size} entity schemas" }
    require(blockEntitySchemas.size >= 40) { "Vanilla NBT schema has only ${blockEntitySchemas.size} block-entity schemas" }
    require(blockMappings.size >= 100) { "Vanilla NBT schema has only ${blockMappings.size} block mappings" }
    val versions = root["versions"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
    require(versions.size >= 19) { "Vanilla NBT schema has only ${versions.size} version mappings" }
}

val downloadVanillaMcdoc = tasks.register("downloadVanillaMcdoc") {
    group = "vanilla schema"
    description = "Downloads the pinned vanilla-mcdoc source archive and verifies its SHA-256."
    inputs.property("sourceRevision", sourceRevision)
    inputs.property("archiveSha256", expectedArchiveSha256)
    outputs.file(archiveFile)

    doLast {
        val target = archiveFile.get().asFile
        if (target.isFile && sha256(target) == expectedArchiveSha256) return@doLast
        target.parentFile.mkdirs()
        val temporary = target.resolveSibling("${target.name}.part")
        temporary.delete()
        try {
            val connection =
                URI("https://github.com/SpyglassMC/vanilla-mcdoc/archive/$sourceRevision.zip")
                    .toURL()
                    .openConnection()
                    .apply {
                        connectTimeout = 30_000
                        readTimeout = 60_000
                        setRequestProperty("User-Agent", "DatapackSandbox-schema-generator/$version")
                    }
            connection
                .getInputStream()
                .use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }
            val actual = sha256(temporary)
            require(actual == expectedArchiveSha256) {
                "vanilla-mcdoc archive checksum mismatch: expected $expectedArchiveSha256, got $actual"
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }
}

val extractVanillaMcdoc = tasks.register<Sync>("extractVanillaMcdoc") {
    group = "vanilla schema"
    description = "Extracts mcdoc sources from the verified pinned archive."
    dependsOn(downloadVanillaMcdoc)
    from(archiveFile.map { zipTree(it) }) {
        include("**/*.mcdoc")
    }
    into(sourceDirectory)
}

val npmCi = tasks.register<Exec>("npmCi") {
    group = "vanilla schema"
    description = "Installs the locked Node dependencies used by the mcdoc generator."
    workingDir(rootProject.layout.projectDirectory)
    inputs.file(rootProject.layout.projectDirectory.file("package.json"))
    inputs.file(rootProject.layout.projectDirectory.file("package-lock.json"))
    outputs.file(rootProject.layout.projectDirectory.file("node_modules/.package-lock.json"))
    commandLine(npmExecutable, "ci", "--no-audit", "--no-fund")
}

val generateVanillaNbtSchemas = tasks.register<Exec>("generateVanillaNbtSchemas") {
    group = "vanilla schema"
    description = "Generates a compact NBT schema from the pinned vanilla-mcdoc revision."
    dependsOn(extractVanillaMcdoc, npmCi)
    inputs.dir(sourceDirectory)
    inputs.file(generatorScript)
    inputs.property("sourceRevision", sourceRevision)
    outputs.file(generatedSchema)
    doFirst {
        generatedSchema.get().asFile.parentFile.mkdirs()
    }
    commandLine(
        nodeExecutable,
        generatorScript.asFile.absolutePath,
        sourceDirectory.get().asFile.absolutePath,
        generatedSchema.get().asFile.absolutePath,
        sourceRevision,
    )
}

val validateGeneratedVanillaNbtSchemas = tasks.register("validateGeneratedVanillaNbtSchemas") {
    group = "verification"
    description = "Rejects empty or incomplete generated vanilla NBT schemas."
    dependsOn(generateVanillaNbtSchemas)
    inputs.file(generatedSchema)
    doLast { validateSchema(generatedSchema.get().asFile) }
}

tasks.register<Copy>("updateVanillaNbtSchemas") {
    group = "vanilla schema"
    description = "Updates the checked-in runtime schema from the reproducible generated output."
    dependsOn(validateGeneratedVanillaNbtSchemas)
    from(generatedSchema)
    into(canonicalSchema.asFile.parentFile)
}

val verifyVanillaNbtSchemas = tasks.register("verifyVanillaNbtSchemas") {
    group = "verification"
    description = "Verifies that the checked-in runtime schema is complete and matches the pinned revision."
    inputs.file(canonicalSchema)
    doLast { validateSchema(canonicalSchema.asFile) }
}

val checkGeneratedVanillaNbtSchemas = tasks.register("checkGeneratedVanillaNbtSchemas") {
    group = "verification"
    description = "Regenerates the schema and verifies byte-for-byte reproducibility."
    dependsOn(validateGeneratedVanillaNbtSchemas, verifyVanillaNbtSchemas)
    doLast {
        val generated = generatedSchema.get().asFile.readBytes()
        val canonical = canonicalSchema.asFile.readBytes()
        require(generated.contentEquals(canonical)) {
            "Checked-in vanilla NBT schema is stale. Run :schema-generator:updateVanillaNbtSchemas."
        }
    }
}

tasks.named("check") {
    dependsOn(checkGeneratedVanillaNbtSchemas)
}
