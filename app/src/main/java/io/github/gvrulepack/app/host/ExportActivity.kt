package io.github.gvrulepack.app.host

import android.app.ProgressDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import io.github.gvrulepack.app.export.ModuleApkExporter
import io.github.gvrulepack.common.BundleApp
import io.github.gvrulepack.common.RuleFormat
import java.io.File
import java.util.concurrent.Executors

/**
 * 「生成独立模块 APK」配置页：
 * 选应用 → 填模块名/包名/版本 → 选签名 → 生成 → 保存/分享。
 */
class ExportActivity : AppCompatActivity() {

    private lateinit var store: RulePackStore

    private val checks = LinkedHashMap<String, CheckBox>()
    private lateinit var etName: EditText
    private lateinit var etPackage: EditText
    private lateinit var etVersionName: EditText
    private lateinit var etVersionCode: EditText
    private lateinit var switchCustomSign: SwitchMaterial
    private lateinit var customSignBlock: LinearLayout
    private lateinit var etKeystorePassword: EditText
    private lateinit var etKeyAlias: EditText
    private lateinit var etKeyPassword: EditText
    private lateinit var btnGenerate: MaterialButton
    private lateinit var resultCard: MaterialCardView
    private lateinit var resultInfo: TextView

    private var customKeystoreFile: File? = null
    private var pendingApk: File? = null
    private var pendingName: String = "module.apk"

    private val executor = Executors.newSingleThreadExecutor()

