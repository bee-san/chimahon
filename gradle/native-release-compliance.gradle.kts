import groovy.json.JsonSlurper
import org.gradle.api.artifacts.ExternalModuleDependency
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

val nativeSourceManifest = rootProject.file("docs/native-source-manifest.json")
val statsReleaseValidationManifest =
    rootProject.file("docs/immersion-stats-release-validation.json")
val nativeLibraryMetadataDirectory = rootProject.file("aboutlibraries/config/libraries")
val nativeNoticeLicense =
    rootProject.file("aboutlibraries/config/licenses/chimahon-native-notices.json")
val applicationSourceRepository = "https://github.com/bee-san/chimahon"
val nativeArtifactCoordinates = setOf(
    "com.github.bee-san:ffmpeg-kit:1.18-chimahon.2",
    "com.github.bee-san:aniyomi-mpv-lib:1.18.n-chimahon.4",
)
val nativeSourceRepositories = mapOf(
    "com.github.bee-san:ffmpeg-kit:1.18-chimahon.2" to
        "https://github.com/bee-san/ffmpeg-kit",
    "com.github.bee-san:aniyomi-mpv-lib:1.18.n-chimahon.4" to
        "https://github.com/bee-san/aniyomi-mpv-lib",
)
val nativeLicenseIdentifiers = mapOf(
    "com.github.bee-san:ffmpeg-kit:1.18-chimahon.2" to
        setOf("LGPL-3.0-or-later", "GPL-3.0-or-later"),
    "com.github.bee-san:aniyomi-mpv-lib:1.18.n-chimahon.4" to
        setOf("MIT", "GPL-3.0-or-later"),
)
val nativeAarConfigurations = nativeArtifactCoordinates.associateWith { coordinate ->
    val dependency = dependencies.create(coordinate)
    check(dependency is ExternalModuleDependency) {
        "Native compliance coordinate is not an external module: $coordinate"
    }
    dependency.isTransitive = false
    configurations.detachedConfiguration(dependency).apply {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
}
val statsValidationKeys = setOf(
    "knownnessTimelinePerformance",
    "captureAndQueryPerformance",
    "recorderWakeAndWriteBehavior",
    "databaseAndRawTextGrowth",
    "talkBack",
    "largeTextAndDisplay",
    "reducedMotion",
    "visualConfigurationMatrix",
    "supportedVersionUpgrades",
    "releaseMinSdkAndMigration",
    "novelAcceptance",
    "mangaOcrAcceptance",
    "videoAcceptance",
    "ankiAcceptance",
    "goalsAcceptance",
    "privacyAndDeletionAcceptance",
    "backupAndMultiDeviceAcceptance",
)

fun Any?.requiredObject(context: String): Map<*, *> {
    check(this is Map<*, *>) { "$context must be a JSON object." }
    return this
}

fun Map<*, *>.requiredString(key: String, context: String): String {
    val value = this[key]
    check(value is String && value.isNotBlank()) {
        "$context.$key must be a non-empty string."
    }
    return value
}

fun requireLowerHex(value: String, length: Int, context: String) {
    check(Regex("[0-9a-f]{$length}").matches(value)) {
        "$context must be a lowercase $length-character hexadecimal value."
    }
}

fun requireHttpsUrl(value: String, context: String) {
    val uri = runCatching { URI(value) }.getOrNull()
    check(
        uri != null &&
            uri.scheme == "https" &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.fragment == null,
    ) {
        "$context must be an HTTPS URL without credentials or a fragment."
    }
}

data class RemoteAnnotatedTag(
    val objectId: String,
    val commitId: String,
)

fun resolveRemoteAnnotatedTag(
    repository: String,
    tag: String,
    context: String,
): RemoteAnnotatedTag {
    check(Regex("[A-Za-z0-9._-]+").matches(tag)) {
        "$context tag contains unsupported characters."
    }
    val tagRef = "refs/tags/$tag"
    val peeledTagRef = "$tagRef^{}"
    val tagProcess = ProcessBuilder(
        "git",
        "ls-remote",
        "$repository.git",
        tagRef,
        peeledTagRef,
    )
        .redirectErrorStream(true)
        .start()
    val tagOutput = tagProcess.inputStream.bufferedReader().use { it.readText() }
    check(tagProcess.waitFor() == 0) {
        "$context tag lookup failed: ${tagOutput.trim()}"
    }
    val remoteRefs = tagOutput.lineSequence()
        .filter { it.isNotBlank() }
        .associate { line ->
            val parts = line.split('\t', limit = 2)
            check(parts.size == 2) {
                "$context tag lookup returned malformed output."
            }
            parts[1] to parts[0]
        }
    val objectId = remoteRefs[tagRef]
    val commitId = remoteRefs[peeledTagRef]
    check(objectId != null && commitId != null && objectId != commitId) {
        "$context must identify an annotated remote tag."
    }
    return RemoteAnnotatedTag(objectId, commitId)
}

fun currentGitHead(): String {
    val process = ProcessBuilder("git", "rev-parse", "HEAD")
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    check(process.waitFor() == 0 && Regex("[0-9a-f]{40}").matches(output)) {
        "Could not resolve the checked-out Git HEAD: $output"
    }
    return output
}

fun java.io.File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") {
        String.format(Locale.ROOT, "%02x", it.toInt() and 0xff)
    }
}

