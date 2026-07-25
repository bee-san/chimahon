import groovy.json.JsonSlurper
import mihon.buildlogic.Config
import mihon.buildlogic.getBuildTime
import mihon.buildlogic.getCommitCount
import mihon.buildlogic.getGitSha
import org.gradle.api.artifacts.ExternalModuleDependency
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

plugins {
    id("mihon.android.application")
    id("mihon.android.application.compose")
    id("com.github.zellius.shortcut-helper")
    kotlin("plugin.parcelize")
    kotlin("plugin.serialization")
    alias(libs.plugins.aboutLibraries)
    id("com.github.ben-manes.versions")
}

val includeTelemetry = false
val enableUpdater = Config.enableUpdater
val hasLocalOcr = file("../chimahon-local-ocr/build.gradle.kts").exists()
val releaseVersionName = providers.gradleProperty("releaseVersionName").orNull
val releaseVersionCode = providers.gradleProperty("releaseVersionCode").orNull?.toIntOrNull()

if (includeTelemetry) {
    pluginManager.apply {
        apply(libs.plugins.google.services.get().pluginId)
        apply(libs.plugins.firebase.crashlytics.get().pluginId)
    }
}

shortcutHelper.setFilePath("./shortcuts.xml")

aboutLibraries {
    collect {
        // Native artifacts need explicit compound metadata; their POM license
        // declarations do not describe every library bundled in the AAR.
        configPath = file("../aboutlibraries/config")
    }
    license {
        additionalLicenses.addAll(
            "LGPL-3.0-or-later",
            "GPL-3.0-or-later",
        )
    }
}

val nativeSourceManifest = rootProject.file("docs/native-source-manifest.json")
val animatedSceneValidationManifest = rootProject.file("docs/animated-scene-device-validation.json")
val animatedSceneFixture = rootProject.file("app/src/androidTest/assets/scene_capture_rotated_sdr.mp4")
val animatedSceneFixtureProvenance =
    rootProject.file("app/src/androidTest/assets/scene_capture_rotated_sdr.PROVENANCE.md")
