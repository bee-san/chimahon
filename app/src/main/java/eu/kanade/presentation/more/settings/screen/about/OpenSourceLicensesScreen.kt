package eu.kanade.presentation.more.settings.screen.about

import android.text.TextUtils
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.mikepenz.aboutlibraries.ui.compose.util.htmlReadyLicenseContent
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

class OpenSourceLicensesScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val uriHandler = LocalUriHandler.current
        val correspondingSourceLabel = stringResource(KMR.strings.licenses_corresponding_source)
        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.licenses),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            val libraries by produceLibraries(R.raw.aboutlibraries)
            LibrariesContainer(
                libraries = libraries,
                modifier = Modifier
                    .fillMaxSize(),
                contentPadding = contentPadding,
                onLibraryClick = {
                    val correspondingSourceLinks = it.funding.filter { funding ->
                        funding.platform == CORRESPONDING_SOURCE_PLATFORM ||
                            funding.platform == APPLICATION_CORRESPONDING_SOURCE_PLATFORM
                    }
                    val correspondingSourceHtml = correspondingSourceLinks.joinToString("<br/>") { funding ->
                        val escapedLabel = TextUtils.htmlEncode(correspondingSourceLabel)
                        val escapedUrl = TextUtils.htmlEncode(funding.url)
                        "<a href=\"$escapedUrl\">$escapedLabel</a>"
                    }
                    val licenseHtml = it.licenses.joinToString("<br/><hr/><br/>") { license ->
                        "<h2>${TextUtils.htmlEncode(license.name)}</h2>" +
                            license.htmlReadyLicenseContent
                    }
                    navigator.push(
                        OpenSourceLibraryLicenseScreen(
                            name = it.name,
                            website = it.website,
                            license = listOf(correspondingSourceHtml, licenseHtml)
                                .filter { section -> section.isNotBlank() }
                                .joinToString("<br/><hr/><br/>"),
                        ),
                    )
                },
                onFundingClick = { uriHandler.openUri(it.url) },
            )
        }
    }

    private companion object {
        const val CORRESPONDING_SOURCE_PLATFORM = "Corresponding Source"
        const val APPLICATION_CORRESPONDING_SOURCE_PLATFORM = "Application Corresponding Source"
    }
}
