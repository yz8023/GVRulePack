package io.github.gvrulepack.app.host

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.google.gson.Gson
import io.github.gvrulepack.app.shared.Constants

/**
 * 被注入的目标进程通过本 Provider 读取运行时导入的规则。
 *
 * 调用方式（注入侧）：
 *   content://io.github.gvrulepack.app.rules/rules/<package>
 * 返回单行单列（列名 [Constants.COLUMN_JSON]），内容为 [io.github.gvrulepack.app.shared.StoredApp] JSON。
 *
 * exported=true 且无权限：规则本身只是 UI 布局数据，无敏感信息；
 * 与上游 GodViewer 的 HostPrefsProvider 同样的跨进程姿势。
 */
class RulesProvider : ContentProvider() {

    private val gson = Gson()

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(arrayOf(Constants.COLUMN_JSON))
        val context = context ?: return cursor
        val segments = uri.pathSegments
        if (segments.size == 2 && segments[0] == "rules") {
            val pkg = segments[1]
            val app = RulePackStore(context).loadForPackage(pkg)
            if (app != null) {
                cursor.addRow(arrayOf(gson.toJson(app)))
            }
        }
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.gvrulepack.rules"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
