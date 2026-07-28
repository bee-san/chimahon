package eu.kanade.tachiyomi.ui.browse.animeextension

import eu.kanade.domain.animeextension.model.AnimeExtensions
import eu.kanade.tachiyomi.animeextension.model.AnimeExtension
import eu.kanade.tachiyomi.extension.model.InstallStep
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AnimeExtensionItemGroupsTest {

    @Test
    fun `from-sync section is placed directly after installed`() {
        val installed = installedExtension("pkg.installed")
        val fromSync = availableExtension("pkg.sync")
        val available = availableExtension("pkg.available")

        val entries = buildAnimeExtensionItemGroups(
            extensions = AnimeExtensions(
                updates = emptyList(),
                installed = listOf(installed),
                available = listOf(available),
                untrusted = emptyList(),
                fromSync = listOf(fromSync),
            ),
            predicate = { true },
            toItem = { AnimeExtensionUiModel.Item(it, InstallStep.Idle) },
            languageDisplayName = { it },
            languageComparator = { a, b -> a.compareTo(b) },
        ).entries.toList()

        // installed -> extensions from sync -> language sections
        entries.size shouldBe 3
        entries[0].value.map { it.extension } shouldBe listOf(installed)
        (entries[1].key is AnimeExtensionUiModel.Header.Resource) shouldBe true
        entries[1].value.map { it.extension } shouldBe listOf(fromSync)
        (entries[2].key is AnimeExtensionUiModel.Header.Text) shouldBe true
        entries[2].value.map { it.extension } shouldBe listOf(available)
    }

    @Test
    fun `omits the from-sync section when there are no matches`() {
        val groups = buildAnimeExtensionItemGroups(
            extensions = AnimeExtensions(
                updates = emptyList(),
                installed = listOf(installedExtension("pkg.installed")),
                available = listOf(availableExtension("pkg.available")),
                untrusted = emptyList(),
                fromSync = emptyList(),
            ),
            predicate = { true },
            toItem = { AnimeExtensionUiModel.Item(it, InstallStep.Idle) },
            languageDisplayName = { it },
            languageComparator = { a, b -> a.compareTo(b) },
        )

        groups.size shouldBe 2
    }

    private fun availableExtension(pkgName: String, lang: String = "en") = AnimeExtension.Available(
        name = pkgName,
        pkgName = pkgName,
        versionName = "1.0.0",
        versionCode = 1L,
        libVersion = 1.5,
        lang = lang,
        isNsfw = false,
        isTorrent = false,
        signatureHash = "hash",
        sources = emptyList(),
        apkName = "$pkgName.apk",
        iconUrl = "https://example.com/$pkgName.png",
        repoUrl = "https://example.com",
    )

    private fun installedExtension(pkgName: String) = AnimeExtension.Installed(
        name = pkgName,
        pkgName = pkgName,
        versionName = "1.0.0",
        versionCode = 1L,
        libVersion = 1.5,
        lang = "en",
        isNsfw = false,
        isTorrent = false,
        signatureHash = "hash",
        pkgFactory = null,
        sources = emptyList(),
        icon = null,
        isShared = false,
    )
}
