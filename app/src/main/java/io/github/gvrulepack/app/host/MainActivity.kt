package io.github.gvrulepack.app.host

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.github.gvrulepack.app.R
import io.github.gvrulepack.app.databinding.ActivityMainBinding
import io.github.gvrulepack.app.databinding.ItemAppBinding
import io.github.gvrulepack.common.StoredApp

/**
 * 「GV 规则工坊」主界面：导入 GodViewer 规则 JSON，管理已导入规则，
 * 选中规则一键生成独立的 LSPosed 模块 APK（本 App 自身不是模块）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: RulePackStore

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            val text = runCatching {
                contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                toast("读取文件失败")
                return@registerForActivityResult
            }
            handleImport(text)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = RulePackStore(this)

        binding.btnImport.setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream", "*/*"))
        }
        binding.btnExport.setOnClickListener {
            if (store.listApps().isEmpty()) {
                toast("先导入规则再生成模块")
                return@setOnClickListener
            }
            startActivity(Intent(this, ExportActivity::class.java))
        }
        binding.btnClipboard.setOnClickListener { importFromClipboard() }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    /** 从剪贴板读取 GodViewer 导出的 JSON 并走同一套导入流程。 */
    private fun importFromClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            ?.trim()
        if (text.isNullOrEmpty()) {
            toast("剪贴板是空的，先复制 GodViewer 导出的 JSON")
            return
        }
        if (!text.startsWith("{")) {
            toast("剪贴板内容不是 JSON，需要 GodViewer 导出的规则文本")
            return
        }
        handleImport(text)
    }

    // ------------------------------------------------------------- 列表

    private fun refreshList() {
        val apps = store.listApps()
        binding.emptyState.visibility = if (apps.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        binding.sectionCount.text = if (apps.isEmpty()) "" else "${apps.size} 个"
        binding.btnExport.alpha = if (apps.isEmpty()) 0.5f else 1f
        binding.listContainer.removeAllViews()

        val inflater = LayoutInflater.from(this)
        for (app in apps) {
            val item = ItemAppBinding.inflate(inflater, binding.listContainer, false)
            fillRow(item, app)
            binding.listContainer.addView(item.root)
        }
    }

    private fun fillRow(item: ItemAppBinding, app: StoredApp) {
        val installed = isInstalled(app.packageName)

        item.itemLabel.text = buildString {
            append(app.label ?: app.packageName)
            if (!installed) append("（本机未安装）")
        }
        item.itemLabel.alpha = if (installed) 1f else 0.55f

        item.itemPackage.text = app.packageName
        item.itemStats.text = "视图规则 ${app.viewRules.size} · 弹窗规则 ${app.dialogRules.size}"

        runCatching {
            item.itemIcon.setImageDrawable(packageManager.getApplicationIcon(app.packageName))
            item.itemIcon.clearColorFilter()
        }.onFailure {
            item.itemIcon.setImageResource(R.drawable.ic_eye)
            item.itemIcon.setColorFilter(
                ContextCompat.getColor(this, android.R.color.darker_gray),
                android.graphics.PorterDuff.Mode.SRC_IN,
            )
        }

        item.root.setOnClickListener { showRowActions(app) }
        item.btnDelete.setOnClickListener { confirmDelete(app) }
    }

    private fun showRowActions(app: StoredApp) {
        val options = arrayOf("生成模块 APK（仅含此应用）", "删除规则")
        AlertDialog.Builder(this)
            .setTitle(app.label ?: app.packageName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> ExportActivity.start(this, listOf(app.packageName))
                    1 -> confirmDelete(app)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------- 行为

    private fun handleImport(json: String) {
        val result = store.importBundle(json)
        refreshList()
        val msg = buildString {
            if (result.imported.isNotEmpty()) {
                append("导入成功：\n")
                for (s in result.imported) {
                    append("· ${s.label ?: s.packageName} (${s.packageName})\n")
                    append("  视图规则 ${s.viewRuleCount} 条 · 弹窗规则 ${s.dialogRuleCount} 条\n")
                }
                append("\n下一步：点「生成模块 APK」，把生成的模块发给朋友，")
                append("安装后在 LSPosed 勾选作用域即可生效。")
            }
            if (result.errors.isNotEmpty()) {
                if (result.imported.isNotEmpty()) append("\n\n")
                append("提示：\n")
                result.errors.take(6).forEach { append("· $it\n") }
                if (result.errors.size > 6) append("· …等共 ${result.errors.size} 条")
            }
        }
        AlertDialog.Builder(this)
            .setTitle(if (result.ok) "导入完成" else "导入失败")
            .setMessage(msg)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun confirmDelete(app: StoredApp) {
        AlertDialog.Builder(this)
            .setTitle("删除规则")
            .setMessage("删除 ${app.label ?: app.packageName} 的全部规则？")
            .setPositiveButton("删除") { _, _ ->
                store.deleteApp(app.packageName)
                toast("已删除")
                refreshList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun isInstalled(packageName: String): Boolean = runCatching {
        packageManager.getPackageInfo(packageName, 0)
    }.isSuccess

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
