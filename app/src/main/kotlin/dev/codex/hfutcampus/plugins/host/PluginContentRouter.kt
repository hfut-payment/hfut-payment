package dev.codex.hfutcampus

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/** Read-only Android URI slot. The installed extension owns the URI schema and caller policy.  */
class PluginContentRouter : ContentProvider() {
    override fun onCreate(): Boolean {
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        args: Array<out String>?,
        sort: String?
    ): Cursor? {
        val local: LocalPlugins = LocalPlugins.Companion.get(checkNotNull(context))
        val bundle = local.endpoint(uri.authority) ?: return null
        val plugin = NativePlugins.get(checkNotNull(context), bundle)
        if (plugin == null) return null
        val result = plugin.query(uri, projection, selection, args, sort)
        if (!local.active(bundle) && result != null) {
            result.close()
            return null
        }
        return result
    }

    override fun getType(uri: Uri): String? {
        val plugin = NativePlugins.get(
            checkNotNull(context),
            LocalPlugins.Companion.get(checkNotNull(context)).endpoint(uri.getAuthority())
        )
        return if (plugin == null) null else plugin.type(uri)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException("Read only")
    }

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
        throw UnsupportedOperationException("Read only")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        args: Array<out String>?
    ): Int {
        throw UnsupportedOperationException("Read only")
    }
}