    private val pickKeystoreLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            val dest = File(cacheDir, "picked_sign.jks")
            runCatching {
                contentResolver.openInputStream(uri)!!.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                customKeystoreFile = dest
                toast("已选择密钥库：${dest.name}（${dest.length() / 1024} KB）")
            }.onFailure {
                toast("读取密钥库失败：${it.message}")
            }
        }

    private val saveApkLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")) { uri ->
            uri ?: return@registerForActivityResult
            val apk = pendingApk ?: return@registerForActivityResult
            runCatching {
                apk.inputStream().use { input ->
                    contentResolver.openOutputStream(uri)!!.use { output -> input.copyTo(output) }
                }
                toast("已保存")
            }.onFailure {
                toast("保存失败：${it.message}")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RulePackStore(this)
        val preselect = intent.getStringArrayListExtra(EXTRA_PRESELECT) ?: emptyList()
        setContentView(buildContent(preselect))
        prefillPackage()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun buildContent(preselect: List<String>): View {
        val apps = store.listApps()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }

        root += title("生成独立模块 APK", 22f)
        root += label("把选中的规则打包成一个独立的 LSPosed 模块：发给朋友安装、勾选作用域即可，无需安装本工坊。", 13f, top = 6, secondary = true)

        // 应用选择
        root += title("选择要打包的应用", 15f, top = 20)
        for (app in apps) {
            val cb = CheckBox(this).apply {
                text = "${app.label ?: app.packageName}（视图 ${app.viewRules.size} · 弹窗 ${app.dialogRules.size}）"
                isChecked = preselect.isEmpty() || app.packageName in preselect
                setPadding(0, dp(2), 0, dp(2))
            }
            cb.setOnCheckedChangeListener { _, _ -> syncPackageIfNeeded() }
            checks[app.packageName] = cb
            root += cb
        }

        // 模块名称
        root += title("模块应用名", 15f, top = 16)
        etName = EditText(this).apply {
            hint = "显示在桌面和 LSPosed 里的名字"
            setText(defaultModuleName(apps))
        }
        root += etName

        // 包名
        root += title("包名（多个模块共存用不同包名）", 15f, top = 12)
        val pkgRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        etPackage = EditText(this).apply {
            hint = "com.example.mymodule"
            setSingleLine()
        }
        pkgRow += etPackage.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        pkgRow += MaterialButton(this).apply {
            text = "随机"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { randomizePackage() }
        }
        root += pkgRow

        // 版本
        root += title("版本", 15f, top = 12)
        val verRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        etVersionName = EditText(this).apply { hint = "版本名 1.0.0"; setSingleLine() }
        etVersionCode = EditText(this).apply { hint = "版本号 1"; setSingleLine(); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        verRow += etVersionName.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        verRow += View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) }
        verRow += etVersionCode.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.4f) }
        root += verRow

        // 签名
        root += title("签名", 15f, top = 16)
        switchCustomSign = SwitchMaterial(this).apply {
            text = "使用自定义签名密钥（默认用内置公开签名）"
            setOnCheckedChangeListener { _, on -> customSignBlock.visibility = if (on) View.VISIBLE else View.GONE }
        }
        root += switchCustomSign
        customSignBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        customSignBlock += MaterialButton(this).apply {
            text = "选择密钥库文件（.jks / .p12）"
            setOnClickListener { pickKeystoreLauncher.launch(arrayOf("*/*")) }
        }
        etKeystorePassword = EditText(this).apply { hint = "密钥库密码"; inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        etKeyAlias = EditText(this).apply { hint = "别名 alias" }
        etKeyPassword = EditText(this).apply { hint = "密钥密码（留空 = 同密钥库密码）"; inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        customSignBlock += etKeystorePassword
        customSignBlock += etKeyAlias
        customSignBlock += etKeyPassword
        root += customSignBlock

        // 生成
        btnGenerate = MaterialButton(this).apply {
            text = "生成模块 APK"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(20) }
            setOnClickListener { generate() }
        }
        root += btnGenerate

        // 结果
        resultCard = MaterialCardView(this).apply {
            visibility = View.GONE
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = 1
        }
        val resultInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        resultInfo = TextView(this).apply { textSize = 13f }
        val resultButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        resultButtons += MaterialButton(this).apply {
            text = "保存到文件"
            setOnClickListener {
                pendingApk?.let { saveApkLauncher.launch(pendingName) }
            }
        }
        resultButtons += View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) }
        resultButtons += MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            text = "分享"
            setOnClickListener { shareApk() }
        }
        resultInner += resultInfo
        resultInner += resultButtons
        resultCard.addView(resultInner)
        root += resultCard

        return ScrollView(this).apply { addView(root) }
    }

    private fun title(text: String, size: Float, top: Int = 0): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTypeface(typeface, Typeface.BOLD)
        if (top > 0) setPadding(0, dp(top), 0, dp(4))
    }

    private fun label(text: String, size: Float, top: Int = 0, secondary: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        if (top > 0) setPadding(0, dp(top), 0, dp(4))
        if (secondary) setTextColor(0xFF888888.toInt())
    }

    private operator fun LinearLayout.plusAssign(view: View) {
        addView(view)
    }

    // ------------------------------------------------------------- 默认值

    private fun defaultModuleName(apps: List<io.github.gvrulepack.common.StoredApp>): String {
        if (apps.isEmpty()) return "我的规则包"
        val first = apps.first().label ?: apps.first().packageName
        return if (apps.size == 1) "${first}规则包" else "${first}等${apps.size}应用规则包"
    }

    /** 自动包名：目标应用包名 + .mode（多选时取第一个勾选的应用） */
    private fun defaultPackageName(): String {
        val first = checkedPackages().firstOrNull()
        return if (first != null) "$first.mode" else "io.github.gvrulepack.pack"
    }

    /** 最近一次自动生成的包名：用于判断输入框是否仍是「自动值」（用户手改则不覆盖） */
    private var lastAutoPkg: String? = null

    /** 勾选变化时刷新自动包名；用户手动改过（≠自动值）则保持不动 */
    private fun syncPackageIfNeeded() {
        val auto = defaultPackageName()
        val cur = etPackage.text.toString().trim()
        if (cur.isEmpty() || cur == lastAutoPkg) {
            lastAutoPkg = auto
            etPackage.setText(auto)
            etPackage.setSelection(auto.length)
        }
    }

    private fun prefillPackage() {
        lastAutoPkg = null
        syncPackageIfNeeded()
        etVersionName.setText("1.0.0")
        etVersionCode.setText("1")
    }

    private fun randomizePackage() {
        val random = (100000..999999).random()
        val pkg = "${defaultPackageName()}.$random"
        lastAutoPkg = pkg
        etPackage.setText(pkg)
        etPackage.setSelection(pkg.length)
    }

    private fun checkedPackages(): List<String> = checks.filter { it.value.isChecked }.keys.toList()

    // ------------------------------------------------------------- 生成

    private fun generate() {
        val selected = checkedPackages()
        if (selected.isEmpty()) {
            toast("至少选择一个应用")
            return
        }
        val name = etName.text.toString().trim()
        if (name.isEmpty()) {
            toast("填写模块应用名")
            return
        }
        val pkg = etPackage.text.toString().trim()
        if (!pkg.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+"))) {
            toast("包名格式不合法")
            return
        }
        val vName = etVersionName.text.toString().trim().ifEmpty { "1.0.0" }
        val vCode = etVersionCode.text.toString().trim().toIntOrNull() ?: 1

        val customSign = switchCustomSign.isChecked
        if (customSign && customKeystoreFile == null) {
            toast("先选择自定义密钥库文件")
            return
        }

        val dialog = ProgressDialog(this).apply {
            setMessage("正在生成模块 APK…")
            setCancelable(false)
            show()
        }
        btnGenerate.isEnabled = false

        executor.execute {
            val result = runCatching {
                val apps = store.listApps().filter { it.packageName in selected }
                val bundleJson = ModuleApkExporter.buildBundleJson(
                    apps.map {
                        BundleApp(
                            packageName = it.packageName,
                            label = it.label,
                            viewRules = it.viewRules,
                            dialogRules = it.dialogRules,
                        )
                    },
                )

                val exportDir = File(cacheDir, "exports").apply { mkdirs() }
                val template = copyAssetTo(RuleFormat.TEMPLATE_APK_ASSET, File(exportDir, "template.apk"))

                val sign = if (customSign) {
                    val keystorePwd = etKeystorePassword.text.toString()
                    val alias = etKeyAlias.text.toString().trim().ifEmpty { RuleFormat.DEFAULT_KEY_ALIAS }
                    val keyPwd = etKeyPassword.text.toString().ifEmpty { keystorePwd }
                    ModuleApkExporter.loadKeystore(
                        customKeystoreFile!!,
                        keystorePwd.toCharArray(),
                        alias,
                        keyPwd.toCharArray(),
                    )
                } else {
                    val defaultKs = copyAssetTo(RuleFormat.DEFAULT_KEYSTORE_ASSET, File(exportDir, "default_sign.jks"))
                    val pwd = RuleFormat.DEFAULT_KEYSTORE_PASSWORD.toCharArray()
                    ModuleApkExporter.loadKeystore(defaultKs, pwd, RuleFormat.DEFAULT_KEY_ALIAS, pwd)
                }

                val unsigned = File(exportDir, "unsigned.apk")
                val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val signed = File(exportDir, "$safeName-$vName.apk")
                if (signed.exists()) signed.delete()

                ModuleApkExporter.export(
                    templateApk = template,
                    rulesBundleJson = bundleJson,
                    config = ModuleApkExporter.Config(pkg, name, vName, vCode),
                    sign = sign,
                    unsignedOut = unsigned,
                    signedOut = signed,
                ) to ModuleApkExporter.sha256(signed)
            }

            runOnUiThread {
                runCatching { dialog.dismiss() }
                btnGenerate.isEnabled = true
                result.fold({ (r, sha) ->
                    pendingApk = r.signedApk
                    pendingName = "${safeFileName(name)}-$vName.apk"
                    resultInfo.text = buildString {
                        append("✔ 生成成功：${r.signedApk.name}\n")
                        append("包含 ${r.scopePackages.size} 个应用 · 视图规则 ${r.viewRuleCount} 条 · 弹窗规则 ${r.dialogRuleCount} 条\n")
                        append("推荐作用域：${r.scopePackages.joinToString("、")}\n")
                        append("SHA256：${sha.take(16)}…\n\n")
                        append("发给朋友安装 → LSPosed 勾选推荐应用 → 强停目标应用后打开即生效")
                    }
                    resultCard.visibility = View.VISIBLE
                }, { e ->
                    AlertDialog.Builder(this@ExportActivity)
                        .setTitle("生成失败")
                        .setMessage(e.message ?: e.toString())
                        .setPositiveButton("确定", null)
                        .show()
                })
            }
        }
    }

    private fun safeFileName(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_")

    private fun copyAssetTo(asset: String, dest: File): File {
        // 无条件覆盖：旧版「存在即复用」会在升级工坊后一直用缓存里的旧模板，
        // 导出的模块缺失 LSPosed 识别键（v1.2.5 修复的真实事故）。
        assets.open(asset).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        return dest
    }

    private fun shareApk() {
        val apk = pendingApk ?: return
        val uri = GvFileProvider.uriFor(this, apk)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("apk", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "分享模块 APK"))
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val EXTRA_PRESELECT = "preselect"

        fun start(context: android.content.Context, packages: List<String>) {
            val intent = Intent(context, ExportActivity::class.java).apply {
                putStringArrayListExtra(EXTRA_PRESELECT, ArrayList(packages))
            }
            context.startActivity(intent)
        }
    }
}
