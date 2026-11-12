package dev.codex.hfutcampus

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Android Keystore AES-GCM; never falls back to plaintext.  */
internal class CampusCredentials(context: Context) {
    private val prefs: SharedPreferences

    init {
        prefs = context.getSharedPreferences("campus-credentials", Context.MODE_PRIVATE)
    }

    @Throws(Exception::class)
    private fun key(): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        if (store.containsAlias(ALIAS)) return store.getKey(ALIAS, null) as SecretKey?
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()
        )
        return generator.generateKey()
    }

    @Throws(Exception::class)
    fun save(username: String?, password: String?, branch: String?) {
        val json = JSONObject()
        json.put("username", username)
        json.put("password", password)
        json.put("branch", branch)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = Base64.encodeToString(
            cipher.doFinal(
                json.toString().toByteArray(StandardCharsets.UTF_8)
            ), Base64.NO_WRAP
        )
        check(
            prefs.edit().putString("cipher", ciphertext)
                .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)).commit()
        ) { "storage" }
    }

    @Throws(Exception::class)
    fun load(): JSONObject? {
        val ciphertext: String = prefs.getString("cipher", "")!!
        if (ciphertext.isEmpty()) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP))
        )
        return JSONObject(
            String(
                cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)),
                StandardCharsets.UTF_8
            )
        )
    }

    fun clear() {
        check(prefs.edit().clear().commit()) { "storage" }
    }

    fun hasSaved() = prefs.contains("cipher")

    companion object {
        private const val ALIAS = "campus-card-credentials-v1"
        fun hasSaved(context: Context): Boolean {
            return context.getSharedPreferences("campus-credentials", Context.MODE_PRIVATE)
                .contains("cipher")
        }
    }
}
