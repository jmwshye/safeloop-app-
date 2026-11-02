package com.safeloop.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SessionUser(val uid: String, val name: String, val email: String)
internal data class NestSession(val user: SessionUser, val token: String, val expiresAt: Long, val id: String)

/** Session contents are encrypted by an Android Keystore key, excluded from backup. */
internal class NestSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("nest-auth", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): NestSession? = synchronized(lock) {
        val saved = prefs.getString("session", null) ?: return@synchronized null
        try {
            val fields = saved.split(':', limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(fields[0], Base64.NO_WRAP)))
            val json = JSONObject(String(cipher.doFinal(Base64.decode(fields[1], Base64.NO_WRAP)), Charsets.UTF_8))
            val session = NestSession(SessionUser(json.getString("uid"), json.getString("name"), json.getString("email")), json.getString("token"), json.getLong("expiresAt"), json.getString("id"))
            if (session.expiresAt <= System.currentTimeMillis()) { prefs.edit().clear().commit(); null } else session
        } catch (_: Exception) { prefs.edit().clear().commit(); null }
    }
    fun save(session: NestSession) = synchronized(lock) {
        val json = JSONObject().put("uid", session.user.uid).put("name", session.user.name).put("email", session.user.email)
            .put("token", session.token).put("expiresAt", session.expiresAt).put("id", session.id)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("session", Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
    }
    fun clear(expectedToken: String? = null): Boolean = synchronized(lock) {
        val current = read()
        if (expectedToken != null && current != null && current.token != expectedToken) return@synchronized false
        prefs.edit().clear().commit()
    }
    companion object { private val lock = Any(); private const val alias = "safeloop-nest-session-v1" }
}
