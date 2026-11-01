# 更新日志

## v1.2.8（2026-11-02）

- **全新应用图标**：深色圆角渐变底 + 四色菱形（蓝/黄/绿/红）+ 白色中心菱形，
  工坊与导出的模块统一使用；主页头部与空状态同步换新。
- **页面布局优化**：列表行的「本机无此应用」标注从名称后缀移到独立药丸徽标，
  名称不再被挤占、信息层次更清晰。

## v1.2.7（2026-11-02）

- **消除「本机未安装」标注的歧义**：规则列表里的灰字标注改为
  「（本机无此应用·不影响生成）」，并在导入成功提示中说明——规则来自 JSON，
  本机装没装目标应用都不影响生成模块 APK（模块是装到目标应用所在的手机上生效的）。

## v1.2.6（2026-11-02）

- **导出模块在 LSPosed 显示「推荐作用域」**：传统（legacy）模块的推荐应用列表，
  LSPosed 只读 manifest 的 `xposedscope` meta-data（分号分隔；zip 内 `scope.list` 仅新版 modern 模块读取）。
  现模板预置占位、导出时写入选中包名；scope 为空则移除该字段。重新导出并安装后，
  LSPosed 模块详情/作用域页即可看到推荐应用，一键勾选。
- 前提回顾：v1.2.5 起修复模板缓存，升级工坊后请**重新生成**模块 APK。

## v1.2.5（2026-11-02）

- **修复「升级工坊后导出的模块仍不被 LSPosed 识别」（v1.2.4 修复无效的真正根因）**：
  导出器会把模板复制到应用缓存目录复用，旧逻辑「文件存在就不更新」——先用旧版导出过一次后，
  缓存里的旧模板会被一直复用，v1.2.3/v1.2.4 的模板修复永远到不了产物。
  现改为每次导出无条件覆盖模板，并在导出完成后**自检产物**（必须含 `assets/xposed_init`
  与 manifest `xposedminversion` 识别键，否则报错中止），双保险。
- 若此前导出过模块后升级过工坊：**无需手动清缓存**，v1.2.5 直接覆盖缓存模板。
  仍不显示的话，删除 App 后重装再导出。

## v1.2.4（2026-11-02）

- **修复导出的模块不被 LSPosed 识别（列表里不显示）**：LSPosed 对传统模块的识别条件是 manifest 里含 `xposedminversion` meta-data（源码 `isLegacyModule`），模板此前从未声明；现补上 `xposedmodule` / `xposedminversion` / `xposeddescription` 三件套，并加防回归测试断言。
- 提示：传统模块在 LSPosed 里的推荐作用域读取的是 manifest `xposedscope`（暂未启用），**请在 LSPosed 中手动勾选目标应用**后强停重开。

## v1.2.3（2026-11-02）

- **修复导出模块完全不生效**（两个根因）：① 导出器曾把规则写到 zip 根路径，而读取端按 `assets/rulepack.json` 查找——现在读写都用真实 zip 路径，并有防回归测试；② 模块改回**传统 Xposed API**（`assets/xposed_init` + api-82），LSPosed/EdXposed/太极全兼容（此前的 modern libxposed API 在旧框架上根本不加载），规则读取改为公开 API 的 ZipFile 直读（不再反射隐藏 API `AssetManager.addAssetPath`）。
- **诊断日志**：模块加载、Hook 安装、规则读取全链路 logcat（tag=GVRulePack）+ XposedBridge 双写，LSPosed 日志可查。
- **包名默认值**：默认「目标应用包名 + `.mode`」（多选取第一个勾选），切换勾选自动刷新、手动改过不覆盖；随机按钮基于此加随机段。
- **模块名可更正**：导出时模块名同步写入 `module.prop` 的 `name=`——LSPosed 模块列表与桌面显示一致。
- 模板预置空 `assets/rulepack.json`，模块元数据精简（去除 modern API 专有字段）。

## v1.2.2（2026-11-02）

- **修复 v1.2.1 启动即崩**：`AbstractMethodError: AppCompatDelegateImpl.onCreateView`——R8（compat 模式、非优化 proguard 配置下）仍把 appcompat 的 Factory2 实现裁成 abstract。工厂 App 自此**关闭混淆与资源收缩**（换体积换稳定；导出的模块模板不受影响，仍保持混淆）。

## v1.2.1（2026-11-01）

- **修复启动即崩**：`Missing android.support.FILE_PROVIDER_PATHS meta-data`——R8 构建管线把 androidx FileProvider 的 meta-data 解析裁剪成无条件抛异常。已改为自实现的轻量 `GvFileProvider`（零反射、零 XML 资源依赖），并关闭 R8 full mode、改用非优化 proguard 配置。

## v1.2.0（2026-11-01）

**架构重做：模块工厂**——工坊 App 不再是 LSPosed 模块，而是「生成模块 APK 的工厂」：

- **三模块工程**：`common`（共享规则模型/解析）+ `module`（模板 APK：hook 核心 + LSPosed 元数据）+ `app`（工坊）。
- **生成独立模块 APK**：勾选应用 → 填模块名 / 包名（可随机）/ 版本 → 选签名 → ARSCLib 二进制改包内置模板 + apksig 重签名 → 导出 / 系统分享。每个配置一个 APK，多配置多包名可共存。
- **生成的模块零配置**：内嵌所选规则 `assets/rulepack.json` + 推荐作用域 `scope.list`（安装后 LSPosed 自动提示），不依赖工坊 App。
- **可配置签名**：内置公开默认签名，或导出时携带自己的 keystore（JKS/PKCS12，v1+v2 签名）。
- **打包流水线升级**：`pack.py` 自动构建模板 → 模板与内置密钥入 assets → JVM 端到端测试（改包+签名+校验）→ 构建工厂 APK。
- 工坊保留：文件 / 剪贴板导入规则、多配置列表、行内生成 / 删除；导出界面 Material 3 风格。

## v1.1.0（2026-11-01）

- **剪贴板导入**：支持直接粘贴 `godviewer-rules-bundle` JSON 文本导入规则；打开页面若剪贴板有规则会提示导入，也可通过右上角「从剪贴板导入」按钮随时手动导入。
- 新增 `CHANGELOG.md` 更新日志。

## v1.0.0（2026-11-01）

- **规则导入**：打开 GodViewer 规则分享页 → 点右上「Share」→ 选「GV 规则包」即可导入；或通过应用列表、文件管理器分享 JSON 给本应用（支持多配置）。
- **LSPosed 模块**：Hook 范围 `onPostResume`、`onDestroy`、`Dialog.show`、`PopupWindow.invokePopup`——只在这些入口回放规则，不干扰业务逻辑。
- **仅内嵌模式**：规则随 APK 打包，安装即可用，无需任何搭配应用。
- **弹窗规则**：按 dialog/popup 根容器独立定位视图，匹配窗口内容，精准替换弹窗按钮与提示。
- **模块保护**：`exceptionMode=protective`——目标应用崩溃时自动退出，不背锅。
- **推荐作用域**：`META-INF/xposed/scope.list` 内置目标应用列表，安装后 LSPosed 自动提示勾选。
- **精美 UI**：应用列表展示规则数量，支持行内「删除规则」；全中文应用名、矢量图标。
- 一键打包：`python3 pack.py --keystore auto`，CI 全自动构建发布。
