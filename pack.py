#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GV 规则工坊 一键打包脚本：

  1. 构建「模块模板」APK（:module）
  2. 模板 + 内置默认签名密钥 放入工厂 assets
  3. 跑 JVM 端到端测试（导出器改包+签名全链路验证）
  4. 构建「工厂 App」APK（:app）→ 复制到 dist/

工厂 App 装到手机后：导入 GodViewer JSON → 配置 → 生成独立模块 APK。

用法：
  cp rulepack.properties.example rulepack.properties   # 按需修改（工厂自身的包名/名称/签名）
  python3 pack.py
"""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CONFIG_FILE = ROOT / "rulepack.properties"
DIST_DIR = ROOT / "dist"

MODULE_APK = ROOT / "module" / "build" / "outputs" / "apk" / "release" / "module-release.apk"
TEMPLATE_ASSET = ROOT / "app" / "src" / "main" / "assets" / "template.apk"
DEFAULT_KS_ASSET = ROOT / "app" / "src" / "main" / "assets" / "default_sign.jks"
FACTORY_APK = ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"

# 与 common/RuleFormat.kt 保持一致
KS_PASSWORD = "gvrulepack"
KS_ALIAS = "gvrulepack"


# --------------------------------------------------------------------------- 配置
def load_properties(path: Path) -> dict[str, str]:
    props: dict[str, str] = {}
    if not path.exists():
        return props
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        props[key.strip()] = value.strip()
    return props


def parse_args(props: dict[str, str]) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="GV 规则工坊 一键打包")
    p.add_argument("--config", default=str(CONFIG_FILE), help="配置文件路径（默认 rulepack.properties）")
    p.add_argument("--package", default=props.get("applicationId", "io.github.gvrulepack.app"), help="工厂包名")
    p.add_argument("--name", default=props.get("appLabel", "GV 规则工坊"), help="工厂应用名")
    p.add_argument("--version-code", default=props.get("versionCode", "9"), help="versionCode")
    p.add_argument("--version-name", default=props.get("versionName", "1.2.6"), help="versionName")
    p.add_argument("--output-name", default=props.get("outputName", ""), help="输出 APK 文件名")
    p.add_argument("--keystore", default=props.get("keystore", ""),
                   help="工厂自身签名；'auto'=自动生成；留空=debug 签名")
    p.add_argument("--keystore-password", default=props.get("keystorePassword", ""))
    p.add_argument("--key-alias", default=props.get("keyAlias", ""))
    p.add_argument("--key-password", default=props.get("keyPassword", ""))
    p.add_argument("--lite", action="store_true", help="低内存模式：跳过 R8 混淆/资源收缩")
    p.add_argument("--skip-test", action="store_true", help="跳过导出器端到端测试")
    p.add_argument("--dry-run", action="store_true", help="只打印将要执行的构建步骤")
    return p.parse_args()


# --------------------------------------------------------------------------- 工具
def find_keytool() -> Path | None:
    found = shutil.which("keytool")
    if found:
        return Path(found)
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / "keytool"
        if candidate.exists():
            return candidate
    return None


def find_gradle() -> list[str]:
    wrapper = ROOT / "gradlew"
    if wrapper.exists():
        return [str(wrapper)]
    found = shutil.which("gradle")
    if found:
        return [found]
    sys.exit("[pack] 错误: 找不到 gradlew / gradle。请安装 Gradle 8.7+，"
             "或先执行 `gradle wrapper --gradle-version 8.7`，也可用 Android Studio 构建")


def ensure_default_keystore() -> None:
    """工厂内置的默认签名密钥（生成的模块 APK 用它签名，公开无机密性）。"""
    if DEFAULT_KS_ASSET.exists():
        return
    keytool = find_keytool()
    if keytool is None:
        sys.exit("[pack] 错误: 找不到 keytool，无法生成内置默认签名密钥")
    DEFAULT_KS_ASSET.parent.mkdir(parents=True, exist_ok=True)
    cmd = [
        str(keytool), "-genkeypair", "-noprompt",
        "-keystore", str(DEFAULT_KS_ASSET),
        "-storepass", KS_PASSWORD, "-keypass", KS_PASSWORD,
        "-alias", KS_ALIAS,
        "-keyalg", "RSA", "-keysize", "2048", "-validity", "10950",
        "-dname", "CN=GVRulePack Module, OU=GVRulePack, O=GVRulePack, L=City, ST=State, C=CN",
    ]
    print(f"[pack] 生成内置默认签名密钥: {DEFAULT_KS_ASSET}")
    subprocess.run(cmd, check=True)


def ensure_auto_keystore(dry_run: bool = False) -> Path:
    auto = DIST_DIR / "gvrulepack-auto.jks"
    if auto.exists():
        print(f"[pack] 复用自动密钥: {auto}")
        return auto
    keytool = find_keytool()
    if keytool is None:
        sys.exit("[pack] 错误: 找不到 keytool（请装 JDK 或设置 JAVA_HOME）")
    if dry_run:
        print(f"[pack] dry-run: 将在 {auto} 生成自动密钥")
        return auto
    DIST_DIR.mkdir(parents=True, exist_ok=True)
    cmd = [
        str(keytool), "-genkeypair", "-noprompt",
        "-keystore", str(auto),
        "-storepass", KS_PASSWORD, "-keypass", KS_PASSWORD,
        "-alias", KS_ALIAS,
        "-keyalg", "RSA", "-keysize", "2048", "-validity", "10950",
        "-dname", "CN=GVRulePack, OU=GVRulePack, O=GVRulePack, L=City, ST=State, C=CN",
    ]
    print(f"[pack] 生成自动密钥: {auto}")
    subprocess.run(cmd, check=True)
    return auto


def run_gradle(gradle: list[str], tasks: list[str], extra: list[str], dry_run: bool) -> None:
    cmd = gradle + tasks + ["--console=plain"] + extra
    print("[pack] $ " + " ".join(cmd))
    if dry_run:
        return
    result = subprocess.run(cmd, cwd=str(ROOT))
    if result.returncode != 0:
        sys.exit(f"[pack] 构建失败 (exit={result.returncode}): {' '.join(tasks)}")


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> None:
    pre = argparse.ArgumentParser(add_help=False)
    pre.add_argument("--config", default=str(CONFIG_FILE))
    pre_args, _ = pre.parse_known_args()
    props = load_properties(Path(pre_args.config))
    args = parse_args(props)

    print(f"[pack] 工厂: 包名={args.package} 名称={args.name} 版本={args.version_name}({args.version_code})")

    gradle = find_gradle()
    lite = ["-PcfgLiteBuild=true"] if args.lite else []
    if args.lite:
        print("[pack] 低内存模式: 跳过 R8 / 资源收缩")

    # ---- 第 1 步：模块模板 ----
    print("\n[pack] === 1/4 构建模块模板（:module）===")
    run_gradle(gradle, [":module:assembleRelease"], lite, args.dry_run)
    if not args.dry_run:
        if not MODULE_APK.exists():
            sys.exit(f"[pack] 错误: 模板产物不存在 {MODULE_APK}")
        TEMPLATE_ASSET.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(MODULE_APK, TEMPLATE_ASSET)
        print(f"[pack] 模板已放入 assets: {TEMPLATE_ASSET} "
              f"({TEMPLATE_ASSET.stat().st_size // 1024} KB)")

    # ---- 第 2 步：内置默认签名密钥 ----
    print("\n[pack] === 2/4 准备内置默认签名密钥 ===")
    if args.dry_run:
        print(f"[pack] dry-run: 确保 {DEFAULT_KS_ASSET} 存在（缺失则 keytool 生成）")
    else:
        ensure_default_keystore()

    # ---- 第 3 步：导出器端到端测试 ----
    if args.skip_test:
        print("\n[pack] === 3/4 导出器测试：已跳过（--skip-test）===")
    else:
        print("\n[pack] === 3/4 导出器端到端测试（:app:testDebugUnitTest）===")
        run_gradle(gradle, [":app:testDebugUnitTest"], [], args.dry_run)
        print("[pack] 测试通过 ✔")

    # ---- 第 4 步：工厂 App ----
    print("\n[pack] === 4/4 构建工厂 App（:app）===")
    extra = lite + [
        f"-PcfgApplicationId={args.package}",
        f"-PcfgAppLabel={args.name}",
        f"-PcfgVersionCode={args.version_code}",
        f"-PcfgVersionName={args.version_name}",
    ]
    if args.keystore.strip().lower() == "auto":
        ks = ensure_auto_keystore(args.dry_run)
        extra += [f"-PcfgKeystore={ks}",
                  f"-PcfgKeystorePassword={KS_PASSWORD}",
                  f"-PcfgKeyAlias={KS_ALIAS}",
                  f"-PcfgKeyPassword={KS_PASSWORD}"]
        print("[pack] 工厂签名: 自动生成的密钥")
    elif args.keystore.strip():
        ks = Path(args.keystore)
        if not ks.is_absolute():
            ks = ROOT / ks
        if not ks.exists():
            sys.exit(f"[pack] 错误: 密钥文件不存在: {ks}")
        if not args.keystore_password or not args.key_alias:
            sys.exit("[pack] 错误: 使用自定义密钥时必须提供 keystorePassword 与 keyAlias")
        extra += [f"-PcfgKeystore={ks}",
                  f"-PcfgKeystorePassword={args.keystore_password}",
                  f"-PcfgKeyAlias={args.key_alias}",
                  f"-PcfgKeyPassword={args.key_password or args.keystore_password}"]
        print(f"[pack] 工厂签名: {ks}")
    else:
        print("[pack] 工厂签名: debug")

    run_gradle(gradle, ["assembleRelease"], extra, args.dry_run)
    if args.dry_run:
        print("\n[pack] dry-run 完成")
        return
    if not FACTORY_APK.exists():
        sys.exit(f"[pack] 错误: 找不到工厂产物 {FACTORY_APK}")

    DIST_DIR.mkdir(parents=True, exist_ok=True)
    safe_name = args.output_name.strip() or f"{args.name}-{args.version_name}.apk"
    if not safe_name.endswith(".apk"):
        safe_name += ".apk"
    safe_name = safe_name.replace("/", "_").replace("\\", "_").replace(":", "_")
    out = DIST_DIR / safe_name
    shutil.copy2(FACTORY_APK, out)

    print("")
    print("[pack] ✔ 打包完成")
    print(f"[pack]   工厂 APK : {out} ({out.stat().st_size / 1024:.0f} KB)")
    print(f"[pack]   SHA256   : {sha256_of(out)}")
    print(f"[pack]   内置模板 : {TEMPLATE_ASSET} ({TEMPLATE_ASSET.stat().st_size / 1024:.0f} KB)")
    print("[pack]   安装工厂 App → 导入 GodViewer JSON → 「生成模块 APK」→ 发给朋友")


if __name__ == "__main__":
    main()
