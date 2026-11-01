# 更新日志

## v1.1.0（versionCode 2）— 2026-11-01

- **新增：剪贴板导入** —— 主页新增「从剪贴板导入 JSON」，别人发你一段规则文本，
  复制后直接粘贴导入，不必先存成文件
- 三步上手引导文案同步更新（文件 / 剪贴板两种导入方式）
- CI 修复：`setup-android` 默认包废弃导致的构建失败（改用 Runner 自带 sdkmanager）

## v1.0.0（versionCode 1）— 2026-11-01

首个版本：

- 导入 GodViewer 导出的 `godviewer-rules-bundle` JSON（v1 格式，文件选择）
- libxposed API 102 入口，`Activity.onPostResume` + 全局布局回调自动重放
  `view_rules`；`Dialog.show` / `PopupWindow` 尽力重放 `dialog_rules`
- 视图匹配顺序与 GodViewer 一致：depth 路径 → 资源名 → 文本（版本一致时严格匹配）
- ContentProvider 跨进程供给运行时导入的规则；`-PrulesBundle` 构建期预置 + 自动生成
  LSPosed 推荐作用域（`scope.list`）
- `pack.py` 一键打包：包名 / 应用名 / 版本 / 签名密钥 / 内置规则全可配，
  支持 `--keystore auto` 自动生成密钥与 `--lite` 低内存模式
- Material 3 宿主 UI，深色模式自适应；R8 混淆（入口类保持）
