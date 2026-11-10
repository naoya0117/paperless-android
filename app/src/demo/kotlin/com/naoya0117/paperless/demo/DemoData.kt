package com.naoya0117.paperless.demo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The demo server's data, loaded from `assets/demo/data.json` (shaped like the API v10 responses) and changed
 * in memory only: every start of the app begins with the same data.
 */
class DemoData internal constructor(
    json: JSONObject,
) {
    val documents: MutableList<JSONObject> = json.objects("documents")
    val trash: MutableList<JSONObject> = json.objects("trash")
    val uiSettings: JSONObject = json.getJSONObject("ui_settings")

    /** Label-like collections by their API path segment. */
    val collections: Map<String, MutableList<JSONObject>> =
        COLLECTIONS.associateWith { json.objects(it) }

    private var nextId = (documents + trash + collections.values.flatten()).maxOf { it.getLong("id") } + 1

    fun newId(): Long = nextId++

    fun document(id: Long): JSONObject? = documents.firstOrNull { it.getLong("id") == id }

    companion object {
        val COLLECTIONS =
            listOf("tags", "correspondents", "document_types", "storage_paths", "custom_fields", "saved_views")

        fun load(context: Context): DemoData =
            DemoData(
                JSONObject(
                    context.assets
                        .open("demo/data.json")
                        .bufferedReader()
                        .use { it.readText() },
                ),
            )
    }
}

internal fun JSONObject.objects(name: String): MutableList<JSONObject> = getJSONArray(name).objects()

internal fun JSONArray.objects(): MutableList<JSONObject> = MutableList(length()) { getJSONObject(it) }

internal fun JSONArray.longs(): List<Long> = List(length()) { getLong(it) }

/** A deep copy, so that responses never share objects with the data. */
internal fun JSONObject.copy(): JSONObject = JSONObject(toString())

/** The value, or null for a missing key or JSON null. */
internal fun JSONObject.longOrNull(name: String): Long? = if (isNull(name)) null else optLong(name)

internal fun JSONObject.stringOrNull(name: String): String? = if (isNull(name)) null else optString(name)
