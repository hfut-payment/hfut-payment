package dev.codex.hfutcampus

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class CommunityStore(context: Context, id: String, signer: String) {
    constructor(context: Context, plugin: PluginBundle) : this(context, plugin.id, plugin.signer)

    private val scope = "$id:$signer"
    private val prefs = context.getSharedPreferences("community-plugin-v2", Context.MODE_PRIVATE)
    private val alias = "community-plugin-v2:$scope"

    fun values(): JSONObject = JSONObject(prefs.getString("$scope:data", "{}") ?: "{}")

    fun configurationTag(key: String = ""): String = Base64.encodeToString(
        MessageDigest.getInstance("SHA-256").digest((if (key.isEmpty()) prefs.getString("$scope:data", "{}") ?: "{}"
            else values().opt(key)?.toString().orEmpty()).toByteArray()),
        Base64.NO_WRAP
    )

    fun put(values: JSONObject) {
        require(values.length() <= 64 && values.keys().asSequence().all(CommunitySpec.KEY::matches))
        val encoded = values.toString()
        require(encoded.toByteArray().size <= 8192)
        check(prefs.edit().putString("$scope:data", encoded).commit())
    }

    fun hasSecret(name: String, owner: String): Boolean =
        prefs.contains("$scope:secret:$name") && prefs.getString("$scope:owner:$name", null) == owner

    fun saveSecret(name: String, value: String, owner: String) {
        require(value.length in 1..128)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD("$scope:$name:$owner".toByteArray())
        val encrypted = Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        check(prefs.edit().putString("$scope:secret:$name", "$iv:$encrypted")
            .putString("$scope:owner:$name", owner).commit())
    }

    fun secret(name: String, owner: String): String {
        require(hasSecret(name, owner)) { "请为当前账户保存支付密码" }
        val encoded = requireNotNull(prefs.getString("$scope:secret:$name", null)) { "请先保存支付密码" }
        val (iv, ciphertext) = encoded.split(':', limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        cipher.updateAAD("$scope:$name:$owner".toByteArray())
        return String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    fun deleteSecret(name: String) {
        check(prefs.edit().remove("$scope:secret:$name").remove("$scope:owner:$name").commit())
    }

    fun clear() {
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith("$scope:") }.forEach(edit::remove)
        check(edit.commit())
        keyStore().deleteEntry(alias)
    }

    private fun key(create: Boolean): SecretKey {
        val old = keyStore().getKey(alias, null) as? SecretKey
        if (old != null) return old
        check(create) { "密码密钥已失效，请重新保存" }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()
        )
        return generator.generateKey()
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