fun nativeLibraryMetadataFile(coordinate: String) =
    nativeLibraryMetadataDirectory.resolve(
        when (coordinate.substringBeforeLast(':')) {
            "com.github.bee-san:ffmpeg-kit" -> "ffmpeg-kit.json"
            "com.github.bee-san:aniyomi-mpv-lib" -> "aniyomi-mpv-lib.json"
            else -> error("No native AboutLibraries metadata mapping for $coordinate")
        },
    )

val verifyNativeComplianceMetadata by tasks.registering {
    group = "verification"
    description = "Verifies native AAR identities and packaged license metadata."
    inputs.files(
        nativeSourceManifest,
        nativeLibraryMetadataDirectory,
        nativeNoticeLicense,
        nativeAarConfigurations.values,
    )

    doLast {
        val manifest =
            JsonSlurper().parse(nativeSourceManifest).requiredObject("Native source manifest")
        check(manifest["schemaVersion"] == 3) {
            "Native source manifest schemaVersion must be 3."
        }
        check(manifest["releaseGate"] in setOf("blocked", "verified")) {
            "Native source manifest releaseGate must be blocked or verified."
        }
        val artifactEntries = (manifest["artifacts"] as? List<*>)
            ?.mapIndexed { index, value ->
                value.requiredObject("Native source manifest artifacts[$index]")
            }
            .orEmpty()
        val entriesByCoordinate = artifactEntries.associateBy {
            it.requiredString("coordinate", "Native artifact")
        }
        check(entriesByCoordinate.size == artifactEntries.size) {
            "Native source manifest contains a duplicate artifact coordinate."
        }
        check(entriesByCoordinate.keys == nativeArtifactCoordinates) {
            "Native source manifest coordinates must match the resolved native AARs."
        }

        entriesByCoordinate.forEach { (coordinate, entry) ->
            val artifactContext = "Native artifact $coordinate"
            val binarySha256 = entry.requiredString("binarySha256", artifactContext)
            requireLowerHex(binarySha256, 64, "$artifactContext.binarySha256")
            val sourceCommit = entry.requiredString("sourceCommit", artifactContext)
            requireLowerHex(sourceCommit, 40, "$artifactContext.sourceCommit")
            val sourceTag = entry.requiredString("sourceTag", artifactContext)
            val sourceTagObject = entry.requiredString("sourceTagObject", artifactContext)
            requireLowerHex(sourceTagObject, 40, "$artifactContext.sourceTagObject")
            val sourceRevisionUrl = entry.requiredString("sourceRevisionUrl", artifactContext)
            requireHttpsUrl(sourceRevisionUrl, "$artifactContext.sourceRevisionUrl")
            val sourceRepository = checkNotNull(nativeSourceRepositories[coordinate])
            check(sourceRevisionUrl == "$sourceRepository/tree/$sourceCommit") {
                "$artifactContext.sourceRevisionUrl must identify sourceCommit."
            }
            val remoteTag =
                resolveRemoteAnnotatedTag(sourceRepository, sourceTag, artifactContext)
            check(remoteTag.objectId == sourceTagObject) {
                "$artifactContext sourceTagObject does not match the remote tag object."
            }
            check(remoteTag.commitId == sourceCommit) {
                "$artifactContext sourceCommit is not peeled from sourceTag."
            }
            val toolchain =
                entry["toolchain"].requiredObject("$artifactContext.toolchain")
            listOf(
                "androidNdk",
                "androidSdkPlatform",
                "androidBuildTools",
                "ffmpeg",
                "mpv",
            ).forEach { key ->
                toolchain.requiredString(key, "$artifactContext.toolchain")
            }

            val configuration = checkNotNull(nativeAarConfigurations[coordinate])
            val resolvedArtifacts =
                configuration.resolvedConfiguration.resolvedArtifacts
            check(resolvedArtifacts.size == 1) {
                "$artifactContext must resolve to exactly one direct artifact."
            }
            val resolvedArtifact = resolvedArtifacts.single()
            val moduleVersion = resolvedArtifact.moduleVersion.id
            val resolvedCoordinate =
                "${moduleVersion.group}:${resolvedArtifact.name}:${moduleVersion.version}"
            check(resolvedCoordinate == coordinate) {
                "$artifactContext resolved as $resolvedCoordinate."
            }
            check(resolvedArtifact.extension.equals("aar", ignoreCase = true)) {
                "$artifactContext must resolve to an AAR."
            }
            val resolvedSha256 = resolvedArtifact.file.sha256()
            check(resolvedSha256 == binarySha256) {
                "$artifactContext resolved AAR SHA-256 $resolvedSha256 does not match."
            }

            val libraryFile = nativeLibraryMetadataFile(coordinate)
            val library = JsonSlurper().parse(libraryFile)
                .requiredObject("AboutLibraries ${libraryFile.name}")
            val libraryId = coordinate.substringBeforeLast(':')
            val artifactVersion = coordinate.substringAfterLast(':')
            check(
                library["uniqueId"] == libraryId &&
                    library["artifactVersion"] == artifactVersion,
            ) {
                "${libraryFile.name} must identify exactly $coordinate."
            }
            check(library["website"] == sourceRevisionUrl) {
                "${libraryFile.name} website must identify the exact source revision."
            }
            val licenses = (library["licenses"] as? List<*>)
                ?.filterIsInstance<String>()
                ?.toSet()
                .orEmpty()
            check(licenses == nativeLicenseIdentifiers[coordinate]) {
                "${libraryFile.name} must disclose the exact license identifiers."
            }
        }

        val notice = JsonSlurper().parse(nativeNoticeLicense)
            .requiredObject("Native notice license")
        val noticeContent = notice["content"] as? String
        check(
            notice["hash"] == "Chimahon-Native-Notices" &&
                noticeContent?.contains(
                    "Licensed under GNU LGPL version 3 or, at your option, any later version.",
                ) == true &&
                noticeContent.contains(
                    "Copyright (c) 2000-2024 the FFmpeg developers",
                ) &&
                noticeContent.contains("Copyright (c) 2016 Ilya Zhuravlev") &&
                noticeContent.contains("Copyright (c) 2016 sfan5 <sfan5@live.de>"),
        ) {
            "The packaged native notice must disclose both native payloads."
        }
        val noticeLibraryFile =
            nativeLibraryMetadataDirectory.resolve("chimahon-native-notices.json")
        val noticeLibrary = JsonSlurper().parse(noticeLibraryFile)
            .requiredObject("AboutLibraries ${noticeLibraryFile.name}")
        check(
            noticeLibrary["uniqueId"] == "app.chimahon:native-notices" &&
                noticeLibrary["licenses"] == listOf("Chimahon-Native-Notices"),
        ) {
            "The packaged native notice must have a visible AboutLibraries entry."
        }
    }
}

