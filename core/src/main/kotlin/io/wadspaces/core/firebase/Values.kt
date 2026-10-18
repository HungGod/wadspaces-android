package io.wadspaces.core.firebase

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Firestore's typed values (REST form) to and from JSON: wad-firebase's
 * values.rs. A timestamp has no JSON form: `{"$time": <epoch ms>}` stands
 * for one going in; one coming out is its RFC 3339 text.
 */
object Values {
    fun time(ms: Long): JsonObject = buildJsonObject { put("\$time", ms) }

    private fun isInteger(p: JsonPrimitive) = !p.isString && p.longOrNull != null && p.content.none { it == '.' || it == 'e' || it == 'E' }

    fun toValue(v: JsonElement): JsonElement = when (v) {
        JsonNull -> buildJsonObject { put("nullValue", JsonNull) }
        is JsonPrimitive -> when {
            v.isString -> buildJsonObject { put("stringValue", v.content) }
            v.booleanOrNull != null -> buildJsonObject { put("booleanValue", v.booleanOrNull) }
            isInteger(v) -> buildJsonObject { put("integerValue", v.content) }
            else -> buildJsonObject { put("doubleValue", v.doubleOrNull) }
        }
        is JsonArray -> buildJsonObject {
            put("arrayValue", buildJsonObject { put("values", JsonArray(v.map(::toValue))) })
        }
        is JsonObject -> {
            val ms = (v["\$time"] as? JsonPrimitive)?.longOrNull
            if (v.size == 1 && ms != null) buildJsonObject { put("timestampValue", rfc3339(ms)) }
            else buildJsonObject { put("mapValue", buildJsonObject { put("fields", toFields(v)) }) }
        }
    }

    fun toFields(o: JsonObject): JsonObject = JsonObject(o.mapValues { toValue(it.value) })

    fun fromValue(v: JsonElement): JsonElement {
        val o = v as? JsonObject ?: return JsonNull
        val (kind, x) = o.entries.firstOrNull() ?: return JsonNull
        return when (kind) {
            "nullValue" -> JsonNull
            "integerValue" -> (x as? JsonPrimitive)?.contentOrNull?.toLongOrNull()?.let(::JsonPrimitive) ?: JsonNull
            "mapValue" -> fromFields((x as? JsonObject)?.get("fields") ?: JsonNull)
            "arrayValue" -> JsonArray(((x as? JsonObject)?.get("values") as? JsonArray)?.map(::fromValue) ?: emptyList())
            else -> x
        }
    }

    fun fromFields(fields: JsonElement): JsonObject =
        JsonObject((fields as? JsonObject)?.mapValues { fromValue(it.value) } ?: emptyMap())

    /** A document's id: the last segment of its name. */
    fun docId(doc: JsonElement): String =
        ((doc as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull?.substringAfterLast('/') ?: ""

    /** A document as the apps have it: its fields, and its id. */
    fun plain(doc: JsonElement): JsonObject =
        JsonObject(fromFields(doc.jsonObject["fields"] ?: JsonNull) + ("id" to JsonPrimitive(docId(doc))))

    // Days from 1970-01-01 to a civil date and back (Howard Hinnant's algorithms).
    private fun daysFromCivil(y0: Long, m: Long, d: Long): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = Math.floorDiv(y, 400L)
        val yoe = y - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    private fun civilFromDays(z0: Long): Triple<Long, Long, Long> {
        val z = z0 + 719_468
        val era = Math.floorDiv(z, 146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        return Triple(yoe + era * 400 + (if (m <= 2) 1 else 0), m, d)
    }

    /** Epoch milliseconds as RFC 3339 UTC: 2026-10-02T09:41:06.129Z. */
    fun rfc3339(ms: Long): String {
        val days = Math.floorDiv(ms, 86_400_000L)
        val rest = Math.floorMod(ms, 86_400_000L)
        val (y, m, d) = civilFromDays(days)
        return "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ".format(
            y, m, d, rest / 3_600_000, rest / 60_000 % 60, rest / 1000 % 60, rest % 1000,
        )
    }

    /** A timestamp (a number, or RFC 3339 text at any offset) as epoch ms; 0 when it's neither. */
    fun toMs(v: JsonElement): Long {
        val p = v as? JsonPrimitive ?: return 0
        if (!p.isString) return p.doubleOrNull?.toLong() ?: 0
        return parseRfc3339(p.content) ?: 0
    }

    private fun parseRfc3339(s: String): Long? {
        if (s.length < 20 || s[4] != '-' || s[7] != '-' || s[10] !in "Tt " || s[13] != ':' || s[16] != ':') return null
        fun n(a: Int, b: Int) = s.substring(a, b).toLongOrNull()
        val y = n(0, 4) ?: return null
        val mo = n(5, 7) ?: return null
        val d = n(8, 10) ?: return null
        val h = n(11, 13) ?: return null
        val mi = n(14, 16) ?: return null
        val sec = n(17, 19) ?: return null
        var rest = s.substring(19)
        var milli = 0L
        if (rest.startsWith('.')) {
            val digits = rest.drop(1).takeWhile { it.isDigit() }
            milli = digits.padEnd(3, '0').take(3).toLong()
            rest = rest.drop(1 + digits.length)
        }
        val offset = when {
            rest == "Z" || rest == "z" -> 0L
            rest.length == 6 && (rest[0] == '+' || rest[0] == '-') -> {
                val v = (rest.substring(1, 3).toLongOrNull() ?: return null) * 60 + (rest.substring(4, 6).toLongOrNull() ?: return null)
                if (rest[0] == '-') -v else v
            }
            else -> return null
        }
        return (daysFromCivil(y, mo, d) * 86_400 + h * 3600 + mi * 60 + sec - offset * 60) * 1000 + milli
    }
}

