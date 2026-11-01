#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GVRulePack 一键打包脚本（类似 SimpleHook 的「导出插件 APK」，但在电脑上、从源码构建）：

  读取 rulepack.properties（或命令行参数）→ 可选自动生成密钥 → gradle assembleRelease
  → 复制签名后的 APK 到 dist/ 并打印校验和。

用法：
  cp rulepack.properties.example rulepack.properties   # 按需修改
  python3 pack.py

也可以不看配置文件，全部走命令行：
  python3 pack.py --rules douyin.json --package com.example.foo \\
      --name "抖音去广告规则包" --version-name 1.0 --keystore auto
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
BUILD_APK = ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"

AUTO_KEYSTORE = DIST_DIR / "gvrulepack-auto.jks"
AUTO_STOREPASS = "gvrulepack"
AUTO_KEYALIAS = "gvrulepack"


# --------------------------------------------------------------------------- 配置
def load_properties(path: Path) -> dict[str, str]:
    props: dict[str, str] = {}
    if not path.exists():
        return props
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            continue
        key, _, value = line.partition("=")
        props[key.strip()] = value.strip()
    return props


def parse_args(props: dict[str, str]) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="GVRulePack 一键打包")
    p.add_argument("--config", default=str(CONFIG_FILE), help="配置文件路径（默认 rulepack.properties）")
    p.add_argument("--rules", default=props.get("rulesBundle", ""), help="GodViewer 导出的 JSON")
    p.add_argument("--package", default=props.get("applicationId", "io.github.gvrulepack.app"), help="包名")
    p.add_argument("--name", default=props.get("appLabel", "GV 规则包"), help="模块应用名")
    p.add_argument("--version-code", default=props.get("versionCode", "2"), help="versionCode")
    p.add_argument("--version-name", default=props.get("versionName", "1.1.0"), help="versionName")
    p.add_argument("--output-name", default=props.get("outputName", ""), help="输出 APK 文件名")
    p.add_argument("--keystore", default=props.get("keystore", ""),
                   help="签名密钥文件路径；'auto'=自动生成；留空=debug 签名")
    p.add_argument("--keystore-password", default=props.get("keystorePassword", ""))
    p.add_argument("--key-alias", default=props.get("keyAlias", ""))
    p.add_argument("--key-password", default=props.get("keyPassword", ""))
    p.add_argument("--dry-run", action="store_true", help="只打印将要执行的 gradle 命令")
    p.add_argument("--lite", action="store_true",
                   help="低内存模式：跳过 R8 混淆/资源收缩（适合 2GB 内存机器）")
    return p.parse_args()


# --------------------------------------------------------------------------- 签名
def find_keytool() -> Path | None:
    explicit = os.environ.get("KEYTOOL")
    if explicit and Path(explicit).exists():
        return Path(explicit)
    found = shutil.which("keytool")
    if found:
        return Path(found)
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / "keytool"
        if candidate.exists():
            return candidate
    return None


def ensure_auto_keystore(dry_run: bool = False) -> Path:
    """在 dist/ 生成一把自用密钥并复用（保证多次打包签名一致、可覆盖安装）。"""
    if AUTO_KEYSTORE.exists():
        print(f"[pack] 复用自动密钥: {AUTO_KEYSTORE}")
        return AUTO_KEYSTORE
    keytool = find_keytool()
    if keytool is None:
        sys.exit("[pack] 错误: 找不到 keytool（请装 JDK 或设置 JAVA_HOME），"
                 "也可以自己提供 --keystore 或去掉签名配置用 debug 签名")
    if dry_run:
        print(f"[pack] dry-run: 将在 {AUTO_KEYSTORE} 生成自动密钥（keytool）")
        return AUTO_KEYSTORE
    DIST_DIR.mkdir(parents=True, exist_ok=True)
    cmd = [
        str(keytool), "-genkeypair", "-noprompt",
        "-keystore", str(AUTO_KEYSTORE),
        "-storepass", AUTO_STOREPASS, "-keypass", AUTO_STOREPASS,
        "-alias", AUTO_KEYALIAS,
        "-keyalg", "RSA", "-keysize", "2048", "-validity", "10950",
        "-dname", "CN=GVRulePack, OU=GVRulePack, O=GVRulePack, L=City, ST=State, C=CN",
    ]
    print(f"[pack] 生成自动密钥: {AUTO_KEYSTORE}")
    subprocess.run(cmd, check=True)
    return AUTO_KEYSTORE


# --------------------------------------------------------------------------- 构建
def find_gradle() -> list[str]:
    wrapper = ROOT / "gradlew"
    if wrapper.exists():
        return [str(wrapper)]
    found = shutil.which("gradle")
    if found:
        return [found]
    sys.exit("[pack] 错误: 找不到 gradlew / gradle。请安装 Gradle 8.7+，"
             "或先执行 `gradle wrapper --gradle-version 8.7`，也可用 Android Studio 构建")