val nativeLibraryMetadataDirectory = rootProject.file("aboutlibraries/config/libraries")
val nativeNoticeLicense = rootProject.file("aboutlibraries/config/licenses/chimahon-native-notices.json")
val nativeArtifactCoordinates = setOf(
    "com.github.jmir1:ffmpeg-kit:1.17",
    "com.github.aniyomiorg:aniyomi-mpv-lib:1.17.n",
)
val nativeSourceRepositories = mapOf(
    "com.github.jmir1:ffmpeg-kit:1.17" to "https://github.com/jmir1/ffmpeg-kit",
    "com.github.aniyomiorg:aniyomi-mpv-lib:1.17.n" to "https://github.com/aniyomiorg/aniyomi-mpv-lib",
)
val nativeLicenseIdentifiers = mapOf(
    "com.github.jmir1:ffmpeg-kit:1.17" to setOf("LGPL-3.0-or-later", "GPL-3.0-or-later"),
    "com.github.aniyomiorg:aniyomi-mpv-lib:1.17.n" to setOf("MIT", "GPL-3.0-or-later"),
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

fun Any?.requiredObject(context: String): Map<*, *> {
    check(this is Map<*, *>) { "$context must be a JSON object." }
    return this
}

fun Map<*, *>.requiredString(key: String, context: String): String {
    val value = this[key]
    check(value is String && value.isNotBlank()) { "$context.$key must be a non-empty string." }
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

val verifyNativeComplianceMetadata by tasks.registering {
    group = "verification"
    description = "Verifies native AAR identities and the packaged license/notice metadata."
    inputs.files(
        nativeSourceManifest,
        nativeLibraryMetadataDirectory,
        nativeNoticeLicense,
        nativeAarConfigurations.values,
    )

    doLast {
        val manifest = JsonSlurper().parse(nativeSourceManifest).requiredObject("Native source manifest")
        check(manifest["schemaVersion"] == 2) {
            "Native source manifest schemaVersion must be 2."
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
            "Native source manifest coordinates must exactly match the resolved native AAR coordinates."
        }

        entriesByCoordinate.forEach { (coordinate, entry) ->
            val artifactContext = "Native artifact $coordinate"
            val binarySha256 = entry.requiredString("binarySha256", artifactContext)
            requireLowerHex(binarySha256, 64, "$artifactContext.binarySha256")
            val sourceCommit = entry.requiredString("sourceCommit", artifactContext)
            requireLowerHex(sourceCommit, 40, "$artifactContext.sourceCommit")
            val sourceTag = entry.requiredString("sourceTag", artifactContext)
            check(Regex("[A-Za-z0-9._-]+").matches(sourceTag)) {
                "$artifactContext.sourceTag contains unsupported characters."
            }
            val sourceTagObject = entry.requiredString("sourceTagObject", artifactContext)
            requireLowerHex(sourceTagObject, 40, "$artifactContext.sourceTagObject")
            val sourceRevisionUrl = entry.requiredString("sourceRevisionUrl", artifactContext)
            requireHttpsUrl(sourceRevisionUrl, "$artifactContext.sourceRevisionUrl")
            val sourceRepository = checkNotNull(nativeSourceRepositories[coordinate])
            check(sourceRevisionUrl == "$sourceRepository/tree/$sourceCommit") {
                "$artifactContext.sourceRevisionUrl must identify sourceCommit in its expected repository."
            }
            val tagRef = "refs/tags/$sourceTag"
            val peeledTagRef = "$tagRef^{}"
            val tagProcess = ProcessBuilder(
                "git",
                "ls-remote",
                "$sourceRepository.git",
                tagRef,
                peeledTagRef,
            )
                .redirectErrorStream(true)
                .start()
            val tagOutput = tagProcess.inputStream.bufferedReader().use { it.readText() }
            check(tagProcess.waitFor() == 0) {
                "$artifactContext tag lookup failed: ${tagOutput.trim()}"
            }
            val remoteRefs = tagOutput.lineSequence()
                .filter { it.isNotBlank() }
                .associate { line ->
                    val parts = line.split('\t', limit = 2)
                    check(parts.size == 2) { "$artifactContext tag lookup returned malformed output." }
                    parts[1] to parts[0]
                }
            check(remoteRefs[tagRef] == sourceTagObject) {
                "$artifactContext sourceTagObject does not match the remote annotated tag object."
            }
            check(remoteRefs[peeledTagRef] == sourceCommit) {
                "$artifactContext sourceCommit is not the commit peeled from sourceTag."
            }
            val toolchain = entry["toolchain"].requiredObject("$artifactContext.toolchain")
            listOf(
                "androidNdk",
                "androidSdkPlatform",
                "androidBuildTools",
                "ffmpeg",
                "mpv",
            ).forEach { key -> toolchain.requiredString(key, "$artifactContext.toolchain") }

            val configuration = checkNotNull(nativeAarConfigurations[coordinate])
            val resolvedArtifacts = configuration.resolvedConfiguration.resolvedArtifacts
            check(resolvedArtifacts.size == 1) {
                "$artifactContext must resolve to exactly one direct artifact, but resolved ${resolvedArtifacts.size}."
            }
            val resolvedArtifact = resolvedArtifacts.single()
            val moduleVersion = resolvedArtifact.moduleVersion.id
            val resolvedCoordinate = "${moduleVersion.group}:${resolvedArtifact.name}:${moduleVersion.version}"
            check(resolvedCoordinate == coordinate) {
                "$artifactContext resolved as $resolvedCoordinate."
            }
            check(resolvedArtifact.extension.equals("aar", ignoreCase = true)) {
                "$artifactContext must resolve to an AAR, not ${resolvedArtifact.extension}."
            }
            val resolvedSha256 = resolvedArtifact.file.sha256()
            check(resolvedSha256 == binarySha256) {
                "$artifactContext resolved AAR SHA-256 $resolvedSha256 does not match $binarySha256."
            }

            val libraryId = coordinate.substringBeforeLast(':')
            val artifactVersion = coordinate.substringAfterLast(':')
            val libraryFile = nativeLibraryMetadataDirectory.resolve(
                when (libraryId) {
                    "com.github.jmir1:ffmpeg-kit" -> "ffmpeg-kit.json"
                    "com.github.aniyomiorg:aniyomi-mpv-lib" -> "aniyomi-mpv-lib.json"
                    else -> error("No native AboutLibraries metadata mapping for $libraryId")
                },
            )
            val library = JsonSlurper().parse(libraryFile).requiredObject("AboutLibraries ${libraryFile.name}")
            check(library["uniqueId"] == libraryId && library["artifactVersion"] == artifactVersion) {
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
                "${libraryFile.name} must disclose the exact wrapper and native-payload license identifiers."
            }
        }

        val notice = JsonSlurper().parse(nativeNoticeLicense).requiredObject("Native notice license")
        val noticeContent = notice["content"] as? String
        check(
            notice["hash"] == "Chimahon-Native-Notices" &&
                noticeContent?.contains("Licensed under GNU LGPL version 3 or, at your option, any later version.") == true &&
                noticeContent.contains("Copyright (c) 2000-2024 the FFmpeg developers") &&
                noticeContent.contains("Copyright (c) 2016 Ilya Zhuravlev") &&
                noticeContent.contains("Copyright (c) 2016 sfan5 <sfan5@live.de>"),
        ) {
            "The packaged native notice must disclose both bundled native payloads."
        }
        val noticeLibraryFile = nativeLibraryMetadataDirectory.resolve("chimahon-native-notices.json")
        val noticeLibrary = JsonSlurper().parse(noticeLibraryFile)
            .requiredObject("AboutLibraries ${noticeLibraryFile.name}")
        check(
            noticeLibrary["uniqueId"] == "app.chimahon:native-notices" &&
                noticeLibrary["licenses"] == listOf("Chimahon-Native-Notices"),
        ) {
            "The packaged native notice must be associated with a visible AboutLibraries entry."
        }
    }
}

val verifyNativeSourceCompliance by tasks.registering {
    group = "verification"
    description = "Blocks distributable native builds until exact Corresponding Source is recorded."
    dependsOn(verifyNativeComplianceMetadata)
    inputs.files(nativeSourceManifest, nativeLibraryMetadataDirectory)

    doLast {
        val manifest = JsonSlurper().parse(nativeSourceManifest).requiredObject("Native source manifest")
        check(manifest["releaseGate"] == "verified") {
            "Native Corresponding Source is not verified. See docs/native-distribution-compliance.md."
        }

        val applicationSourceUrl = manifest.requiredString("applicationSourceUrl", "Native source manifest")
        requireHttpsUrl(applicationSourceUrl, "Native source manifest.applicationSourceUrl")
        val applicationSourceCommit = manifest.requiredString("applicationSourceCommit", "Native source manifest")
        requireLowerHex(applicationSourceCommit, 40, "Native source manifest.applicationSourceCommit")
        check(applicationSourceUrl.endsWith("/$applicationSourceCommit")) {
            "applicationSourceUrl must identify applicationSourceCommit exactly."
        }

        val artifacts = (manifest["artifacts"] as List<*>).map {
            it.requiredObject("Native source manifest artifact")
        }
        artifacts.forEach { artifact ->
            val coordinate = artifact.requiredString("coordinate", "Native artifact")
            val artifactContext = "Native artifact $coordinate"
            val sourceArchiveUrl = artifact.requiredString("sourceArchiveUrl", artifactContext)
            requireHttpsUrl(sourceArchiveUrl, "$artifactContext.sourceArchiveUrl")
            val sourceArchiveSha256 = artifact.requiredString("sourceArchiveSha256", artifactContext)
            requireLowerHex(sourceArchiveSha256, 64, "$artifactContext.sourceArchiveSha256")
            val toolchain = artifact["toolchain"].requiredObject("$artifactContext.toolchain")
            val toolchainArchiveUrl = toolchain.requiredString("archiveUrl", "$artifactContext.toolchain")
            requireHttpsUrl(toolchainArchiveUrl, "$artifactContext.toolchain.archiveUrl")
            val toolchainArchiveSha256 = toolchain.requiredString("archiveSha256", "$artifactContext.toolchain")
            requireLowerHex(toolchainArchiveSha256, 64, "$artifactContext.toolchain.archiveSha256")

            val libraryId = coordinate.substringBeforeLast(':')
            val libraryFile = nativeLibraryMetadataDirectory.resolve(
                when (libraryId) {
                    "com.github.jmir1:ffmpeg-kit" -> "ffmpeg-kit.json"
                    "com.github.aniyomiorg:aniyomi-mpv-lib" -> "aniyomi-mpv-lib.json"
                    else -> error("No native AboutLibraries metadata mapping for $libraryId")
                },
            )
            val library = JsonSlurper().parse(libraryFile).requiredObject("AboutLibraries ${libraryFile.name}")
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
                "${libraryFile.name} must expose sourceArchiveUrl as one structured Corresponding Source link."
            }
        }

        val noticeLibraryFile = nativeLibraryMetadataDirectory.resolve("chimahon-native-notices.json")
        val noticeLibrary = JsonSlurper().parse(noticeLibraryFile)
            .requiredObject("AboutLibraries ${noticeLibraryFile.name}")
        val noticeFunding = (noticeLibrary["funding"] as? List<*>)
            ?.map { it.requiredObject("${noticeLibraryFile.name} funding entry") }
            .orEmpty()
        check(
            noticeFunding.count {
                it["platform"] == "Application Corresponding Source" &&
                    it["url"] == applicationSourceUrl
            } == 1,
        ) {
            "${noticeLibraryFile.name} must expose applicationSourceUrl as a structured Corresponding Source link."
        }
    }
}

val animatedSceneValidationKeys = setOf(
    "api26To29Webp",
    "api30PlusWebpLossy",
    "localAndReadOnlySaf",
    "remoteHlsAndAuthenticated",
    "videoOnlyDash",
    "rotationAndSdr",
    "phaseCancellationAndFaults",
    "ankiDroidFileProviderFallback",
    "desktopWebMobilePlayback",
    "lowerEndDeviceBenchmark",
)

val verifyAnimatedSceneValidationMetadata by tasks.registering {
    group = "verification"
    description = "Verifies the animated-scene fixture identity and device-validation manifest."
    inputs.files(
        animatedSceneValidationManifest,
        animatedSceneFixture,
        animatedSceneFixtureProvenance,
    )

    doLast {
        val manifest = JsonSlurper().parse(animatedSceneValidationManifest)
            .requiredObject("Animated-scene validation manifest")
        check(manifest["schemaVersion"] == 1) {
            "Animated-scene validation manifest schemaVersion must be 1."
        }
        check(manifest["releaseGate"] in setOf("blocked", "verified")) {
            "Animated-scene validation releaseGate must be blocked or verified."
        }
        manifest.requiredString("reason", "Animated-scene validation manifest")
        val fixture = manifest["fixture"].requiredObject("Animated-scene validation fixture")
        val fixturePath = fixture.requiredString("path", "Animated-scene validation fixture")
        val fixtureFile = rootProject.file(fixturePath)
        check(fixtureFile.isFile) {
            "Animated-scene validation fixture does not exist: $fixturePath"
        }
        val expectedFixtureSha256 = fixture.requiredString(
            "sha256",
            "Animated-scene validation fixture",
        )
        requireLowerHex(
            expectedFixtureSha256,
            64,
            "Animated-scene validation fixture.sha256",
        )
        check(fixtureFile.sha256() == expectedFixtureSha256) {
            "Animated-scene validation fixture SHA-256 does not match its manifest."
        }
        check(fixture.requiredString("license", "Animated-scene validation fixture") == "CC0-1.0") {
            "Animated-scene validation fixture must have the recorded CC0-1.0 grant."
        }
        val provenancePath = fixture.requiredString(
            "provenance",
            "Animated-scene validation fixture",
        )
        check(rootProject.file(provenancePath).isFile) {
            "Animated-scene validation fixture provenance does not exist: $provenancePath"
        }

        val matrix = manifest["matrix"].requiredObject("Animated-scene validation matrix")
        check(matrix.keys == animatedSceneValidationKeys) {
            "Animated-scene validation matrix keys do not match the required matrix."
        }
        matrix.forEach { (key, value) ->
            check(value is Boolean) {
                "Animated-scene validation matrix.$key must be a boolean."
            }
        }
    }
}

val verifyAnimatedSceneReleaseReadiness by tasks.registering {
    group = "verification"
    description = "Blocks distributable builds until the animated-scene device matrix is verified."
    dependsOn(verifyAnimatedSceneValidationMetadata)
    inputs.file(animatedSceneValidationManifest)

    doLast {
        val manifest = JsonSlurper().parse(animatedSceneValidationManifest)
            .requiredObject("Animated-scene validation manifest")
        check(manifest["releaseGate"] == "verified") {
            "Animated-scene device validation is not verified. See docs/animated-scene-device-validation.md."
        }
        val matrix = manifest["matrix"].requiredObject("Animated-scene validation matrix")
        check(animatedSceneValidationKeys.all { matrix[it] == true }) {
            "Every animated-scene device-validation matrix entry must be true before release."
        }
    }
}

val nativeDistributionTaskPrefix = Regex("^(assemble|bundle|package|sign|publish|upload)", RegexOption.IGNORE_CASE)
val nativeDistributionVariant = Regex("(releaseTest|release|foss|preview|benchmark)", RegexOption.IGNORE_CASE)
val nativeDistributionTasks = tasks.matching { task ->
    nativeDistributionTaskPrefix.containsMatchIn(task.name) &&
        nativeDistributionVariant.containsMatchIn(task.name)
}
nativeDistributionTasks.configureEach {
    dependsOn(
        verifyNativeSourceCompliance,
        verifyAnimatedSceneReleaseReadiness,
    )
}

val verifyNativeComplianceTaskWiring by tasks.registering {
    group = "verification"
    description = "Verifies that all native distribution task paths depend on the compliance gate."

    doLast {
        val complianceTask = verifyNativeSourceCompliance.get()
        val complianceTaskProvider = verifyNativeSourceCompliance
        val protectedTasks = nativeDistributionTasks.toList()
        check(protectedTasks.isNotEmpty()) {
            "No protected native distribution tasks were found."
        }
        protectedTasks.forEach { task ->
            check(
                complianceTask in task.dependsOn ||
                    complianceTaskProvider in task.dependsOn,
            ) {
                "${task.path} does not depend on ${complianceTask.path}."
            }
        }
        val debugDistributionTasks = tasks.filter {
            nativeDistributionTaskPrefix.containsMatchIn(it.name) &&
                Regex("debug", RegexOption.IGNORE_CASE).containsMatchIn(it.name)
        }
        debugDistributionTasks.forEach { task ->
            check(
                complianceTask !in task.dependsOn &&
                    complianceTaskProvider !in task.dependsOn,
            ) {
                "Debug task ${task.path} must remain independent of ${complianceTask.path}."
            }
        }
        listOf("Release", "ReleaseTest", "Foss", "Preview", "Benchmark").forEach { variant ->
            listOf("assemble", "bundle", "package").forEach { prefix ->
                val task = tasks.findByName("$prefix$variant")
                check(task != null && task in protectedTasks) {
                    "Expected protected task $prefix$variant was not found."
                }
            }
        }
    }
}

val verifyAnimatedSceneValidationTaskWiring by tasks.registering {
    group = "verification"
    description = "Verifies that distributable task paths depend on the scene device-validation gate."

    doLast {
        val readinessTask = verifyAnimatedSceneReleaseReadiness.get()
        val readinessTaskProvider = verifyAnimatedSceneReleaseReadiness
        val protectedTasks = nativeDistributionTasks.toList()
        check(protectedTasks.isNotEmpty()) {
            "No distributable native task paths were found."
        }
        protectedTasks.forEach { task ->
            check(
                readinessTask in task.dependsOn ||
                    readinessTaskProvider in task.dependsOn,
            ) {
                "${task.path} does not depend on ${readinessTask.path}."
            }
        }
        val debugDistributionTasks = tasks.filter {
            nativeDistributionTaskPrefix.containsMatchIn(it.name) &&
                Regex("debug", RegexOption.IGNORE_CASE).containsMatchIn(it.name)
        }
        debugDistributionTasks.forEach { task ->
            check(
                readinessTask !in task.dependsOn &&
                    readinessTaskProvider !in task.dependsOn,
            ) {
                "${task.path} must remain available while scene device validation is blocked."
            }
        }
    }
}

android {
    namespace = "eu.kanade.tachiyomi"

    defaultConfig {
        applicationId = "app.chimahon"

        versionCode = releaseVersionCode ?: 3
        versionName = releaseVersionName ?: "1.1.0"

        buildConfigField("String", "COMMIT_COUNT", "\"${getCommitCount()}\"")
        buildConfigField("String", "COMMIT_SHA", "\"${getGitSha()}\"")
        buildConfigField("String", "BUILD_TIME", "\"${getBuildTime(useLastCommitTime = false)}\"")
        buildConfigField("boolean", "TELEMETRY_INCLUDED", "false")
        buildConfigField("boolean", "UPDATER_ENABLED", enableUpdater.toString())
        buildConfigField("boolean", "HAS_LOCAL_OCR", hasLocalOcr.toString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        val debug by getting {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-${getCommitCount()}"
            isPseudoLocalesEnabled = true
            buildConfigField("boolean", "TELEMETRY_INCLUDED", "false")
            buildConfigField("boolean", "UPDATER_ENABLED", "false")
        }
        val release by getting {
            isMinifyEnabled = Config.enableCodeShrink
            isShrinkResources = Config.enableCodeShrink

            proguardFiles("proguard-android-optimize.txt", "proguard-rules.pro")

            buildConfigField("String", "BUILD_TIME", "\"${getBuildTime(useLastCommitTime = true)}\"")
            buildConfigField("boolean", "TELEMETRY_INCLUDED", "false")
            buildConfigField("boolean", "UPDATER_ENABLED", enableUpdater.toString())
        }

        val commonMatchingFallbacks = listOf(release.name)

        create("releaseTest") {
            initWith(release)

            applicationIdSuffix = ".rt"
            isMinifyEnabled = false
            isShrinkResources = false

            matchingFallbacks.addAll(commonMatchingFallbacks)
        }
        create("foss") {
            initWith(release)

            applicationIdSuffix = ".foss"

            matchingFallbacks.addAll(commonMatchingFallbacks)

            buildConfigField("boolean", "TELEMETRY_INCLUDED", "false")
            buildConfigField("boolean", "UPDATER_ENABLED", enableUpdater.toString())
        }
        create("preview") {
            initWith(release)

            applicationIdSuffix = ".beta"

            versionNameSuffix = debug.versionNameSuffix
            signingConfig = debug.signingConfig

            matchingFallbacks.addAll(commonMatchingFallbacks)

            buildConfigField("String", "BUILD_TIME", "\"${getBuildTime(useLastCommitTime = false)}\"")
            buildConfigField("boolean", "TELEMETRY_INCLUDED", "false")
            buildConfigField("boolean", "UPDATER_ENABLED", enableUpdater.toString())
        }
        create("benchmark") {
            initWith(release)

            isDebuggable = false
            isProfileable = true
            versionNameSuffix = "${debug.versionNameSuffix}-benchmark"
            applicationIdSuffix = ".benchmark"

            signingConfig = debug.signingConfig

            matchingFallbacks.addAll(commonMatchingFallbacks)

            buildConfigField("boolean", "TELEMETRY_INCLUDED", "false")
            buildConfigField("boolean", "UPDATER_ENABLED", enableUpdater.toString())
        }
    }

    sourceSets {
        getByName("preview").res.srcDirs("src/beta/res")
        getByName("benchmark").res.srcDirs("src/debug/res")
    }

    splits {
        abi {
            isEnable = true
            isUniversalApk = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    packaging {
        jniLibs {
            keepDebugSymbols += listOf(
                "libandroidx.graphics.path",
                "libarchive-jni",
                "libconscrypt_jni",
                "libimagedecoder",
                "libquickjs",
                "libsqlite3x",
                "libmpv",
                "libavcodec",
                "libavformat",
                "libswscale",
                "libavutil",
                "libswresample",
                "libavfilter",
                "libass",
                "libdav1d",
                "libplacebo",
            )
                .map { "**/$it.so" }
        }
        resources {
            excludes += setOf(
                "kotlin-tooling-metadata.json",
                "LICENSE.txt",
                "META-INF/**/*.properties",
                "META-INF/**/LICENSE.txt",
                "META-INF/*.properties",
                "META-INF/*.version",
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/NOTICE",
                "META-INF/README.md",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }

    dependenciesInfo {
        includeInApk = Config.includeDependencyInfo
        includeInBundle = Config.includeDependencyInfo
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        aidl = true

        // Disable some unused things
        renderScript = false
        shaders = false
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=coil3.annotation.ExperimentalCoilApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-opt-in=kotlinx.coroutines.InternalCoroutinesApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
            "-Xannotation-default-target=param-property",
        )
    }
}

dependencies {
    implementation(projects.chimahon)

    if (hasLocalOcr) {
        implementation(project(":chimahon-local-ocr"))
    }

    implementation(projects.i18n)
    // ANK -->
    implementation(projects.i18nAnk)
    // ANK <--
    // KMK -->
    implementation(projects.i18nKmk)
    // KMK <--
    // SY -->
    implementation(projects.i18nSy)
    // SY <--
    implementation(projects.core.archive)
    implementation(projects.core.common)
    implementation(projects.coreMetadata)
    implementation(projects.sourceApi)
    implementation(projects.sourceLocal)
    implementation(projects.data)
    implementation(projects.domain)
    implementation(projects.presentationCore)
    implementation(projects.presentationWidget)
    implementation(projects.telemetry)

    // Compose
    implementation(compose.activity)
    implementation(compose.foundation)
    implementation(compose.material3.core)
    implementation(compose.material.icons)
    implementation(compose.animation)
    implementation(compose.animation.graphics)
    implementation(compose.glance)
    debugImplementation(compose.ui.tooling)
    implementation(compose.ui.tooling.preview)
    implementation(compose.ui.util)
    implementation(compose.constraintlayout)

    implementation(androidx.interpolator)

    implementation(androidx.paging.runtime)
    implementation(androidx.paging.compose)

    implementation(libs.bundles.sqlite)

    implementation(kotlinx.reflect)
    implementation(kotlinx.immutables)

    implementation(platform(kotlinx.coroutines.bom))
    implementation(kotlinx.bundles.coroutines)

    // AndroidX libraries
    implementation(androidx.annotation)
    implementation(androidx.appcompat)
    implementation(androidx.biometricktx)
    implementation(androidx.constraintlayout)
    implementation(androidx.corektx)
    implementation(androidx.splashscreen)
    implementation(androidx.recyclerview)
    implementation(androidx.viewpager)
    implementation(androidx.profileinstaller)

    implementation(androidx.bundles.lifecycle)

    // Job scheduling
    implementation(androidx.workmanager)

    // RxJava
    implementation(libs.rxjava)

    // Networking
    implementation(libs.bundles.okhttp)
    implementation(libs.okio)
    implementation(libs.conscrypt.android) // TLS 1.3 support for Android < 10

    // Data serialization (JSON, protobuf, xml)
    implementation(kotlinx.bundles.serialization)

    // HTML parser
    implementation(libs.jsoup)

    // Disk
    implementation(libs.disklrucache)
    implementation(libs.unifile)

    // Preferences
    implementation(libs.preferencektx)

    // Dependency injection
    implementation(libs.injekt)

    // Image loading
    implementation(platform(libs.coil.bom))
    implementation(libs.bundles.coil)
    implementation(libs.subsamplingscaleimageview) {
        exclude(module = "image-decoder")
    }
    implementation(libs.image.decoder)

    // Image cropper
    implementation(libs.android.image.cropper)

    // UI libraries
    implementation(libs.material)
    implementation(libs.flexible.adapter.core)
    implementation(libs.photoview)
    implementation(libs.directionalviewpager) {
        exclude(group = "androidx.viewpager", module = "viewpager")
    }
    implementation(libs.richeditor.compose)
    implementation(libs.aboutLibraries.compose)
    implementation(libs.bundles.voyager)
    implementation(libs.compose.materialmotion)
    implementation(libs.swipe)
    implementation(libs.compose.webview)
    implementation(libs.compose.grid)
    implementation(libs.reorderable)
    implementation(libs.bundles.markdown)
    implementation(libs.materialKolor)

    // KMK -->
    implementation(libs.palette.ktx)
    implementation(libs.haze)
    implementation(compose.colorpicker)
    implementation(projects.flagkit)
    // KMK <--

    // Logging
    implementation(libs.timber)
    implementation(libs.logcat)

    // Shizuku
    implementation(libs.bundles.shizuku)

    // String similarity
    implementation(libs.stringSimilarity)

    // Tests
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)
    androidTestImplementation(androidx.test.ext)
    androidTestImplementation(androidx.test.runner)

    // For detecting memory leaks; see https://square.github.io/leakcanary/
    // debugImplementation(libs.leakcanary.android)
    implementation(libs.leakcanary.plumber)

    testImplementation(kotlinx.coroutines.test)

    // MPV player
    implementation(libs.aniyomi.mpv)
    implementation(libs.seeker)
    implementation(libs.ffmpeg.kit)
    implementation(libs.smart.exception.java)
    implementation(libs.mediasession)
    implementation(libs.truetypeparser)
    implementation(libs.torrentserver)
    implementation(libs.nanohttpd)
    implementation(libs.media.router)
    implementation(libs.cast.play.services)

    // SY -->
    // Better logging (EH)
    implementation(sylibs.xlog)

    // RatingBar (SY)
    implementation(sylibs.ratingbar)
    implementation(sylibs.composeRatingbar)

    // Google drive
    implementation(sylibs.google.api.services.drive)

    // ZXing Android Embedded
    implementation(sylibs.zxing.android.embedded)

    // NewPipe Extractor for YouTube stream resolution
    implementation(libs.newpipe.extractor)
    // Match NewPipe dev's protobuf patch while staying on the stable extractor release.
    implementation(libs.protobuf.javalite)
}

androidComponents {
    onVariants(selector().withFlavor("default" to "standard")) {
        // Only excluding in standard flavor because this breaks
        // Layout Inspector's Compose tree
        it.packaging.resources.excludes.add("META-INF/*.version")
    }
}

buildscript {
    dependencies {
        classpath(kotlinx.gradle)
    }
}
