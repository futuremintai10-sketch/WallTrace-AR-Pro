package com.walltrace.ar.data

import com.walltrace.ar.model.SketchAdjustments
import org.json.JSONArray
import org.json.JSONObject

/**
 * A saved sketch: the imported image (copied into app storage) plus its adjustment settings.
 * Wall placement is intentionally not persisted: ARCore anchors are session-bound, so a reopened
 * project requires placing the sketch on the wall again.
 */
data class Project(
    val id: String,
    val name: String,
    val imageFileName: String,
    val createdAt: Long,
    val updatedAt: Long,
    val adjustments: SketchAdjustments
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("imageFileName", imageFileName)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("adjustments", adjustments.toJson())
    }

    companion object {
        fun fromJson(json: JSONObject): Project? {
            val id = json.optString("id", "")
            val image = json.optString("imageFileName", "")
            if (id.isBlank() || image.isBlank()) return null
            // Reject path traversal in stored file names.
            if (image.contains('/') || image.contains('\\') || image.contains("..")) return null
            return Project(
                id = id,
                name = json.optString("name", "Sketch").ifBlank { "Sketch" },
                imageFileName = image,
                createdAt = json.optLong("createdAt", 0L),
                updatedAt = json.optLong("updatedAt", 0L),
                adjustments = SketchAdjustments.fromJson(json.optJSONObject("adjustments"))
            )
        }

        fun listToJson(projects: List<Project>): String =
            JSONArray().apply { projects.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(text: String): List<Project> {
            if (text.isBlank()) return emptyList()
            val array = JSONArray(text)
            val result = ArrayList<Project>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                fromJson(obj)?.let { result.add(it) }
            }
            return result
        }
    }
}
