package app.jacket.mobile.lampac

import org.json.JSONObject
import java.io.IOException

/**
 * Authentication fields for a single upstream JacRed tracker.
 * Passwords are kept only in the app-private init.conf and are never logged.
 */
data class LampacTrackerAuth(
    val id: String,
    val enabled: Boolean,
    val username: String,
    val password: String
)

/** Safe, minimal read/update adapter for JacRed.Jackett in Lampac init.conf. */
object LampacTrackerConfig {
    val TRACKER_IDS: List<String> = listOf(
        "Rutracker", "Toloka", "Animelayer", "Selezen",
        "Lostfilm", "Kinozal", "NNMClub"
    )

    fun read(content: String): List<LampacTrackerAuth> {
        val root = parseConfig(content)
        val jackett = root.optJSONObject("JacRed")?.optJSONObject("Jackett")
        return TRACKER_IDS.map { id ->
            val tracker = jackett?.optJSONObject(id)
            val login = tracker?.optJSONObject("login")
            LampacTrackerAuth(
                id = id,
                enabled = tracker?.optBoolean("enable", true) ?: true,
                username = login?.optString("u").orEmpty(),
                password = login?.optString("p").orEmpty()
            )
        }
    }

    /** Updates auth flags and credentials while preserving all other settings. */
    fun update(content: String, settings: List<LampacTrackerAuth>): String {
        val root = parseConfig(content)
        val jacRed = root.optJSONObject("JacRed") ?: JSONObject().also { root.put("JacRed", it) }
        if (!jacRed.has("typesearch")) jacRed.put("typesearch", "jackett")
        if (!jacRed.has("disableJackett")) jacRed.put("disableJackett", false)
        val jackett = jacRed.optJSONObject("Jackett") ?: JSONObject().also { jacRed.put("Jackett", it) }
        val seen = HashSet<String>()

        settings.forEach { item ->
            require(item.id in TRACKER_IDS) { "Неизвестный торрент-трекер: ${item.id}" }
            require(seen.add(item.id)) { "Повторяется настройка трекера: ${item.id}" }
            val tracker = jackett.optJSONObject(item.id) ?: JSONObject()
            tracker.put("enable", item.enabled)
            val login = tracker.optJSONObject("login") ?: JSONObject()
            val username = item.username.trim()
            if (username.isBlank()) login.remove("u") else login.put("u", username)
            if (item.password.isBlank()) login.remove("p") else login.put("p", item.password)
            if (login.length() == 0) tracker.remove("login") else tracker.put("login", login)
            jackett.put(item.id, tracker)
        }

        val updated = root.toString(2)
        require(updated.toByteArray(Charsets.UTF_8).size <= MAX_CONFIG_BYTES) {
            "Обновлённый init.conf превышает лимит 2 МиБ"
        }
        return updated
    }

    private fun parseConfig(content: String): JSONObject {
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= MAX_CONFIG_BYTES) {
            "init.conf пуст или превышает лимит 2 МиБ"
        }
        return try {
            JSONObject(content)
        } catch (error: Exception) {
            throw IOException("Не удалось прочитать JSON init.conf Lampac", error)
        }
    }

    private const val MAX_CONFIG_BYTES = 2 * 1024 * 1024
}
