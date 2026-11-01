# GV 规则包（GVRulePack）

把 **[GodViewer](https://github.com/tobbytian/GodViewer)** 抓好的 UI 规则，变成一个**即装即用的轻量 LSPosed 模块**：

> 用 GodViewer 抓规则 → 导出 JSON → 交给本模块 → 朋友安装后**导入即生效**，
> 不需要再开 GodViewer、不需要重新点选抓取。

- 兼容 GodViewer 导出的 `godviewer-rules-bundle` v1 格式（示例见 `app/src/main/assets/sample_rules.json`）
- 纯自动重放：不拦截触摸、不弹编辑框、不发通知，比 GodViewer 本体轻得多
- 规则匹配 / 应用逻辑**原样移植**自 GodViewer（depth 路径 → 资源名 → 文本，版本一致时严格匹配）
- 基于 **libxposed API 102**（与新版 GodViewer 相同），需要 LSPosed（建议 1.9+/2.x）
- 两种使用方式：
  - **A. 通用版**：装一次 APK，之后随时在 App 里导入 / 更换 JSON（适合爱折腾的人）
  - **B. 分享版**：构建时把 JSON 直接打进 APK，并**自动生成 LSPosed「推荐应用」**（适合一次打包发给朋友）

---

## 快速开始

### 一条命令打包（推荐，类似 SimpleHook 的导出流程）

```bash
# 1) 复制配置模板并按需修改（包名 / 应用名 / 版本 / 签名 / 规则文件）
cp rulepack.properties.example rulepack.properties

# 2) 一键打包 → 产物在 dist/<应用名>-<版本>.apk
python3 pack.py
```

`rulepack.properties` 可配置项：

| 配置项 | 说明 |
|---|---|
| `rulesBundle` | GodViewer 导出的 JSON；留空 = 打「通用版」（装好后在 App 内导入） |
| `applicationId` | 包名 |
| `appLabel` | 模块显示名（桌面 / LSPosed 里显示的名字） |
| `versionCode` / `versionName` | 版本号 |
| `outputName` | 输出文件名（留空自动生成） |
| `keystore` | 自定义签名密钥 `.jks`；填 `auto` = 自动生成一把并复用；留空 = debug 签名 |
| `keystorePassword` / `keyAlias` / `keyPassword` | 签名材料 |

不写配置文件也行，全部走命令行：

```bash
python3 pack.py --rules douyin.json --package com.foo.rulepack \
    --name "抖音净化" --version-name 1.1 --keystore auto
# 只看将执行的构建命令：
python3 pack.py --dry-run ...
# 低内存机器（≤2GB RAM）构建被 OOM 杀掉时：
python3 pack.py --lite        # 跳过 R8 混淆/资源收缩，体积略大但更省内存
```

构建产物（`dist/`）：签名经过 `apksigner verify` 校验（v1+v2），R8 混淆后
`META-INF/xposed/java_init.list` 指向的入口类保持原名，可直接安装。

> 和 SimpleHook 的区别：SimpleHook 是在手机上用 ARSCLib 对内置插件模板做**二进制改包 + 重签名**；
> GVRulePack 有完整源码，走的是**源码构建**——包名/应用名/签名在编译期注入，产物更干净、无兼容隐患。
> 模块内部 authority 已按运行时包名推导，为将来做「App 内导出 APK」留好了兼容。

### 方式 A：通用版（运行时导入）

1. 构建（见下）并安装 APK
2. LSPosed → 模块 → 启用「GV 规则包」
3. 打开「GV 规则包」导入规则，两种方式任选：
   - **文件**：「导入规则 JSON」→ 选择 GodViewer 导出的文件
   - **剪贴板**：复制规则 JSON 文本 →「从剪贴板导入 JSON」（对方直接发你一段文本时最方便）
4. 按界面列表提示，在 LSPosed 勾选对应应用的作用域（可用「打开 LSPosed 作用域」按钮直达）
5. 强制停止目标应用后重新打开 → 规则自动生效

> 修改 / 更换 JSON 后，重新导入并强制停止目标应用即可。

### 方式 B：分享版（预置规则 + 自动推荐应用）

上面 `pack.py` 配好 `rulesBundle` 就是分享版。构建时脚本会自动：

- 把 JSON 写入 `assets/rulepack.json`（注入后自动加载，无需再导入）
- 从 JSON 里的 `package` 字段生成 `META-INF/xposed/scope.list`
  → 安装后 LSPosed 里会**自动列出这些「推荐应用」**，朋友只需勾选即可

不用 pack.py、直接用 gradle 也行：

```bash
# JDK 17 + Android SDK（compileSdk 35）
gradle assembleRelease -PrulesBundle=/path/to/godviewer-rules-bundle.json \
    -PcfgApplicationId=com.foo.mymodule -PcfgAppLabel="我的规则包"
# 产物：app/build/outputs/apk/release/app-release.apk
```

> 预置版同样支持运行时再导入其它 JSON（两者按规则 key 合并，运行时导入的优先生效）。

### 没有本地环境？

仓库自带 GitHub Actions（`.github/workflows/build.yml`），推到 GitHub 后直接出 APK 产物（下载 `dist/*.apk`）；
要预置规则/自定义包名，把 `rulepack.properties` 放进仓库根目录即可被 CI 自动读取
（**注意：里面别提交你的密钥本体**，签名材料建议留空用 debug 签名，或在 CI secrets 里注入）。

首次本地构建若缺 gradle wrapper：`gradle wrapper --gradle-version 8.7`，或直接用 Android Studio 打开本目录。

---

## 工作原理

```
┌────────────┐  导出 JSON   ┌─────────────────────────────┐
│  GodViewer  │ ──────────▶ │  GV 规则包（本模块）          │
│ （抓规则用） │             │  host：导入/存储/管理 JSON    │
└────────────┘             │  注入：Activity 恢复 + 全局  │
                            │  布局回调时自动重放规则       │
                            └──────────────┬──────────────┘
                                           │ content://…rules（运行时导入）
                                           │ 或 APK assets（预置）
                            ┌──────────────▼──────────────┐
                            │ 目标应用进程（如抖音）        │
                            │ onPostResume / 全局布局 →    │
                            │ findViewBestMatch → 应用属性 │
                            └─────────────────────────────┘
```

- **宿主 App**（`host/`）：导入 JSON、按应用合并/删除、通过导出的 `ContentProvider`
  （`content://io.github.gvrulepack.app.rules/rules/<包名>`）把规则提供给注入进程
- **注入模块**（`module/`）：`Activity.onPostResume` + `OnGlobalLayoutListener` 重放
  `view_rules`；`Dialog.show` / `PopupWindow.invokePopup` 尽力重放 `dialog_rules`
- **匹配顺序**（与 GodViewer 一致）：`depth` 路径 → `res_name` 资源名 → `text` 文本，
  每步都校验 `view_class`；目标应用版本号与 `match_version_code` 一致时走严格匹配，
  不一致时要求资源名同时命中，避免升级后误伤

## 支持的规则属性

| 属性 | 支持 |
|------|------|
| 可见性（隐藏控件） | ✅ `changed_visibility` |
| 尺寸 / 边距 / 内边距 | ✅ |
| 文本 / 最大行数 / hint | ✅（TextView） |
| 缩放类型 | ✅（ImageView） |
| 图片 URL | ✅（模块内置简易下载器，不依赖目标应用的 Glide） |
| dialog_rules | ✅ 尽力匹配（对话框/弹窗内按 res_name/文本定位） |

## 注意事项

1. **作用域**：模块只在你勾选的应用里生效；分享版安装后推荐应用会自动列出，但仍需在 LSPosed 里确认勾选并重启目标应用
2. **版本匹配**：规则里的 `match_version_code` 和目标应用当前版本不一致时会自动降级为宽松匹配（资源名必须命中）；如果目标 App 改版导致控件大改，规则可能失效——用 GodViewer 重新抓一份导出即可
3. **别强行停止本模块**：运行时导入的规则由模块 App 的 Provider 提供，模块 App 被「强行停止」后目标应用读不到规则（重新打开一次模块 App 即恢复）；预置版不受影响
4. **与 GodViewer 共存**：两者互不干扰，可以同时启用（GodViewer 负责抓，本模块负责重放导入的规则）
5. **规则数据没有敏感信息**，Provider 对外开放读取，与上游 GodViewer 的跨进程设计一致

## 目录结构

```
app/src/main/java/io/github/gvrulepack/app/
├── shared/          # 与 GodViewer 兼容的数据模型 + bundle 解析/合并
├── host/            # 宿主 App：导入 UI、规则存储、RulesProvider
└── module/          # 注入侧：libxposed 入口、Hook 适配、匹配与重放引擎
app/src/main/resources/META-INF/xposed/   # 现代 LSPosed 模块元数据（推荐应用 scope.list）
```

## 致谢与许可

- 规则格式与重放逻辑移植自 [GodViewer](https://github.com/tobbytian/GodViewer)（GPL-3.0），感谢 [tobbytian](https://github.com/tobbytian)
- 本项目同样采用 GPL-3.0，详见 [LICENSE.md](LICENSE.md)
