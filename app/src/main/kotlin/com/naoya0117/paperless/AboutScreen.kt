package com.naoya0117.paperless

import android.content.Context
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.util.withJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A license or notice text bundled with the app (res/raw), opened by [LicenseTextScreen]. */
enum class BundledText(
    @StringRes val title: Int,
    @RawRes val text: Int,
    /** License texts are wrapped at ~72 columns; joined into paragraphs, they fit narrow screens. */
    val reflow: Boolean,
    /** The license's SPDX ID, as in the library list. */
    val spdxId: String? = null,
    /**
     * The libraries (`group:name`) the text is for, if it carries their copyright (MIT, BSD); empty if it is
     * the license's text for any library.
     */
    val libraries: Set<String> = emptySet(),
) {
    GPL(R.string.about_license_gpl, R.raw.license_gpl_3_0, reflow = true),
    APACHE(R.string.about_license_apache, R.raw.license_apache_2_0, reflow = true, spdxId = "Apache-2.0"),
    MIT_CHECKER_QUAL(
        R.string.about_license_mit,
        R.raw.license_mit_checker_qual,
        reflow = true,
        spdxId = "MIT",
        libraries = setOf("org.checkerframework:checker-qual"),
    ),
    BSD_LIBYUV(
        R.string.about_license_bsd_3_clause,
        R.raw.license_bsd_3_clause_libyuv,
        reflow = true,
        spdxId = "BSD-3-Clause",
        libraries = setOf("androidx.camera:camera-core"),
    ),
    NOTICES(R.string.about_notices, R.raw.third_party_notices, reflow = false),
    ;

    companion object {
        /** By [name] (the navigation argument). */
        fun of(name: String?): BundledText? = entries.firstOrNull { it.name == name }

        /** The text of a library's license, if bundled. */
        fun forLicense(
            spdxId: String,
            library: String,
        ): BundledText? =
            entries.firstOrNull {
                it.spdxId == spdxId &&
                    (it.libraries.isEmpty() || library in it.libraries)
            }
    }
}

/** A library in the APK, as listed by the AboutLibraries Gradle plugin at build time. */
@Immutable
internal data class OpenSourceLibrary(
    val id: String,
    val name: String,
    val version: String?,
    /** SPDX IDs (or names, if the POM's license is not a known one). */
    val licenses: List<String>,
    val licenseUrl: String?,
)

internal sealed interface LibrariesState {
    data object Loading : LibrariesState

    data object Failed : LibrariesState

    data class Loaded(
        val libraries: List<OpenSourceLibrary>,
    ) : LibrariesState
}

/**
 * The app's version, copyright and license (with the GPL's "no warranty" notice), where the source code is,
 * and the open source libraries in the app with their licenses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenText: (BundledText) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AboutViewModel = hiltViewModel(),
) {
    val resources = LocalResources.current
    val uriHandler = LocalUriHandler.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val libraries by viewModel.libraries.collectAsStateWithLifecycle()
    val openUrl = { url: String ->
        // No browser (e.g. a restricted profile): tell rather than crash.
        if (runCatching { uriHandler.openUri(url) }.isFailure) {
            scope.launch { snackbar.showSnackbar(resources.getString(R.string.about_open_failed)) }
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.drawer_about)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(stringResource(R.string.about_copyright), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.about_notice),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            item {
                val url = stringResource(R.string.about_source_code_url)
                ListItem(
                    headlineContent = { Text(stringResource(R.string.about_source_code)) },
                    supportingContent = { Text(url) },
                    leadingContent = { Icon(Icons.Outlined.Code, contentDescription = null) },
                    modifier = Modifier.clickable { openUrl(url) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.about_license)) },
                    supportingContent = { Text(stringResource(BundledText.GPL.title)) },
                    leadingContent = { Icon(Icons.Outlined.Gavel, contentDescription = null) },
                    modifier = Modifier.clickable { onOpenText(BundledText.GPL) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.about_notices)) },
                    leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
                    modifier = Modifier.clickable { onOpenText(BundledText.NOTICES) },
                )
                HorizontalDivider()
            }
            item {
                Text(
                    stringResource(R.string.about_libraries),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .semantics { heading() },
                )
            }
            when (val state = libraries) {
                LibrariesState.Loading ->
                    item {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                LibrariesState.Failed ->
                    item {
                        Text(
                            stringResource(R.string.about_libraries_failed),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                is LibrariesState.Loaded ->
                    items(state.libraries, key = { it.id }) { library ->
                        // Licenses not bundled (none so far: the build allows only bundled ones) open their URL.
                        val text = library.licenses.firstNotNullOfOrNull { BundledText.forLicense(it, library.id) }
                        ListItem(
                            headlineContent = { Text(library.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    listOfNotNull(library.version, library.licenses.joinToString()).joinToString(" · "),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            modifier =
                                when {
                                    text != null -> Modifier.clickable { onOpenText(text) }
                                    library.licenseUrl != null -> Modifier.clickable { openUrl(library.licenseUrl) }
                                    else -> Modifier
                                },
                        )
                    }
            }
        }
    }
}

/** A bundled license or notice text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseTextScreen(
    text: BundledText,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    val paragraphs by produceState<List<String>?>(null, text) {
        value =
            withContext(Dispatchers.IO) {
                val raw = resources.openRawResource(text.text).bufferedReader().use { it.readText() }
                paragraphs(raw, text.reflow)
            }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(text.title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        val list = paragraphs
        if (list == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val direction = LocalLayoutDirection.current
            LazyColumn(
                contentPadding =
                    PaddingValues(
                        start = padding.calculateStartPadding(direction) + 16.dp,
                        end = padding.calculateEndPadding(direction) + 16.dp,
                        top = padding.calculateTopPadding() + 8.dp,
                        bottom = padding.calculateBottomPadding() + 16.dp,
                    ),
                modifier = Modifier.fillMaxSize(),
            ) {
                // Per paragraph: a selection does not survive items scrolled out of a lazy list.
                items(list) { paragraph ->
                    SelectionContainer {
                        Text(
                            paragraph,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Wrapped lines of the license files are longer (~70 columns); a shorter one ends where the author broke it. */
private const val WRAPPED_LINE = 50

/**
 * Splits a text into its paragraphs (separated by blank lines). With [reflow], the lines of a paragraph are joined,
 * as the line breaks of the license files mostly only wrap them.
 */
internal fun paragraphs(
    text: String,
    reflow: Boolean,
): List<String> =
    text
        .replace("\r\n", "\n")
        .split(Regex("\n[ \t]*\n"))
        .map { paragraph ->
            val lines = paragraph.lines().map { it.trim() }.filter { it.isNotEmpty() }
            // Short lines before the last are deliberate breaks (titles, the sample notices): kept.
            if (reflow && lines.dropLast(1).all { it.length >= WRAPPED_LINE }) {
                lines.joinToString(" ")
            } else if (reflow) {
                lines.joinToString("\n")
            } else {
                paragraph.trimEnd().trimStart('\n')
            }
        }.filter { it.isNotBlank() }

internal fun loadLibraries(context: Context): List<OpenSourceLibrary> =
    Libs
        .Builder()
        .withJson(context, R.raw.aboutlibraries)
        .build()
        .libraries
        .map { library ->
            OpenSourceLibrary(
                id = library.uniqueId,
                name = library.name.ifBlank { library.uniqueId },
                version = library.artifactVersion,
                licenses = library.licenses.map { it.spdxId ?: it.name },
                licenseUrl = library.licenses.firstNotNullOfOrNull { it.url },
            )
        }.sortedBy { it.name.lowercase() }