val verifyNativeSourceCompliance by tasks.registering {
    group = "verification"
    description = "Requires exact Corresponding Source for native distributions."
    dependsOn(verifyNativeComplianceMetadata)
    inputs.files(nativeSourceManifest, nativeLibraryMetadataDirectory)

    doLast {
        val manifest =
            JsonSlurper().parse(nativeSourceManifest).requiredObject("Native source manifest")
        check(manifest["releaseGate"] == "verified") {
            "Native Corresponding Source is not verified. See docs/native-distribution-compliance.md."
        }

        val manifestContext = "Native source manifest"
        val applicationSourceTag =
            manifest.requiredString("applicationSourceTag", manifestContext)
        val applicationSourceRevisionUrl = manifest.requiredString(
            "applicationSourceRevisionUrl",
            manifestContext,
        )
        requireHttpsUrl(
            applicationSourceRevisionUrl,
            "$manifestContext.applicationSourceRevisionUrl",
        )
        check(
            applicationSourceRevisionUrl ==
                "$applicationSourceRepository/tree/$applicationSourceTag",
        ) {
            "applicationSourceRevisionUrl must identify applicationSourceTag."
        }
        val applicationSourceArchiveUrl = manifest.requiredString(
            "applicationSourceArchiveUrl",
            manifestContext,
        )
        requireHttpsUrl(
            applicationSourceArchiveUrl,
            "$manifestContext.applicationSourceArchiveUrl",
        )
        check(
            applicationSourceArchiveUrl ==
                "$applicationSourceRepository/releases/download/$applicationSourceTag/" +
                "chimahon-source-$applicationSourceTag.tar.gz",
        ) {
            "applicationSourceArchiveUrl must identify the release source archive."
        }

        val artifacts = (manifest["artifacts"] as List<*>).map {
            it.requiredObject("Native source manifest artifact")
        }
        artifacts.forEach { artifact ->
            val coordinate = artifact.requiredString("coordinate", "Native artifact")
            val artifactContext = "Native artifact $coordinate"
            val sourceArchiveUrl =
                artifact.requiredString("sourceArchiveUrl", artifactContext)
            requireHttpsUrl(sourceArchiveUrl, "$artifactContext.sourceArchiveUrl")
            val sourceArchiveSha256 =
                artifact.requiredString("sourceArchiveSha256", artifactContext)
            requireLowerHex(
                sourceArchiveSha256,
                64,
                "$artifactContext.sourceArchiveSha256",
            )
            val toolchain =
                artifact["toolchain"].requiredObject("$artifactContext.toolchain")
            val toolchainArchiveUrl =
                toolchain.requiredString("archiveUrl", "$artifactContext.toolchain")
            requireHttpsUrl(
                toolchainArchiveUrl,
                "$artifactContext.toolchain.archiveUrl",
            )
            val toolchainArchiveSha256 =
                toolchain.requiredString("archiveSha256", "$artifactContext.toolchain")
            requireLowerHex(
                toolchainArchiveSha256,
                64,
                "$artifactContext.toolchain.archiveSha256",
            )

            val libraryFile = nativeLibraryMetadataFile(coordinate)
            val library = JsonSlurper().parse(libraryFile)
                .requiredObject("AboutLibraries ${libraryFile.name}")
            val funding = (library["funding"] as? List<*>)
                ?.map { it.requiredObject("${libraryFile.name} funding entry") }
                .orEmpty()
            val correspondingSourceLinks = funding.filter {
                it["platform"] == "Corresponding Source"
            }
            check(
                correspondingSourceLinks.size == 1 &&
                    correspondingSourceLinks.single()["url"] == sourceArchiveUrl,
            ) {
                "${libraryFile.name} must expose its exact Corresponding Source."
            }
        }

        val noticeLibraryFile =
            nativeLibraryMetadataDirectory.resolve("chimahon-native-notices.json")
        val noticeLibrary = JsonSlurper().parse(noticeLibraryFile)
            .requiredObject("AboutLibraries ${noticeLibraryFile.name}")
        val noticeFunding = (noticeLibrary["funding"] as? List<*>)
            ?.map { it.requiredObject("${noticeLibraryFile.name} funding entry") }
            .orEmpty()
        check(
            noticeFunding.count {
                it["platform"] == "Application Corresponding Source" &&
                    it["url"] == applicationSourceArchiveUrl
            } == 1,
        ) {
            "${noticeLibraryFile.name} must expose application Corresponding Source."
        }
    }
}

