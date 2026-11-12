package dev.codex.hfutcampus

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.IBinder
import org.json.JSONObject

/** Version 1 of the interface for host-signed Android extensions.  */
interface NativePlugin {
    fun state(): JSONObject {
        return JSONObject()
    }

    fun command(action: String?, ui: Ui?) {
        throw IllegalArgumentException("Unknown plugin action")
    }

    fun restore(boot: Boolean): Boolean {
        return false
    }

    fun start(action: String?) {}
    fun stop() {}
    fun deactivated(disabled: Boolean) {}
    fun accountCleared() {}
    fun worker(): Worker? {
        return null
    }

    fun query(
        uri: Uri?,
        projection: Array<out String>?,
        selection: String?,
        arguments: Array<out String>?,
        order: String?
    ): Cursor? {
        throw UnsupportedOperationException("Plugin has no content endpoint")
    }

    fun type(uri: Uri?): String? {
        return null
    }

    interface Host {
        fun context(): Context?
        fun accountConfigured(): Boolean
        fun accountSaved(): Boolean
        fun accountRevision(): Long
        fun snapshotInstance(): String?
        fun snapshot(): CardSnapshot?
        fun balance(): BalanceSnapshot?
        fun setBalanceDemand(enabled: Boolean)
        fun observeBalance(listener: Runnable)
        fun unobserveBalance(listener: Runnable)
        fun codes(): PaymentCodes?
        fun observe(listener: CampusRuntime.Listener)
        fun unobserve(listener: CampusRuntime.Listener?)
        fun displayUntil(elapsedTime: Long)
        fun workerIntent(): Intent?
        fun running(): Boolean
        fun stopBackground()
    }

    interface Ui {
        fun activity(): Activity?
        fun sheet(title: String?, detail: String?): CampusSheet?
        fun detail(page: CampusSheet, label: String, value: String?)
        fun error(title: String?, detail: String?)
        fun confirm(title: String?, detail: String?, button: String?, action: Runnable)
        fun account()
        fun permission(permission: String, reason: String?, granted: Runnable)
        fun startBackground(action: String?)
    }

    interface Worker {
        fun binder(): IBinder?
        fun close()
    }
}