def validate_rules(path: Path) -> None:
    if not path.exists():
        sys.exit(f"[pack] 错误: 规则文件不存在: {path}")
    text = path.read_text(encoding="utf-8")
    if '"godviewer-rules-bundle"' not in text:
        sys.exit("[pack] 错误: 该文件不是 godviewer-rules-bundle 格式（缺少 format 标识）")


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> None:
    # 先取出 --config，用对应文件做默认值；命令行显式参数始终优先
    pre = argparse.ArgumentParser(add_help=False)
    pre.add_argument("--config", default=str(CONFIG_FILE))
    pre_args, _ = pre.parse_known_args()
    props = load_properties(Path(pre_args.config))
    args = parse_args(props)

    print(f"[pack] 配置: 包名={args.package} 名称={args.name} 版本={args.version_name}({args.version_code})")

    gradle = find_gradle()
    cmd = gradle + ["assembleRelease", "--console=plain",
                    f"-PcfgApplicationId={args.package}",
                    f"-PcfgAppLabel={args.name}",
                    f"-PcfgVersionCode={args.version_code}",
                    f"-PcfgVersionName={args.version_name}"]
    if args.lite:
        cmd.append("-PcfgLiteBuild=true")
        print("[pack] 低内存模式: 跳过 R8 / 资源收缩")

    # 规则预置
    if args.rules:
        rules_path = Path(args.rules)
        if not rules_path.is_absolute():
            rules_path = ROOT / rules_path
        validate_rules(rules_path)
        cmd.append(f"-PrulesBundle={rules_path}")
        print(f"[pack] 预置规则: {rules_path}")
    else:
        print("[pack] 未配置规则文件 → 打「通用版」（装好后在 App 内导入）")

    # 签名
    if args.keystore.strip().lower() == "auto":
        ks = ensure_auto_keystore(dry_run=args.dry_run)
        cmd += [f"-PcfgKeystore={ks}",
                f"-PcfgKeystorePassword={AUTO_STOREPASS}",
                f"-PcfgKeyAlias={AUTO_KEYALIAS}",
                f"-PcfgKeyPassword={AUTO_STOREPASS}"]
        print("[pack] 签名: 自动生成的密钥")
    elif args.keystore.strip():
        ks = Path(args.keystore)
        if not ks.is_absolute():
            ks = ROOT / ks
        if not ks.exists():
            sys.exit(f"[pack] 错误: 密钥文件不存在: {ks}")
        if not args.keystore_password or not args.key_alias:
            sys.exit("[pack] 错误: 使用自定义密钥时必须提供 keystorePassword 与 keyAlias")
        cmd += [f"-PcfgKeystore={ks}",
                f"-PcfgKeystorePassword={args.keystore_password}",
                f"-PcfgKeyAlias={args.key_alias}",
                f"-PcfgKeyPassword={args.key_password or args.keystore_password}"]
        print(f"[pack] 签名: {ks}")
    else:
        print("[pack] 签名: debug（如需正式签名请配置 keystore 或 --keystore auto）")

    if args.dry_run:
        print("[pack] dry-run, 将执行:")
        print("  " + " ".join(cmd))
        return

    print("[pack] 开始构建: " + " ".join(cmd))
    result = subprocess.run(cmd, cwd=str(ROOT))
    if result.returncode != 0:
        sys.exit(f"[pack] 构建失败 (exit={result.returncode})")
    if not BUILD_APK.exists():
        sys.exit(f"[pack] 错误: 找不到构建产物 {BUILD_APK}")

    # 输出到 dist/
    DIST_DIR.mkdir(parents=True, exist_ok=True)
    safe_name = args.output_name.strip() or f"{args.name}-{args.version_name}.apk"
    if not safe_name.endswith(".apk"):
        safe_name += ".apk"
    safe_name = safe_name.replace("/", "_").replace("\\", "_").replace(":", "_")
    out = DIST_DIR / safe_name
    shutil.copy2(BUILD_APK, out)

    size_kb = out.stat().st_size / 1024
    print("")
    print("[pack] ✔ 打包完成")
    print(f"[pack]   APK   : {out}")
    print(f"[pack]   大小  : {size_kb:.0f} KB")
    print(f"[pack]   SHA256: {sha256_of(out)}")
    if not args.rules:
        print("[pack]   提示: 通用版请在安装后打开 App 导入规则 JSON，并在 LSPosed 勾选作用域")
    else:
        print("[pack]   提示: 安装后在 LSPosed 确认勾选推荐应用，强停目标应用后打开即可")


if __name__ == "__main__":
    main()