val verifyImmersionStatsReleaseValidationMetadata by tasks.registering {
    group = "verification"
    description = "Validates the statistics release evidence manifest without waiving it."
    inputs.files(statsReleaseValidationManifest, nativeSourceManifest)

    doLast {
        val manifest = JsonSlurper().parse(statsReleaseValidationManifest)
            .requiredObject("Statistics release validation manifest")
        check(manifest["schemaVersion"] == 1) {
            "Statistics release validation schemaVersion must be 1."
        }
        check(manifest["releaseGate"] in setOf("blocked", "verified")) {
            "Statistics release validation releaseGate must be blocked or verified."
        }
        manifest.requiredString("reason", "Statistics release validation manifest")
        check(
            manifest.requiredString(
                "rollout",
                "Statistics release validation manifest",
            ) == "opt-in-preview-with-legacy-dual-write",
        ) {
            "v2.5.0 must retain the reviewed opt-in dual-write rollout."
        }
        val candidate = manifest.requiredString(
            "candidate",
            "Statistics release validation manifest",
        )
        val nativeManifest =
            JsonSlurper().parse(nativeSourceManifest).requiredObject("Native source manifest")
        check(candidate == nativeManifest["applicationSourceTag"]) {
            "Statistics and native manifests must identify the same release tag."
        }

        val matrix = manifest["matrix"]
            .requiredObject("Statistics release validation matrix")
        check(matrix.keys == statsValidationKeys) {
            "Statistics release validation matrix keys do not match the required matrix."
        }
        matrix.forEach { (key, value) ->
            check(value is Boolean) {
                "Statistics release validation matrix.$key must be a boolean."
            }
        }

        val evidence = (manifest["evidence"] as? List<*>)
            ?.mapIndexed { index, value ->
                value.requiredObject("Statistics release evidence[$index]")
            }
            .orEmpty()
        val evidenceIds = mutableSetOf<String>()
        val coveredKeys = mutableSetOf<String>()
        evidence.forEachIndexed { index, entry ->
            val context = "Statistics release evidence[$index]"
            check(evidenceIds.add(entry.requiredString("id", context))) {
                "$context.id must be unique."
            }
            val commit = entry.requiredString("commit", context)
            requireLowerHex(commit, 40, "$context.commit")
            entry.requiredString("date", context)
            entry.requiredString("environment", context)
            check(entry.requiredString("result", context) == "pass") {
                "$context.result must be pass before it can cover a release gate."
            }
            val covers = (entry["covers"] as? List<*>)
                ?.filterIsInstance<String>()
                ?.toSet()
                .orEmpty()
            check(covers.isNotEmpty() && statsValidationKeys.containsAll(covers)) {
                "$context.covers must contain only required matrix keys."
            }
            coveredKeys += covers
            val artifacts = (entry["artifacts"] as? List<*>)
                ?.mapIndexed { artifactIndex, value ->
                    value.requiredObject("$context.artifacts[$artifactIndex]")
                }
                .orEmpty()
            check(artifacts.isNotEmpty()) {
                "$context must reference at least one immutable evidence artifact."
            }
            artifacts.forEachIndexed { artifactIndex, artifact ->
                val artifactContext = "$context.artifacts[$artifactIndex]"
                requireHttpsUrl(
                    artifact.requiredString("url", artifactContext),
                    "$artifactContext.url",
                )
                requireLowerHex(
                    artifact.requiredString("sha256", artifactContext),
                    64,
                    "$artifactContext.sha256",
                )
            }
        }
        matrix.filterValues { it == true }.keys.forEach { key ->
            check(key in coveredKeys) {
                "Statistics release validation matrix.$key lacks passing evidence."
            }
        }
        if (manifest["releaseGate"] == "verified") {
            check(matrix.values.all { it == true }) {
                "Every statistics release validation row must pass before verification."
            }
            check(coveredKeys == statsValidationKeys) {
                "Verified statistics validation must have evidence for every row."
            }
        }
    }
}

