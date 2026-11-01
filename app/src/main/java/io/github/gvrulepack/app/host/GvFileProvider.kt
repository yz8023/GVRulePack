package io.github.gvrulepack.app.host

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * 轻量文件分享 Provider：只暴露 `cacheDir/exports/` 下的导出文件。
 *
 * 不使用 androidx FileProvider 的 meta-data/XML 加载机制——R8 构建管线曾把
 * `getFileProviderPathsMetaData` 裁剪成「无条件抛 Missing meta-data 异常」，
 * 导致 App 启动即崩。本类零反射、零资源依赖，路径映射写死，行为完全可预测。
 */
class GvFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw IllegalStateException("no context")
        require(uri.pathSegments.size == 2 && uri.pathSegments[0] == EXPORT_DIR) { "bad uri: $uri" }
        val base = File(ctx.cacheDir, EXPORT_DIR)
        val file = File(base, uri.lastPathSegment!!)
        require(file.canonicalPath.startsWith(base.canonicalPath + File.separator)) { "path escape: $uri" }
        val flags = if (mode.contains("w")) {
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
        } else {
            ParcelFileDescriptor.MODE_READ_ONLY
        }
        return ParcelFileDescriptor.open(file, flags)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val EXPORT_DIR = "exports"

        /** `content://<pkg>.fileprovider/exports/<name>` */
        fun uriFor(context: Context, file: File): Uri =
            Uri.Builder()
                .scheme("content")
                .authority(context.packageName + ".fileprovider")
                .appendPath(EXPORT_DIR)
                .appendPath(file.name)
                .build()
    }
}
