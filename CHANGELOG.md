# 更新日志

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