val verifyImmersionStatsReleaseReadiness by tasks.registering {
    group = "verification"
    description = "Blocks production release assembly until device evidence is verified."
    dependsOn(verifyImmersionStatsReleaseValidationMetadata)
    inputs.file(statsReleaseValidationManifest)

    doLast {
        val manifest = JsonSlurper().parse(statsReleaseValidationManifest)
            .requiredObject("Statistics release validation manifest")
        check(manifest["releaseGate"] == "verified") {
            "Statistics release validation is not verified. See docs/immersion-stats-release-validation.md."
        }
        val matrix =
            manifest["matrix"].requiredObject("Statistics release validation matrix")
        check(statsValidationKeys.all { matrix[it] == true }) {
            "Every statistics device-validation row must pass before release."
        }
    }
}

val verifyApplicationReleaseSourceCompliance by tasks.registering {
    group = "verification"
    description = "Requires the application release tag to identify Git HEAD."
    dependsOn(
        verifyNativeSourceCompliance,
        verifyImmersionStatsReleaseReadiness,
    )
    inputs.file(nativeSourceManifest)

    doLast {
        val manifest =
            JsonSlurper().parse(nativeSourceManifest).requiredObject("Native source manifest")
        val applicationSourceTag = manifest.requiredString(
            "applicationSourceTag",
            "Native source manifest",
        )
        val applicationTag = resolveRemoteAnnotatedTag(
            applicationSourceRepository,
            applicationSourceTag,
            "Application source",
        )
        check(applicationTag.commitId == currentGitHead()) {
            "Application source tag must peel to the checked-out Git HEAD."
        }
    }
}

