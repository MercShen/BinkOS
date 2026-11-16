#!/usr/bin/env python3
"""Check BinkOS EN/ZH string resources.

Fails (exit 1) on:
  - missing keys on either side
  - duplicate keys
  - empty values
  - placeholder mismatch (%1$d / %1$s / %d / %s)

Warns (exit 0) about known non-canonical copy. See docs/terminology.md for the
canonical terms and why these are flagged.
"""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app" / "src" / "main" / "res"
EN_PATH = RES / "values" / "strings.xml"
ZH_PATH = RES / "values-zh" / "strings.xml"

PLACEHOLDER = re.compile(r"%(?:\d+\$)?[a-zA-Z]")

FORBIDDEN_EN = (
    "Home Apps",
    "Homepage",
    "Asterisk Notification",
    "Badge Noti",
    "Noti.",
    "SimpleTray",
    "Chosen Theme",
    "Widget Font",
    "Widget alignment",
    "Select App",
    "default home screen",
)
FORBIDDEN_ZH = (
    "主屏应用",
    "小组件",
    "组件",
    "星号通知",
    "角标通知",
    "信笺",
    "中枢",
    "简易托盘",
)


def load(path: Path) -> tuple[dict[str, str], list[str]]:
    try:
        tree = ET.parse(path)
    except ET.ParseError as exc:
        raise SystemExit(f"error: cannot parse {path}: {exc}")
    values: dict[str, str] = {}
    order: list[str] = []
    for element in tree.getroot().findall("string"):
        name = element.get("name")
        if not name:
            continue
        values[name] = element.text or ""
        order.append(name)
    return values, order


def duplicate_keys(path: Path) -> list[str]:
    text = path.read_text(encoding="utf-8")
    names = re.findall(r'<string\s+name="([^"]+)"', text)
    seen: set[str] = set()
    duplicates: list[str] = []
    for name in names:
        if name in seen:
            duplicates.append(name)
        seen.add(name)
    return duplicates


def placeholders(value: str) -> list[str]:
    return sorted(PLACEHOLDER.findall(value))


def main() -> int:
    if not EN_PATH.exists() or not ZH_PATH.exists():
        print(f"error: missing {EN_PATH} or {ZH_PATH}")
        return 1

    en, en_order = load(EN_PATH)
    zh, zh_order = load(ZH_PATH)
    errors: list[str] = []
    warnings: list[str] = []

    for path, values in ((EN_PATH, en), (ZH_PATH, zh)):
        for name in duplicate_keys(path):
            errors.append(f"{path.name}: duplicate key '{name}'")
        for name, value in values.items():
            if not value.strip():
                errors.append(f"{path.name}: empty value for '{name}'")

    for name in sorted(set(en) - set(zh)):
        errors.append(f"missing in values-zh: '{name}'")
    for name in sorted(set(zh) - set(en)):
        errors.append(f"missing in values: '{name}'")

    for name in en_order:
        if name not in zh:
            continue
        left = placeholders(en[name])
        right = placeholders(zh[name])
        if left != right:
            errors.append(
                f"placeholder mismatch for '{name}': EN={left or 'none'} ZH={right or 'none'}"
            )

    for name, value in en.items():
        for term in FORBIDDEN_EN:
            if term in value:
                warnings.append(f"values: '{name}' contains non-canonical '{term}'")
    for name, value in zh.items():
        for term in FORBIDDEN_ZH:
            if term in value:
                warnings.append(f"values-zh: '{name}' contains non-canonical '{term}'")

    print(f"EN keys: {len(en)} | ZH keys: {len(zh)}")
    print(f"missing: {len(set(en) ^ set(zh))} | duplicate: {len(duplicate_keys(EN_PATH)) + len(duplicate_keys(ZH_PATH))} | placeholder issues: {sum(1 for e in errors if 'placeholder' in e)}")

    for message in warnings:
        print(f"warning: {message}")
    for message in errors:
        print(f"error: {message}")

    if errors:
        print("FAIL: fix the errors above (see docs/terminology.md)")
        return 1
    print("OK: locale parity and placeholders look good")
    return 0


if __name__ == "__main__":
    sys.exit(main())
