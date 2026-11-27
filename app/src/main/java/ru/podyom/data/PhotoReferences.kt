package ru.podyom.data

import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import ru.podyom.App
import ru.podyom.core.PhotoMatcher

@Serializable
data class PhotoReference(val features: PhotoMatcher.Template, val thumbnail: String? = null) {
    fun usable() = features.usable() && (thumbnail?.length ?: 0) <= 24_000
}

/** Reuse private Room rows, reserving their prefix. All writes are editor saves. */
class PhotoReferences(private val app: App) {
    suspend fun get(id: String?): PhotoReference? {
        if (!app.unlocked || id == null || !valid(id)) return null
        return withContext(Dispatchers.Default) {
            try {
                val row = app.privateDb.dao().profile("photo:$id") ?: return@withContext null
                if (row.json.length > 180_000) return@withContext null
                codec.decodeFromString<PhotoReference>(row.json).takeIf { it.usable() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { app.journal.report(TechnicalEvent.PHOTO_STORAGE_ERROR); null }
        }
    }

    suspend fun save(reference: PhotoReference): String {
        require(reference.usable()) { "Снимите эталон заново" }
        val id = UUID.randomUUID().toString()
        val json = withContext(Dispatchers.Default) { codec.encodeToString(reference) }
        app.privateDb.dao().putProfile(ProfileRow("photo:$id", json))
        return id
    }

    suspend fun removeUnused(privateKeysInUse: Set<String>, photos: List<String>) {
        if (!app.unlocked) return
        val dao = app.privateDb.dao()
        val referenced = privateKeysInUse.mapNotNullTo(HashSet()) { id ->
            dao.profile(id)?.let { codec.decodeFromString<ru.podyom.core.PrivateAlarm>(it.json).photoId }
        }
        photos.filter { it.removePrefix("photo:") !in referenced }.forEach { dao.deletePhoto(it) }
    }

    private fun valid(id: String) = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
}