val nativeDistributionTaskPrefix =
    Regex("^(assemble|bundle|package|publish|upload|sign(?=[A-Z]))")
val nativeDistributionVariant =
    Regex("(ReleaseTest|Release|Foss|Preview|Benchmark)(Bundle|UniversalApk)?$")
val nativeDistributionTasks = tasks.matching { task ->
    nativeDistributionTaskPrefix.containsMatchIn(task.name) &&
        nativeDistributionVariant.containsMatchIn(task.name)
}
val applicationReleaseTasks = nativeDistributionTasks.matching { task ->
    Regex("Release(Bundle|UniversalApk)?$").containsMatchIn(task.name)
}
nativeDistributionTasks.configureEach {
    dependsOn(verifyNativeSourceCompliance)
}
applicationReleaseTasks.configureEach {
    dependsOn(
        verifyImmersionStatsReleaseReadiness,
        verifyApplicationReleaseSourceCompliance,
    )
}

val verifyReleaseComplianceTaskWiring by tasks.registering {
    group = "verification"
    description = "Verifies native and statistics gates protect distribution tasks."

    doLast {
        val nativeComplianceTask = verifyNativeSourceCompliance.get()
        val nativeComplianceProvider = verifyNativeSourceCompliance
        val statsReadinessTask = verifyImmersionStatsReleaseReadiness.get()
        val statsReadinessProvider = verifyImmersionStatsReleaseReadiness
        val applicationComplianceTask =
            verifyApplicationReleaseSourceCompliance.get()
        val applicationComplianceProvider =
            verifyApplicationReleaseSourceCompliance
        val protectedTasks = nativeDistributionTasks.toList()
        val protectedReleaseTasks = applicationReleaseTasks.toList()
        check(protectedTasks.isNotEmpty()) {
            "No protected native distribution tasks were found."
        }
        check(protectedReleaseTasks.isNotEmpty()) {
            "No protected production release tasks were found."
        }
        protectedTasks.forEach { task ->
            check(
                nativeComplianceTask in task.dependsOn ||
                    nativeComplianceProvider in task.dependsOn,
            ) {
                "${task.path} does not depend on ${nativeComplianceTask.path}."
            }
        }
        protectedReleaseTasks.forEach { task ->
            check(
                statsReadinessTask in task.dependsOn ||
                    statsReadinessProvider in task.dependsOn,
            ) {
                "${task.path} does not depend on ${statsReadinessTask.path}."
            }
            check(
                applicationComplianceTask in task.dependsOn ||
                    applicationComplianceProvider in task.dependsOn,
            ) {
                "${task.path} does not depend on ${applicationComplianceTask.path}."
            }
        }
        (protectedTasks - protectedReleaseTasks.toSet()).forEach { task ->
            check(
                statsReadinessTask !in task.dependsOn &&
                    statsReadinessProvider !in task.dependsOn &&
                    applicationComplianceTask !in task.dependsOn &&
                    applicationComplianceProvider !in task.dependsOn,
            ) {
                "Qualification variants must remain usable while release is blocked: ${task.path}."
            }
        }
        val debugDistributionTasks = tasks.filter {
            nativeDistributionTaskPrefix.containsMatchIn(it.name) &&
                Regex("debug", RegexOption.IGNORE_CASE).containsMatchIn(it.name)
        }
        debugDistributionTasks.forEach { task ->
            check(
                nativeComplianceTask !in task.dependsOn &&
                    nativeComplianceProvider !in task.dependsOn &&
                    statsReadinessTask !in task.dependsOn &&
                    statsReadinessProvider !in task.dependsOn,
            ) {
                "Debug task ${task.path} must remain independent of release gates."
            }
        }
        listOf("Release", "ReleaseTest", "Foss", "Preview", "Benchmark")
            .forEach { variant ->
                listOf("assemble", "bundle", "package").forEach { prefix ->
                    val task = tasks.findByName("$prefix$variant")
                    check(task != null && task in protectedTasks) {
                        "Expected protected task $prefix$variant was not found."
                    }
                }
            }
        listOf("assembleRelease", "bundleRelease", "packageRelease")
            .forEach { taskName ->
                val task = tasks.findByName(taskName)
                check(task != null && task in protectedReleaseTasks) {
                    "Expected protected production task $taskName was not found."
                }
            }
    }
}
