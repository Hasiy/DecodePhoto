/* Copyright 2026 Raw Photo Viewer contributors. */
package top.hasiy.rawviewer

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.hasiy.rawviewer.raw.RawRenderOptions
import top.hasiy.rawviewer.raw.RawWhiteBalance

data class PhotoSession(val uri: Uri, val videoUri: Uri? = null, val format: String,
    val openedAt: Long = System.currentTimeMillis(), val mode: DecodeMode = DecodeMode.AUTO,
    val options: RawRenderOptions = RawRenderOptions())

/** Only Keystore-encrypted document identifiers are persisted; backup is disabled in the manifest. */
class PhotoSessionStore(context: Context, namespace: String = "photo_sessions") {
    private val preferences = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    suspend fun read(): List<PhotoSession> = withContext(Dispatchers.IO) {
        mutex.withLock { readStored() }
    }
    suspend fun remember(session: PhotoSession): List<PhotoSession> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = (listOf(session) + readStored().filter { it.uri != session.uri }).take(MAX_RECENT)
            writeStored(list)
            list
        }
    }
    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock { preferences.edit().clear().commit(); Unit }
    }

    private fun readStored(): List<PhotoSession> {
        val encoded = preferences.getString("data", null) ?: return emptyList()
        return try {
            val packet = Base64.decode(encoded, Base64.NO_WRAP)
            require(packet.size > IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, packet.copyOfRange(0, IV_BYTES)))
            val array = JSONArray(String(cipher.doFinal(packet.copyOfRange(IV_BYTES, packet.size)), Charsets.UTF_8))
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                PhotoSession(Uri.parse(item.getString("uri")),
                    item.optString("video").takeIf { it.isNotBlank() }?.let(Uri::parse),
                    item.getString("format"), item.getLong("time"),
                    DecodeMode.valueOf(item.optString("mode", DecodeMode.AUTO.name)),
                    RawRenderOptions(item.optDouble("ev", 0.0).toFloat(),
                        RawWhiteBalance.valueOf(item.optString("wb", RawWhiteBalance.CAMERA.name)),
                        item.optBoolean("half", true)))
            }.take(MAX_RECENT)
        } catch (error: Exception) {
            Log.w(TAG, "Unable to restore encrypted session: ${error.javaClass.simpleName}")
            preferences.edit().remove("data").commit()
            emptyList()
        }
    }
    private fun writeStored(list: List<PhotoSession>) {
        val array = JSONArray()
        list.forEach { session -> array.put(JSONObject().apply {
            put("uri", session.uri.toString()); put("video", session.videoUri?.toString().orEmpty())
            put("format", session.format); put("time", session.openedAt); put("mode", session.mode.name)
            put("ev", session.options.exposureEv.toDouble()); put("wb", session.options.whiteBalance.name)
            put("half", session.options.halfSize)
        }) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val packet = cipher.iv + cipher.doFinal(array.toString().toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("data", Base64.encodeToString(packet, Base64.NO_WRAP)).commit())
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private companion object {
        const val MAX_RECENT = 10
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val KEY_ALIAS = "decodephoto.sessions.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG = "PhotoSessionStore"
    }
}
