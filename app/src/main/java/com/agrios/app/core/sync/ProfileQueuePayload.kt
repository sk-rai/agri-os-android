package com.agrios.app.core.sync

import com.google.gson.Gson
import com.google.gson.JsonParser

/** Preserve form fields unchanged. Identity binding remains the backend's job. */
fun profileQueuePayload(payload: Map<String, Any?>): String = Gson().toJson(payload)

data class ProfileFieldPresence(val pin_code_present: Boolean, val assistance_mode_present: Boolean,
    val project_id_present: Boolean, val user_id_present: Boolean)

/** Deliberately returns only presence booleans, never a value or payload. */
fun profileFieldPresence(serialized: String): ProfileFieldPresence {
    val root = JsonParser.parseString(serialized).asJsonObject
    fun present(key: String) = root.has(key) && !root.get(key).isJsonNull
    return ProfileFieldPresence(present("pin_code"), present("assistance_mode"), present("project_id"), present("user_id"))
}
