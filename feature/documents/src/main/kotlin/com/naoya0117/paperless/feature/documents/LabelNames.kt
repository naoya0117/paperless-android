package com.naoya0117.paperless.feature.documents

import com.naoya0117.paperless.core.data.LabelRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Names and colors to show for the IDs on a document. */
data class LabelNames(
    val correspondents: Map<Long, String> = emptyMap(),
    val documentTypes: Map<Long, String> = emptyMap(),
    val storagePaths: Map<Long, String> = emptyMap(),
    val tags: Map<Long, TagLabel> = emptyMap(),
)

data class TagLabel(
    val name: String,
    /** `#rrggbb` */
    val color: String,
    val textColor: String,
)

/** The cached labels of the active account by ID. */
fun LabelRepository.names(): Flow<LabelNames> =
    combine(
        correspondents.items,
        documentTypes.items,
        storagePaths.items,
        tags.items,
    ) { correspondents, types, paths, tags ->
        LabelNames(
            correspondents = correspondents.associate { it.id to it.name },
            documentTypes = types.associate { it.id to it.name },
            storagePaths = paths.associate { it.id to it.name },
            tags = tags.associate { it.id to TagLabel(it.name, it.color, it.textColor) },
        )
    }
