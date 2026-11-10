package com.naoya0117.paperless.demo

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * A Paperless-ngx server inside the app for the demo build: answers every request of the app's HTTP client
 * (any host) from [DemoData], like API v10 does, without any network access. Changes are kept in memory.
 */
class DemoServer internal constructor(
    private val data: DemoData,
    private val files: DemoFiles,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val reply =
            try {
                // Files are drawn outside the lock, so that thumbnails do not hold up the API calls.
                fileRequest(request) ?: synchronized(this) { handle(request) }
            } catch (e: Exception) {
                Reply.json(500, JSONObject().put("detail", "Demo server error: $e"))
            }
        return Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(reply.code)
            .message(if (reply.code < 400) "OK" else "Error")
            .header("X-Api-Version", API_VERSION)
            .header("X-Version", SERVER_VERSION)
            .apply { reply.headers.forEach { (name, value) -> header(name, value) } }
            .body(reply.body.toResponseBody(reply.contentType.toMediaType()))
            .build()
    }

    private class Reply(
        val code: Int,
        val body: ByteArray,
        val contentType: String,
        val headers: List<Pair<String, String>> = emptyList(),
    ) {
        companion object {
            fun json(
                code: Int,
                value: Any,
            ) = Reply(code, value.toString().toByteArray(), "application/json")

            fun ok(value: Any) = json(200, value)

            val noContent = Reply(204, ByteArray(0), "application/json")

            fun notFound() = json(404, JSONObject().put("detail", "No object matches the given query."))
        }
    }

    /** The path below `/api/`, split into segments. */
    private fun apiPath(url: HttpUrl): List<String> =
        url.encodedPath
            .substringAfter("/api/", "")
            .trim('/')
            .split('/')
            .filter { it.isNotEmpty() }

    /** `GET documents/<id>/thumb|preview|download/`, or null for any other request. */
    private fun fileRequest(request: Request): Reply? {
        val path = apiPath(request.url)
        if (request.method != "GET" || path.size != 3 || path[0] != "documents" || path[2] !in FILE_KINDS) return null
        val id = path[1].toLongOrNull() ?: return null
        val document = synchronized(this) { data.document(id)?.copy() } ?: return Reply.notFound()
        return file(document, path[2])
    }

    private fun handle(request: Request): Reply {
        val url = request.url
        val path = apiPath(url)
        val body = request.bodyJson()
        val method = request.method
        val head = path.firstOrNull() ?: return Reply.notFound()
        return when {
            head == "token" && method == "POST" -> Reply.ok(JSONObject().put("token", DEMO_TOKEN))
            head == "ui_settings" -> Reply.ok(data.uiSettings)
            head == "documents" -> documents(request, path.drop(1), body)
            head == "trash" -> trash(url, body, method)
            head == "tasks" -> tasks(path.drop(1))
            head in DemoData.COLLECTIONS -> collection(head, url, path.drop(1), body, method)
            else -> Reply.notFound()
        }
    }

    // Documents

    private fun documents(
        request: Request,
        path: List<String>,
        body: JSONObject?,
    ): Reply {
        val method = request.method
        val first = path.firstOrNull()
        return when {
            first == null && method == "GET" -> page(request.url, documentList(request.url))
            first == "post_document" -> uploadUnavailable()
            first == "bulk_edit" -> bulkEdit(body)
            first == "delete" -> {
                body?.optJSONArray("documents")?.longs()?.forEach(::moveToTrash)
                Reply.ok(JSONObject().put("result", "OK"))
            }
            first == "reprocess" -> Reply.ok(JSONObject().put("result", "OK"))
            first == "next_asn" -> Reply.ok(nextAsn())
            else -> document(first?.toLongOrNull() ?: return Reply.notFound(), path.drop(1), request, body)
        }
    }

    private fun document(
        id: Long,
        path: List<String>,
        request: Request,
        body: JSONObject?,
    ): Reply {
        val document = data.document(id) ?: return Reply.notFound()
        val method = request.method
        return when (path.firstOrNull()) {
            null ->
                when (method) {
                    "GET" -> Reply.ok(document)
                    "PATCH", "PUT" -> Reply.ok(update(document, body ?: JSONObject()))
                    "DELETE" -> {
                        moveToTrash(id)
                        Reply.noContent
                    }
                    else -> Reply.notFound()
                }
            "notes" -> notes(document, request, body)
            "metadata" -> Reply.ok(metadata(document))
            "suggestions" -> Reply.ok(suggestions(document))
            "history" -> Reply.ok(history(document))
            else -> Reply.notFound()
        }
    }

    private fun documentList(url: HttpUrl): List<JSONObject> {
        val params = url.queryParameterNames.associateWith { url.queryParameter(it).orEmpty() }
        val search = SEARCH_PARAMS.firstNotNullOfOrNull { params[it]?.takeIf(String::isNotBlank) }
        val moreLike = params["more_like_id"]?.toLongOrNull()?.let(data::document)
        val matches =
            data.documents.filter { document ->
                params.all { (key, value) -> matchesParam(document, key, value) } &&
                    (moreLike == null || isSimilar(document, moreLike))
            }
        val sorted = sort(matches, params["ordering"])
        return if (search == null) {
            sorted
        } else {
            sorted.mapIndexed {
                rank,
                document,
                ->
                document.copy().put("__search_hit__", searchHit(document, search, rank))
            }
        }
    }

    private fun matchesParam(
        document: JSONObject,
        key: String,
        value: String,
    ): Boolean {
        val ids = value.split(',').mapNotNull { it.trim().toLongOrNull() }
        val tags = document.getJSONArray("tags").longs()
        return when {
            key in IGNORED_PARAMS -> true
            key == "text" || key == "query" -> containsText(document, value, content = true)
            key == "title_search" || key == "title__icontains" -> containsText(document, value, content = false)
            key == "content__icontains" -> document.optString("content").contains(value, ignoreCase = true)
            key == "tags__id__all" -> tags.containsAll(ids)
            key == "tags__id__in" -> tags.any { it in ids }
            key == "tags__id__none" -> tags.none { it in ids }
            key == "is_tagged" -> tags.isNotEmpty() == value.isTrue()
            key == "is_in_inbox" -> tags.any { it in inboxTagIds() } == value.isTrue()
            key == "id__in" -> document.getLong("id") in ids
            key == "mime_type" -> document.optString("mime_type") == value
            key == "has_custom_fields" -> fieldIds(document).isNotEmpty() == value.isTrue()
            key == "custom_fields__id__all" -> fieldIds(document).containsAll(ids)
            key == "custom_fields__id__in" -> fieldIds(document).any { it in ids }
            key == "custom_fields__id__none" -> fieldIds(document).none { it in ids }
            key == "custom_fields__icontains" ->
                document
                    .getJSONArray(
                        "custom_fields",
                    ).objects()
                    .any { it.optString("value").contains(value, ignoreCase = true) }
            // Nothing in the demo is shared or has duplicates.
            key == "shared_by__id" -> false
            key == "has_duplicates" -> !value.isTrue()
            key.startsWith(
                "archive_serial_number",
            ) -> matchesAsn(document.longOrNull("archive_serial_number"), key, value)
            DATE_PARAM.matches(key) -> matchesDate(document, key, value)
            else -> matchesReference(document, key, value, ids)
        }
    }

    /** `correspondent__id`, `correspondent__id__in`, `correspondent__id__none`, `correspondent__isnull`, ... */
    private fun matchesReference(
        document: JSONObject,
        key: String,
        value: String,
        ids: List<Long>,
    ): Boolean {
        val field = key.substringBefore("__")
        if (field !in REFERENCE_FIELDS) return true
        val id = document.longOrNull(field)
        return when (key.removePrefix(field)) {
            "__id" -> id == ids.firstOrNull()
            "__id__in" -> id in ids
            "__id__none" -> id !in ids
            "__isnull" -> (id == null) == value.isTrue()
            else -> true
        }
    }

    private fun fieldIds(document: JSONObject): List<Long> =
        document.getJSONArray("custom_fields").objects().map { it.getLong("field") }

    private fun matchesAsn(
        asn: Long?,
        key: String,
        value: String,
    ): Boolean =
        when (key) {
            "archive_serial_number" -> asn == value.toLongOrNull()
            "archive_serial_number__isnull" -> (asn == null) == value.isTrue()
            "archive_serial_number__gt" -> asn != null && asn > (value.toLongOrNull() ?: return true)
            "archive_serial_number__lt" -> asn != null && asn < (value.toLongOrNull() ?: return true)
            else -> true
        }

    private fun matchesDate(
        document: JSONObject,
        key: String,
        value: String,
    ): Boolean {
        val match = DATE_PARAM.matchEntire(key) ?: return true
        val (field, op) = match.destructured
        val date = document.optString(field).take(10)
        return when (op) {
            "year" -> date.take(4) == value
            "month" -> date.substring(5, 7).toIntOrNull() == value.toIntOrNull()
            "day" -> date.substring(8, 10).toIntOrNull() == value.toIntOrNull()
            "gte" -> date >= value.take(10)
            "lte" -> date <= value.take(10)
            "gt" -> date > value.take(10)
            "lt" -> date < value.take(10)
            else -> true
        }
    }

    private fun containsText(
        document: JSONObject,
        text: String,
        content: Boolean,
    ): Boolean {
        val haystack = document.optString("title") + if (content) "\n" + document.optString("content") else ""
        return text.split(' ').filter { it.isNotBlank() }.all { haystack.contains(it, ignoreCase = true) }
    }

    private fun isSimilar(
        document: JSONObject,
        other: JSONObject,
    ): Boolean {
        if (document.getLong("id") == other.getLong("id")) return false
        val tags = other.getJSONArray("tags").longs()
        return document.getJSONArray("tags").longs().any { it in tags } ||
            document.longOrNull("correspondent")?.let { it == other.longOrNull("correspondent") } == true
    }

    private fun searchHit(
        document: JSONObject,
        text: String,
        rank: Int,
    ): JSONObject {
        val words = text.split(' ').filter { it.isNotBlank() }
        val line =
            document.optString("content").lines().firstOrNull { line -> words.any { line.contains(it, true) } }
                ?: document.optString("title")
        val highlighted =
            words.fold(
                line,
            ) { acc, word ->
                acc.replace(Regex(Regex.escape(word), RegexOption.IGNORE_CASE)) {
                    "<b>${it.value}</b>"
                }
            }
        return JSONObject()
            .put("score", 10.0 - rank)
            .put("highlights", highlighted)
            .put("note_highlights", JSONObject.NULL)
            .put("rank", rank + 1)
    }

    private fun sort(
        documents: List<JSONObject>,
        ordering: String?,
    ): List<JSONObject> {
        val field = ordering?.removePrefix("-")?.ifEmpty { null } ?: "created"
        val descending = ordering == null || ordering.startsWith("-")
        val key: (JSONObject) -> Comparable<*>? =
            when (field) {
                "title" -> { d -> d.optString("title").lowercase() }
                "archive_serial_number", "page_count" -> { d -> d.longOrNull(field) }
                "num_notes" -> { d -> d.getJSONArray("notes").length() }
                "correspondent__name", "document_type__name", "storage_path__name" -> { d ->
                    labelName(field.removeSuffix("__name"), d)
                }
                "created", "added", "modified" -> { d -> d.optString(field) }
                else -> { d -> d.getLong("id") }
            }
        val comparator =
            Comparator<JSONObject> { a, b ->
                val (x, y) = key(a) to key(b)
                when {
                    x == null && y == null -> 0
                    x == null -> 1
                    y == null -> -1
                    else -> compareValues(x, y)
                }
            }.thenBy { it.getLong("id") }
        return documents.sortedWith(if (descending) comparator.reversed() else comparator)
    }

    private fun labelName(
        field: String,
        document: JSONObject,
    ): String? {
        val id = document.longOrNull(field) ?: return null
        val collection = data.collections.getValue(field + "s")
        return collection.firstOrNull { it.getLong("id") == id }?.optString("name")?.lowercase()
    }

    private fun update(
        document: JSONObject,
        changes: JSONObject,
    ): JSONObject {
        for (key in changes.keys()) {
            if (key in EDITABLE_DOCUMENT_FIELDS) document.put(key, changes.get(key))
        }
        if (changes.has("created")) document.put("created_date", changes.getString("created").take(10))
        document.put("modified", Instant.now().toString())
        return document
    }

    private fun moveToTrash(id: Long) {
        val document = data.document(id) ?: return
        data.documents.remove(document)
        data.trash.add(document.put("deleted_at", Instant.now().toString()))
    }

    private fun bulkEdit(body: JSONObject?): Reply {
        body ?: return Reply.json(400, JSONObject().put("detail", "Missing body"))
        val ids = body.optJSONArray("documents")?.longs().orEmpty()
        val parameters = body.optJSONObject("parameters") ?: JSONObject()
        val documents = ids.mapNotNull(data::document)

        fun tags(document: JSONObject): MutableSet<Long> = document.getJSONArray("tags").longs().toMutableSet()

        fun setTags(
            document: JSONObject,
            tags: Set<Long>,
        ) = document.put("tags", JSONArray(tags.toList())).put("modified", Instant.now().toString())
        when (body.optString("method")) {
            "delete" -> ids.forEach(::moveToTrash)
            "add_tag" -> documents.forEach { setTags(it, tags(it) + parameters.getLong("tag")) }
            "remove_tag" -> documents.forEach { setTags(it, tags(it) - parameters.getLong("tag")) }
            "modify_tags" -> {
                val add = parameters.optJSONArray("add_tags")?.longs().orEmpty()
                val remove = parameters.optJSONArray("remove_tags")?.longs().orEmpty()
                documents.forEach { setTags(it, tags(it) - remove.toSet() + add) }
            }
            "set_correspondent", "set_document_type", "set_storage_path" -> {
                val field = body.optString("method").removePrefix("set_")
                documents.forEach {
                    it
                        .put(
                            field,
                            parameters.opt(field) ?: JSONObject.NULL,
                        ).put("modified", Instant.now().toString())
                }
            }
            "modify_custom_fields" -> documents.forEach { modifyCustomFields(it, parameters) }
        }
        return Reply.ok(JSONObject().put("result", "OK"))
    }

    /** `add_custom_fields` is a `{"<field id>": value}` object or a list of field IDs (added without a value). */
    private fun modifyCustomFields(
        document: JSONObject,
        parameters: JSONObject,
    ) {
        val values = parameters.optJSONObject("add_custom_fields")
        val add: Map<Long, Any> =
            values?.keys()?.asSequence()?.associate { it.toLong() to values.get(it) }
                ?: parameters.optJSONArray("add_custom_fields")?.longs()?.associateWith { JSONObject.NULL }
                ?: emptyMap()
        val remove =
            parameters
                .optJSONArray("remove_custom_fields")
                ?.longs()
                .orEmpty()
                .toSet()
        val kept = document.getJSONArray("custom_fields").objects().filter { it.getLong("field") !in remove + add.keys }
        val added = add.map { (field, value) -> JSONObject().put("field", field).put("value", value) }
        document.put("custom_fields", JSONArray(kept + added)).put("modified", Instant.now().toString())
    }

    private fun notes(
        document: JSONObject,
        request: Request,
        body: JSONObject?,
    ): Reply {
        val notes = document.getJSONArray("notes")
        when (request.method) {
            "POST" -> {
                val note =
                    JSONObject()
                        .put("id", data.newId())
                        .put("note", body?.optString("note").orEmpty())
                        .put("created", Instant.now().toString())
                        .put(
                            "user",
                            data.uiSettings.getJSONObject("user").let {
                                JSONObject()
                                    .put(
                                        "id",
                                        it.get("id"),
                                    ).put("username", it.get("username"))
                                    .put("first_name", "Demo")
                                    .put("last_name", "User")
                            },
                        )
                notes.put(note)
            }
            "DELETE" -> {
                val id = request.url.queryParameter("id")?.toLongOrNull()
                document.put("notes", JSONArray(notes.objects().filter { it.getLong("id") != id }))
            }
        }
        return Reply.ok(document.getJSONArray("notes"))
    }

    private fun metadata(document: JSONObject): JSONObject {
        val name = document.optString("original_file_name")
        val pdf = document.optString("mime_type") == "application/pdf"
        val checksum = document.getJSONArray("versions").getJSONObject(0).optString("checksum")
        return JSONObject()
            .put("original_checksum", checksum)
            .put("original_size", files.size(document, "download"))
            .put("original_mime_type", document.optString("mime_type"))
            .put("media_filename", name)
            .put("has_archive_version", pdf)
            .put("original_metadata", JSONArray())
            .put("archive_checksum", if (pdf) checksum else JSONObject.NULL)
            .put("archive_media_filename", if (pdf) name else JSONObject.NULL)
            .put("original_filename", name)
            .put("archive_size", if (pdf) files.size(document, "download") else JSONObject.NULL)
            .put("archive_metadata", JSONArray())
            .put("lang", "en")
    }

    private fun suggestions(document: JSONObject): JSONObject {
        fun idList(field: String) = JSONArray(listOfNotNull(document.longOrNull(field)))
        return JSONObject()
            .put("correspondents", idList("correspondent"))
            .put("tags", document.getJSONArray("tags"))
            .put("document_types", idList("document_type"))
            .put("storage_paths", idList("storage_path"))
            .put("dates", JSONArray())
    }

    private fun history(document: JSONObject): JSONArray =
        JSONArray().put(
            JSONObject()
                .put("id", document.getLong("id"))
                .put("timestamp", document.optString("added"))
                .put("action", "create")
                .put("changes", JSONObject().put("title", JSONArray().put("None").put(document.optString("title"))))
                .put("actor", JSONObject().put("id", 1).put("username", "demo")),
        )

    private fun nextAsn(): Long =
        (data.documents + data.trash).mapNotNull { it.longOrNull("archive_serial_number") }.maxOrNull()?.plus(1) ?: 1

    private fun file(
        document: JSONObject,
        kind: String,
    ): Reply {
        val file = files.render(document, kind)
        val disposition = if (kind == "download") "attachment" else "inline"
        return Reply(
            200,
            file.bytes,
            file.contentType,
            listOf(
                "Content-Disposition" to "$disposition; filename=\"${file.name}\"",
            ),
        )
    }

    private fun uploadUnavailable(): Reply =
        Reply.json(403, JSONObject().put("detail", "Uploading is not available in the demo."))

    // Trash, tasks

    private fun trash(
        url: HttpUrl,
        body: JSONObject?,
        method: String,
    ): Reply {
        if (method == "GET") return page(url, data.trash.sortedByDescending { it.optString("deleted_at") })
        val ids = body?.optJSONArray("documents")?.longs() ?: data.trash.map { it.getLong("id") }
        val affected = data.trash.filter { it.getLong("id") in ids }
        data.trash.removeAll(affected)
        if (body?.optString("action") == "restore") {
            data.documents.addAll(affected.map { it.put("deleted_at", JSONObject.NULL) })
        }
        return Reply.ok(JSONObject().put("result", "OK").put("doc_ids", JSONArray(affected.map { it.getLong("id") })))
    }

    private fun tasks(path: List<String>): Reply =
        if (path.firstOrNull() == "acknowledge") {
            Reply.ok(JSONObject().put("result", 0))
        } else {
            Reply.ok(
                JSONObject()
                    .put(
                        "count",
                        0,
                    ).put("next", JSONObject.NULL)
                    .put("previous", JSONObject.NULL)
                    .put("results", JSONArray()),
            )
        }

    // Labels, custom fields and saved views

    private fun collection(
        name: String,
        url: HttpUrl,
        path: List<String>,
        body: JSONObject?,
        method: String,
    ): Reply {
        val items = data.collections.getValue(name)
        val id = path.firstOrNull()?.toLongOrNull()
        if (id == null) {
            return when (method) {
                "GET" -> page(url, listCollection(name, url, items))
                "POST" -> {
                    val item = (body ?: JSONObject()).put("id", data.newId())
                    defaults(name, item)
                    items.add(item)
                    Reply.json(201, withCounts(name, item))
                }
                else -> Reply.notFound()
            }
        }
        val item = items.firstOrNull { it.getLong("id") == id } ?: return Reply.notFound()
        return when (method) {
            "GET" -> Reply.ok(withCounts(name, item))
            "PATCH", "PUT" -> {
                body?.keys()?.forEach { key -> if (key != "id") item.put(key, body.get(key)) }
                Reply.ok(withCounts(name, item))
            }
            "DELETE" -> {
                // Like the server, deleting a tag deletes its sub-tags too.
                val ids = if (name == "tags") descendantsAndSelf(id) else setOf(id)
                items.removeAll { it.getLong("id") in ids }
                ids.forEach { removeReferences(name, it) }
                Reply.noContent
            }
            else -> Reply.notFound()
        }
    }

    /** With the `name__icontains` filter and `ordering` (by name by default), like the server. */
    private fun listCollection(
        name: String,
        url: HttpUrl,
        items: List<JSONObject>,
    ): List<JSONObject> {
        val contains = url.queryParameter("name__icontains")?.takeIf { it.isNotBlank() }
        val ordering = url.queryParameter("ordering")?.ifEmpty { null } ?: "name"
        val field = ordering.removePrefix("-")
        val counted =
            items
                .filter {
                    contains == null || it.optString("name").contains(contains, ignoreCase = true)
                }.map { withCounts(name, it) }
        val sorted =
            when (field) {
                "document_count" -> counted.sortedBy { it.optInt(field) }
                "id" -> counted.sortedBy { it.getLong("id") }
                else -> counted.sortedBy { it.optString(field).lowercase() }
            }
        return if (ordering.startsWith("-")) sorted.reversed() else sorted
    }

    private fun defaults(
        name: String,
        item: JSONObject,
    ) {
        if (name == "saved_views" || name == "custom_fields") {
            if (name == "saved_views") item.put("owner", 1).put("user_can_change", true)
            return
        }
        val label = item.optString("name")
        item
            .put("slug", label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'))
            .putIfAbsent("match", "")
            .putIfAbsent("matching_algorithm", 6)
            .putIfAbsent("is_insensitive", true)
            .put("owner", 1)
            .put("user_can_change", true)
        if (name == "tags") {
            item
                .putIfAbsent("color", "#a6cee3")
                .putIfAbsent("text_color", "#000000")
                .putIfAbsent("is_inbox_tag", false)
                .putIfAbsent("parent", JSONObject.NULL)
        }
        if (name == "storage_paths") item.putIfAbsent("path", "{{ title }}")
    }

    /** Document counts and tag children are computed from the current data, like the server does. */
    private fun withCounts(
        name: String,
        item: JSONObject,
    ): JSONObject {
        if (name == "saved_views") return item
        val id = item.getLong("id")
        val copy = item.copy()
        val count =
            when (name) {
                "tags" -> data.documents.count { id in it.getJSONArray("tags").longs() }
                "custom_fields" ->
                    data.documents.count { d ->
                        d.getJSONArray("custom_fields").objects().any {
                            it.getLong("field") ==
                                id
                        }
                    }
                else -> data.documents.count { it.longOrNull(name.removeSuffix("s")) == id }
            }
        copy.put("document_count", count)
        if (name == "correspondents") {
            val last =
                data.documents
                    .filter {
                        it.longOrNull(
                            "correspondent",
                        ) == id
                    }.maxOfOrNull { it.optString("created") }
            copy.put("last_correspondence", last ?: JSONObject.NULL)
        }
        if (name == "tags") {
            val children = data.collections.getValue("tags").filter { it.longOrNull("parent") == id }
            copy.put("children", JSONArray(children.map { withCounts(name, it) }))
        }
        return copy
    }

    private fun removeReferences(
        name: String,
        id: Long,
    ) {
        for (document in data.documents + data.trash) {
            when (name) {
                "tags" -> document.put("tags", JSONArray(document.getJSONArray("tags").longs() - id))
                "custom_fields" ->
                    document.put(
                        "custom_fields",
                        JSONArray(
                            document.getJSONArray("custom_fields").objects().filter {
                                it.getLong("field") !=
                                    id
                            },
                        ),
                    )
                "saved_views" -> Unit
                else ->
                    if (document.longOrNull(name.removeSuffix("s")) ==
                        id
                    ) {
                        document.put(name.removeSuffix("s"), JSONObject.NULL)
                    }
            }
        }
    }

    private fun descendantsAndSelf(tagId: Long): Set<Long> {
        val tags = data.collections.getValue("tags")
        val result = mutableSetOf(tagId)
        var added = true
        while (added) {
            added = tags.filter { it.longOrNull("parent") in result }.map { it.getLong("id") }.any(result::add)
        }
        return result
    }

    // Helpers

    /** A page of [items] for the `page` and `page_size` parameters, with `next`/`previous` links like DRF. */
    private fun page(
        url: HttpUrl,
        items: List<JSONObject>,
    ): Reply {
        val size = url.queryParameter("page_size")?.toIntOrNull()?.coerceIn(1, 100_000) ?: DEFAULT_PAGE_SIZE
        val page = url.queryParameter("page")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val from = (page - 1) * size
        if (from > 0 && from >= items.size) return Reply.json(404, JSONObject().put("detail", "Invalid page."))
        val pageItems = items.drop(from).take(size)

        fun link(number: Int) =
            url
                .newBuilder()
                .setQueryParameter("page", number.toString())
                .build()
                .toString()
        return Reply.ok(
            JSONObject()
                .put("count", items.size)
                .put("next", if (from + size < items.size) link(page + 1) else JSONObject.NULL)
                .put("previous", if (page > 1) link(page - 1) else JSONObject.NULL)
                .put("all", JSONArray(items.map { it.getLong("id") }))
                .put("results", JSONArray(pageItems)),
        )
    }

    private fun inboxTagIds(): List<Long> =
        data.collections
            .getValue("tags")
            .filter { it.optBoolean("is_inbox_tag") }
            .map { it.getLong("id") }

    private fun Request.bodyJson(): JSONObject? {
        val body = body ?: return null
        // Only JSON bodies are read: a multipart upload is refused without reading the file.
        if (body.contentType()?.subtype != "json") return null
        val text = Buffer().also(body::writeTo).readUtf8()
        return if (text.trimStart().startsWith("{")) JSONObject(text) else null
    }

    private fun String.isTrue() = this == "1" || equals("true", ignoreCase = true)

    private fun JSONObject.putIfAbsent(
        name: String,
        value: Any,
    ): JSONObject = if (has(name)) this else put(name, value)

    companion object {
        const val DEMO_TOKEN = "demo-token"
        private const val API_VERSION = "10"
        private const val SERVER_VERSION = "3.4.2"
        private const val DEFAULT_PAGE_SIZE = 25
        private val FILE_KINDS = setOf("thumb", "preview", "download")
        private val SEARCH_PARAMS = listOf("text", "query", "title_search")
        private val IGNORED_PARAMS =
            setOf(
                "page",
                "page_size",
                "ordering",
                "truncate_content",
                "more_like_id",
                "custom_field_query",
                "fields",
                "full_perms",
            )
        private val REFERENCE_FIELDS = setOf("correspondent", "document_type", "storage_path", "owner")
        private val DATE_PARAM = Regex("""(created|added|modified)(?:__date)?__(gte|lte|gt|lt|year|month|day)""")
        private val EDITABLE_DOCUMENT_FIELDS =
            setOf(
                "title",
                "created",
                "correspondent",
                "document_type",
                "storage_path",
                "tags",
                "archive_serial_number",
                "custom_fields",
                "owner",
            )
    }
}
