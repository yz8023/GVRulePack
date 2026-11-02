# GV RulePack · GV 规则工坊

**一个「LSPosed 模块工厂」**：导入 [GodViewer](https://github.com/dede2002/GodViewer) 分享的规则 JSON，
为**每个配置生成一个独立的模块 APK**，可改包名 / 应用名 / 版本 / 签名，导出后直接发给朋友安装使用。

```
┌────────────────────────┐       ┌───────────────────────────┐
│  GV 规则工坊 App（工厂）  │       │  导出的模块 APK（每个配置一个）│
│  io.github.gvrulepack.app│       │  你自己的包名 · 你的签名      │
├────────────────────────┤       ├───────────────────────────┤
│ ① 导入规则 JSON          │  ──▶  │ • LSPosed 模块（scope.list）│
│ ② 勾选应用               │ 生成   │ • 内嵌所选规则 rulepack.json │
│ ③ 填模块名/包名/版本       │       │ • 不依赖工坊 App，独立生效    │
│ ④ 选签名 → 生成          │       │ • 多配置多 APK 可共存        │
└────────────────────────┘       └───────────────────────────┘
```

- **工厂本身不是模块**——它只是打包工具；导出的 APK 才是 LSPosed 模块。
- 每个配置生成一个 APK：不同包名即可多配置共存，LSPosed 里各自勾选作用域。
- 生成的模块 **零配置使用**：内置规则 + 推荐作用域（`META-INF/xposed/scope.list`，安装后 LSPosed 自动提示勾选），安装、勾选、强停目标应用即生效。
- 与 [SimpleHook](https://github.com/littleWhiteDuck/SimpleHook) 导出插件 APK 的思路一致（ARSCLib 改包 + apksig 重签名）。

## 签名与安全须知

- **内置默认签名是公开签名**：`default_sign.jks`（密码 `gvrulepack`）随仓库/App 分发，
  任何拿到模块 APK 的人都可以下载本仓库、改掉规则后用同一密钥重签。
  **普通分发无所谓；若在意规则不被他人篡改，请在导出时开启「使用自定义签名密钥」**（每次导出结果页也会提示）。
- 模块按规则中的 `image_url` 下载图片时有 10MB/2048px 上限，且拒绝内网与回环地址（防 SSRF/OOM 炸弹）。
- 工坊 App 已关闭 `allowBackup`；导出分享的 FileProvider 只读。

## 快速使用

1. 从 [Releases](../../releases) 下载工厂 APK，安装到手机。
2. 获取规则 JSON（二选一）：
   - **文件导入**：打开 GodViewer 规则分享页 → 点右上「Share」→ 选「GV 规则工坊」；或任意文件管理器分享 JSON 给工坊。
   - **剪贴板导入**：复制 `godviewer-rules-bundle` JSON 后打开工坊（或点右上按钮）。
3. 选择要打包的应用 → 点「生成模块 APK」→ 确认包名 / 版本 / 签名 → 导出或分享。
4. 把 APK 发给对方：安装 → LSPosed 勾选推荐作用域（如抖音、微信）→ 强停目标应用后打开即生效。

**签名**：默认用公开的内置签名（所有生成模块同签名）；也可在导出时携带自己的 keystore（JKS/PKCS12）定制签名。
**多配置共存**：生成时改一下包名（点「随机」）即可，两个模块 APK 互不冲突。

## 模块能力（生成的 APK）

- 对目标应用执行「布局重放」类 hook：视图定位、属性回放（文字、开关、颜色、图片异步加载……）。
- **Hook 范围**：`onPostResume`、`onDestroy`、`Dialog.show`、`PopupWindow.invokePopup`——只在这些入口回放规则，不干扰业务逻辑。
- **仅内嵌模式**：规则随 APK 打包（`assets/rulepack.json`），安装即可用，无需任何搭配应用。
- **弹窗规则**：按 dialog/popup 根容器独立定位视图，匹配窗口内容，精准替换弹窗按钮与提示。
- **模块保护**：`exceptionMode=protective`——目标应用崩溃时自动退出，不背锅。

## 工坊功能

- 列出导入的规则：每个应用显示视图 / 弹窗规则数量；支持多配置（多应用）共存。
- 行操作：**生成模块 APK（仅含此应用）** / 删除规则。
- 导出界面：应用多选、模块名 / 包名（可随机生成）/ 版本名 / 版本号、内置或自定义签名、进度显示，完成后可**保存到文件**或**系统分享**。

## 项目结构

```
common/   共享库：规则数据模型 + 解析（RuleFormat / RuleModels / RuleBundleParser）
module/   模板 APK：LSPosed 元数据 + hook 核心 + 内嵌规则读取（打包时作为模板放入工厂 assets）
app/      工坊 App：规则导入管理、导出界面、模块 APK 生成器（ARSCLib 改包 + apksig 签名）
```

## 构建

```bash
cp rulepack.properties.example rulepack.properties   # 可选：自定义工厂包名/名称/版本/签名
python3 pack.py --keystore auto
```

pack.py 自动完成：构建 `:module` 模板 → 模板与内置密钥放入工厂 assets →
跑导出器端到端测试（改包+签名+校验）→ 构建 `:app` 工厂 APK → 输出到 `dist/`。

| 参数 | 说明 |
| --- | --- |
| `--keystore auto` | 自动生成并复用工厂签名 `dist/gvrulepack-auto.jks` |
| `--keystore path` | 指定密钥文件（配合 `--keystore-password` / `--key-alias` / `--key-password`） |
| `--package` / `--name` / `--version-code` / `--version-name` | 覆盖工坊自身身份 |
| `--output-name` | 输出文件名（默认 `<应用名>-<版本>.apk`） |
| `--lite` | 低内存模式：跳过 R8 混淆与资源收缩 |
| `--skip-test` | 跳过导出器端到端测试 |

无需 Android Studio：CI 全自动（[GitHub Actions](../../actions)），本地只需 JDK 17 + Android SDK。
开发调试直接用 Android Studio 打开工程即可。

## 生成流程（内部）

1. `gradle :module:assembleRelease` → 模板 APK（R8 混淆 + 资源收缩，约 80KB）。
2. 模板复制为 `app/src/main/assets/template.apk`；`keytool` 确保 `assets/default_sign.jks` 存在。
3. `gradle :app:testDebugUnitTest` —— **质量门**：JVM 端到端验证「模板改包 → apksig 签名 → apksig 校验 → manifest/资产核对」。
4. `gradle assembleRelease` → 工厂 APK。
5. 设备上导出时：选中应用的规则 → `assets/rulepack.json` 替换；推荐作用域 → `META-INF/xposed/scope.list`；manifest 改包名/名称/版本；去掉旧签名条目后 apksig 重签名（v1+v2）。

## 提交规则 JSON

从 GodViewer 分享出的 `godviewer-rules-bundle` JSON 可直接 PR 到本仓库 `sample/` 目录。

## 免责声明

本工具与抖音、微信等应用无关，仅供学习交流；请勿用于商业用途。
