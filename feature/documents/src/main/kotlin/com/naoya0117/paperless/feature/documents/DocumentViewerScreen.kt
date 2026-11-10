package com.naoya0117.paperless.feature.documents

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.naoya0117.paperless.core.model.Document
import kotlinx.coroutines.CancellationException
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The document's pages, scrolled vertically. Tapping the document shows its metadata with an edit button.
 *
 * @param onEdit opens the editor for the document
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentViewerScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DocumentViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val infoVisible by viewModel.infoVisible.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(state.document?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.viewer_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::toggleInfo, enabled = state.document != null) {
                        Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.viewer_info))
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) {
            when (val content = state.content) {
                ViewerContent.Loading -> CircularProgressIndicator()
                is ViewerContent.Pdf -> PdfPagesView(content.pages, onTap = viewModel::toggleInfo)
                is ViewerContent.Image -> ImageView(content, onTap = viewModel::toggleInfo)
                is ViewerContent.Unsupported ->
                    Message(stringResource(R.string.viewer_unsupported, content.fileName.orEmpty()))
                ViewerContent.Failed ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Message(stringResource(R.string.viewer_failed))
                        TextButton(onClick = viewModel::retry) { Text(stringResource(R.string.documents_retry)) }
                    }
            }
        }
    }
    val document = state.document
    if (infoVisible && document != null) {
        ModalBottomSheet(onDismissRequest = viewModel::hideInfo) {
            DocumentInfo(
                document = document,
                labels = state.labels,
                onEdit = {
                    viewModel.hideInfo()
                    onEdit(document.id)
                },
            )
        }
    }
}

/** Pages one below the other, rendered when they come into view. */
@Composable
private fun PdfPagesView(
    pages: PdfPages,
    onTap: () -> Unit,
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().tapWhenNotScrolling({ listState.isScrollInProgress }, onTap),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(count = pages.pageCount, key = { it }) { index -> PdfPage(pages, index) }
    }
}

@Composable
private fun PdfPage(
    pages: PdfPages,
    index: Int,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        val bitmap by produceState<ImageBitmap?>(null, pages, index, widthPx) {
            value = safely { pages.render(index, widthPx).asImageBitmap() }
        }
        // Known once rendered; until then the page takes the space of an A4 page.
        val aspect =
            bitmap?.let { it.height.toFloat() / it.width } ?: pages.knownAspectRatio(index) ?: PdfPages.A4_ASPECT
        val description = stringResource(R.string.viewer_page, index + 1, pages.pageCount)
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1 / aspect)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            val image = bitmap
            if (image == null) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Image(
                    image,
                    contentDescription = description,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** Rendering stops (returns `null`) when the pages were closed meanwhile or the page cannot be rendered. */
private suspend fun <T> safely(block: suspend () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught") _: Exception,
    ) {
        null
    }

@Composable
private fun ImageView(
    content: ViewerContent.Image,
    onTap: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .tapWhenNotScrolling({ false }, onTap)
                .padding(12.dp),
    ) {
        AsyncImage(
            model = content.file,
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Message(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(32.dp))
}

/** The metadata of the document, with the edit button. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DocumentInfo(
    document: Document,
    labels: LabelNames,
    onEdit: () -> Unit,
) {
    val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    val dateTime =
        DateTimeFormatter
            .ofLocalizedDateTime(
                FormatStyle.MEDIUM,
                FormatStyle.SHORT,
            ).withZone(ZoneId.systemDefault())
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(
                    rememberScrollState(),
                ).padding(horizontal = 24.dp)
                .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(document.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (document.userCanChange) {
                Button(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.viewer_edit), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        val tags = document.tagIds.mapNotNull(labels.tags::get)
        if (tags.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tags.forEach { TagChip(it) }
            }
        }
        InfoRow(R.string.info_created, document.created.format(date))
        InfoRow(R.string.info_correspondent, document.correspondentId?.let(labels.correspondents::get))
        InfoRow(R.string.info_document_type, document.documentTypeId?.let(labels.documentTypes::get))
        InfoRow(R.string.info_storage_path, document.storagePathId?.let(labels.storagePaths::get))
        InfoRow(R.string.info_archive_serial_number, document.archiveSerialNumber?.toString())
        InfoRow(R.string.info_pages, document.pageCount?.toString())
        InfoRow(R.string.info_original_file_name, document.originalFileName)
        InfoRow(R.string.info_added, dateTime.format(document.added))
        InfoRow(R.string.info_modified, dateTime.format(document.modified))
        document.notes?.takeIf { it.isNotEmpty() }?.let { InfoRow(R.string.info_notes, it.size.toString()) }
        androidx.compose.foundation.layout
            .Spacer(Modifier.size(16.dp))
    }
}

@Composable
private fun InfoRow(
    label: Int,
    value: String?,
) {
    Row {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(140.dp),
        )
        Text(value ?: "—", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/**
 * Taps toggle the details, but not a tap that stops a fling (it was scrolling when the finger went down).
 */
private fun Modifier.tapWhenNotScrolling(
    scrolling: () -> Boolean,
    onTap: () -> Unit,
): Modifier =
    pointerInput(Unit) {
        var wasScrolling = false
        detectTapGestures(
            onPress = { wasScrolling = scrolling() },
            onTap = { if (!wasScrolling) onTap() },
        )
    }
