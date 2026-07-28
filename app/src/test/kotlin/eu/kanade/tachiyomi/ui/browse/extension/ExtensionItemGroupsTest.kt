package eu.kanade.tachiyomi.ui.browse.extension

import eu.kanade.domain.extension.model.Extensions
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import io.kotest.matchers.shouldBe
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.Test

class ExtensionItemGroupsTest {

    @Test
    fun `from-sync section is placed directly after installed`() {
        val installed = installedExtension("pkg.installed")
        val fromSync = availableExtension("pkg.sync")
        val available = availableExtension("pkg.available")

        val entries = buildExtensionItemGroups(
            extensions = Extensions(
                updates = emptyList(),
                installed = listOf(installed),
                available = listOf(available),
                untrusted = emptyList(),
                fromSync = listOf(fromSync),
            ),
            predicate = { true },
            nsfwOnly = false,
            toItem = { ExtensionUiModel.Item(it, InstallStep.Idle) },
            languageDisplayName = { it },
            languageComparator = { a, b -> a.compareTo(b) },
        ).entries.toList()

        // installed -> extensions from sync -> language sections
        entries.size shouldBe 3
        entries[0].value.map { it.extension } shouldBe listOf(installed)
        (entries[1].key is ExtensionUiModel.Header.Resource) shouldBe true
        entries[1].value.map { it.extension } shouldBe listOf(fromSync)
        (entries[2].key is ExtensionUiModel.Header.Text) shouldBe true
        entries[2].value.map { it.extension } shouldBe listOf(available)
    }

    @Test
    fun `omits the from-sync section when there are no matches`() {
        val groups = buildExtensionItemGroups(
            extensions = Extensions(
                updates = emptyList(),
                installed = listOf(installedExtension("pkg.installed")),
                available = listOf(availableExtension("pkg.available")),
                untrusted = emptyList(),
                fromSync = emptyList(),
            ),
            predicate = { true },
            nsfwOnly = false,
            toItem = { ExtensionUiModel.Item(it, InstallStep.Idle) },
            languageDisplayName = { it },
            languageComparator = { a, b -> a.compareTo(b) },
        )

        // Only installed and the single language section remain.
        groups.size shouldBe 2
    }

    private val store = ExtensionStore(
        indexUrl = "https://example.com/index.min.json",
        name = "Test",
        badgeLabel = "test",
        signingKey = "key",
        contact = ExtensionStore.Contact(website = "https://example.com", discord = null),
        isLegacy = false,
        extensionListUrl = null,
    )

    private fun availableExtension(pkgName: String, lang: String = "en") = Extension.Available(
        name = pkgName,
        pkgName = pkgName,
        versionName = "1.0.0",
        versionCode = 1L,
        libVersion = 1.5,
        lang = lang,
        isNsfw = false,
        signatureHash = "hash",
        storeName = "Test",
        sources = emptyList(),
        apkUrl = "https://example.com/$pkgName.apk",
        iconUrl = "https://example.com/$pkgName.png",
        store = store,
    )

    private fun installedExtension(pkgName: String) = Extension.Installed(
        name = pkgName,
        pkgName = pkgName,
        versionName = "1.0.0",
        versionCode = 1L,
        libVersion = 1.5,
        lang = "en",
        isNsfw = false,
        signatureHash = "hash",
        pkgFactory = null,
        sources = emptyList(),
        icon = null,
        isShared = false,
    )
}
