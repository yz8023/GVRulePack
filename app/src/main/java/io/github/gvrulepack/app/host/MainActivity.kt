package io.github.gvrulepack.app.host

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.github.gvrulepack.app.R
import io.github.gvrulepack.app.databinding.ActivityMainBinding
import io.github.gvrulepack.app.databinding.ItemAppBinding
import io.github.gvrulepack.app.shared.StoredApp

/**
 * 宿主 UI（Material 3）：导入 GodViewer 导出的规则 JSON，查看/管理已导入规则。
 *
 * 使用流程（界面顶部也有说明）：
 * 1. 「导入规则 JSON」选择 GodViewer 导出的 bundle 文件
 * 2. 在 LSPosed 中勾选列表中应用的作用域（推荐应用会自动带出，见 README）
 * 3. 强制停止并重新打开目标应用 → 规则自动重放
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
        binding.btnScope.setOnClickListener { openLsposedScope() }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    // ------------------------------------------------------------- 列表

    private fun refreshList() {
        val apps = store.listApps()
        binding.emptyState.visibility = if (apps.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        binding.sectionCount.text = if (apps.isEmpty()) "" else "${apps.size} 个"
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
            if (!installed) append("（未安装）")
        }
        item.itemLabel.alpha = if (installed) 1f else 0.55f

        item.itemPackage.text = app.packageName
        item.itemStats.text = "视图规则 ${app.viewRules.size} · 弹窗规则 ${app.dialogRules.size}"

        // 应用图标：装了取真实图标，未安装用模块眼睛占位
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

        item.root.setOnClickListener { confirmDelete(app) }
        item.btnDelete.setOnClickListener { confirmDelete(app) }
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
                append("\n接下来：在 LSPosed 勾选上述应用的作用域（本页可一键跳转），")
                append("强制停止后重新打开目标应用即可生效。")
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
            .setMessage("删除 ${app.label ?: app.packageName} 的全部规则？\n（删除后需重启目标应用恢复原样）")
            .setPositiveButton("删除") { _, _ ->
                store.deleteApp(app.packageName)
                toast("已删除")
                refreshList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 尽力跳转 LSPosed 作用域页；失败则退到系统应用信息页。 */
    private fun openLsposedScope() {
        val candidates = listOf(
            "org.lsposed.manager" to "org.lsposed.manager.ui.activity.MainActivity",
            "org.lsposed.manager" to "org.lsposed.manager.ui.WelcomeActivity",
            "de.robv.android.xposed.manager" to "de.robv.android.xposed.manager.MainActivity",
        )
        for ((pkg, cls) in candidates) {
            val intent = Intent().setClassName(pkg, cls)
            if (packageManager.resolveActivity(intent, 0) != null) {
                runCatching { startActivity(intent) }
                    .onSuccess { return }
                    .onFailure { /* 试下一个 */ }
            }
        }
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
            )
        }.onFailure { toast("无法打开作用域设置，请手动在 LSPosed 中勾选") }
    }

    private fun isInstalled(packageName: String): Boolean = runCatching {
        packageManager.getPackageInfo(packageName, 0)
    }.isSuccess

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
