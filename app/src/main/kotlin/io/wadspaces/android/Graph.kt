package io.wadspaces.android

import android.content.Context
import io.wadspaces.core.board.Packages
import io.wadspaces.core.builder.Catalog
import io.wadspaces.core.firebase.Auth
import io.wadspaces.core.firebase.FirebaseConfig
import io.wadspaces.core.firebase.Firestore
import io.wadspaces.core.firebase.Http
import io.wadspaces.core.firebase.SessionStore
import io.wadspaces.core.firebase.UrlHttp
import io.wadspaces.core.library.Account
import io.wadspaces.core.remote.RemoteWadd
import java.io.File

/** The session, in a file only this app can read (not backed up). */
class FileStore(private val file: File) : SessionStore {
    override fun load(): String? = runCatching { file.readText() }.getOrNull()
    override fun save(text: String?) {
        if (text == null) {
            file.delete()
        } else {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(file)
        }
    }
}

/** What the app is made of: the account, wadd's remote API, the catalog and package graph. */
class Graph(context: Context, val http: Http = UrlHttp(), store: SessionStore? = null) {
    val config = FirebaseConfig(BuildConfig.FIREBASE_API_KEY, BuildConfig.FIREBASE_PROJECT_ID, BuildConfig.FIREBASE_AUTH_DOMAIN)
    val configured get() = config.apiKey.isNotEmpty() && config.projectId.isNotEmpty()
    val auth = Auth(config, http, store ?: FileStore(File(context.noBackupFilesDir, "session.json")))
    val firestore = Firestore(config, http, auth::idToken)
    val account = Account(auth, firestore)
    val remote = RemoteWadd(http, auth::idToken)
    private fun read(context: Context, name: String) = context.assets.open(name).use { it.readBytes().decodeToString() }
    val catalog: Catalog = Catalog.parse(read(context, "catalog.json"))

    /** What naming a version 4 wadspace's set (and its templates) takes: the package graph. */
    val packages: Packages = Packages.parse(read(context, "packages.json"))
}
