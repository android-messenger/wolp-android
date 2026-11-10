package org.wolpmessenger.push

import org.json.JSONObject

/** Validation of `Push` plugin arguments (S1 §5.2). Pure: unit-tested. */
object JsPush {
    const val MAX_NAMES = 5000
    private const val MAX_ID = 200
    private const val MAX_NAME = 120

    fun id(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_ID && it.none { c -> c.isISOControl() } }

    /** `{names: {id: name}}` → cleaned map, or null when the shape is wrong or too large. */
    fun names(o: JSONObject?): Map<String, String>? {
        o ?: return null
        if (o.length() > MAX_NAMES) return null
        val out = LinkedHashMap<String, String>()
        for (k in o.keys()) {
            val key = id(k) ?: return null
            val v = o.opt(k) as? String ?: return null
            val name = v.filterNot { it.isISOControl() }.trim().take(MAX_NAME)
            if (name.isNotEmpty()) out[key] = name
        }
        return out
    }
}
