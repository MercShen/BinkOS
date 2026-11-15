#!/usr/bin/env python3
"""Generate app/src/main/java/com/github/gezimos/inkos/helper/BackupPreferenceSchema.kt.

Provenance: static key -> value-type table derived from Prefs.kt typed getters
(getInt/getFloat/getLong/getBoolean/getString/getStringSet) and the string constants
they use (Prefs.kt + Constants.kt, including object PrefKeys).

Dynamic keys are emitted as anchored regex rules:
  app_alias_* / APP_ALIAS_*      -> STRING
  APP_NAME_* / APP_PACKAGE_* / APP_ACTIVITY_* / APP_SHORTCUT_ID_* / APP_USER_* -> STRING

Run from the repository root:
    python tools/generate-backup-schema.py
Do not hand-edit the generated Kotlin file.
"""
import io
import os
import re
from collections import Counter, OrderedDict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PREFS = os.path.join(ROOT, "app/src/main/java/com/github/gezimos/inkos/data/Prefs.kt")
CONSTANTS = os.path.join(ROOT, "app/src/main/java/com/github/gezimos/inkos/data/Constants.kt")
OUT = os.path.join(ROOT, "app/src/main/java/com/github/gezimos/inkos/helper/BackupPreferenceSchema.kt")

GETTER_TYPES = {
    "getInt": "INTEGER",
    "getFloat": "FLOAT",
    "getLong": "LONG",
    "getBoolean": "BOOLEAN",
    "getString": "STRING",
    "getStringSet": "SET",
}
DYNAMIC = [
    ("app_alias_.*", "STRING"),
    ("APP_ALIAS_.*", "STRING"),
    ("APP_NAME_.*", "STRING"),
    ("APP_PACKAGE_.*", "STRING"),
    ("APP_ACTIVITY_.*", "STRING"),
    ("APP_SHORTCUT_ID_.*", "STRING"),
    ("APP_USER_.*", "STRING"),
]

def read(path):
    return io.open(path, encoding="utf-8").read()

def parse_string_constants(text, into):
    for m in re.finditer(r'(?:private\s+)?(?:const\s+)?val\s+(\w+)\s*=\s*"((?:[^"\\]|\\.)*)"', text):
        into.setdefault(m.group(1), m.group(2))

def parse_pref_keys(text, into):
    m = re.search(r"object\s+PrefKeys\s*\{(.*?)\n\s*\}", text, re.S)
    if m:
        parse_string_constants(m.group(1), into)

def resolve_key(expr, constants):
    """Return (literal_key | None, dynamic_regex | None)."""
    expr = expr.strip()
    # Skip concatenations/calls/malformed fragments; interpolation is handled below.
    if expr.startswith('"'):
        if not re.fullmatch(r'"(?:[^"\\]|\\.)*"', expr):
            return None, None
    elif not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_.]*', expr):
        return None, None
    if expr.startswith('"'):
        body = expr[1:-1]
        if "$" not in body:
            return body, None
        pattern = ""
        i = 0
        while i < len(body):
            if body.startswith("${", i):
                end = body.find("}", i)
                if end == -1:
                    return None, None
                name = body[i + 2:end]
                value = constants.get(name)
                if value is None:
                    pattern += ".*"
                else:
                    pattern += re.escape(value)
                i = end + 1
                continue
            if body[i] == "$":
                pattern += ".*"
                i += 1
                while i < len(body) and (body[i].isalnum() or body[i] == "_"):
                    i += 1
                continue
            pattern += re.escape(body[i])
            i += 1
        return None, "^" + pattern + "$"
    name = expr
    if name.startswith("Constants."):
        name = name.split(".")[-1]
    if name in constants:
        return constants[name], None
    return None, None

def main():
    prefs_src = read(PREFS)
    constants = OrderedDict()
    parse_string_constants(prefs_src, constants)
    parse_string_constants(read(CONSTANTS), constants)
    parse_pref_keys(read(CONSTANTS), constants)
    parse_pref_keys(prefs_src, constants)

    statics = OrderedDict()
    dynamic = OrderedDict()
    unresolved = Counter()
    getter_pat = re.compile(r"get(Int|Float|Long|Boolean|String|StringSet)\(\s*([^,]+?)\s*,", re.S)
    for m in getter_pat.finditer(prefs_src):
        getter, expr = m.group(1), m.group(2).strip()
        value_type = GETTER_TYPES["get" + getter]
        literal, pattern = resolve_key(expr, constants)
        if literal:
            statics[literal] = value_type
        elif pattern:
            dynamic[pattern] = value_type
        elif expr in ("key", "prefName"):
            pass  # generic accessors
        else:
            unresolved[expr] += 1

    # Direct writes (e.g. getFloatCompat/putFloat/putLong) are not typed getters but declare a key type.
    write_pat = re.compile(r"(getFloatCompat|putInt|putFloat|putLong|putBoolean|putString|putStringSet)\(\s*([^,]+?)\s*,", re.S)
    write_types = {
        "getFloatCompat": "FLOAT", "putFloat": "FLOAT", "putLong": "LONG",
        "putInt": "INTEGER", "putBoolean": "BOOLEAN", "putString": "STRING", "putStringSet": "SET",
    }
    for m in write_pat.finditer(prefs_src):
        getter, expr = m.group(1), m.group(2).strip()
        literal, pattern = resolve_key(expr, constants)
        if literal:
            statics.setdefault(literal, write_types[getter])
        elif pattern:
            dynamic.setdefault(pattern, write_types[getter])

    for pattern, value_type in DYNAMIC:
        dynamic.setdefault(pattern, value_type)

    cleaned = OrderedDict()
    for key, value_type in statics.items():
        if key.startswith("_"):
            continue
        if not re.fullmatch(r"[A-Za-z0-9_]+", key):
            continue
        if "$" in key or '"' in key or "+" in key:
            continue
        cleaned.setdefault(key, value_type)
    statics = cleaned

    normalized = OrderedDict()
    for pattern, value_type in dynamic.items():
        anchored = pattern if pattern.startswith("^") else "^" + pattern
        if not anchored.endswith("$"):
            anchored += "$"
        if anchored == "^.*$":
            continue
        normalized.setdefault(anchored, value_type)
    dynamic = OrderedDict(sorted(normalized.items()))

    lines = []
    lines.append("package com.github.gezimos.inkos.helper")
    lines.append("")
    lines.append("/**")
    lines.append(" * GENERATED by tools/generate-backup-schema.py from Prefs.kt typed getters — do not hand-edit.")
    lines.append(" * Static keys cover integer/float/long/boolean/string/set preferences; dynamic rules cover")
    lines.append(" * app alias and indexed app-name/shortcut/provider/user keys.")
    lines.append(" */")
    lines.append("object BackupPreferenceSchema {")
    lines.append("    data class DynamicRule(val pattern: Regex, val type: BackupJsonValidator.ValueType)")
    lines.append("")
    def kotlin_str(text):
        return text.replace('\\', '\\\\').replace('"', '\\"')

    lines.append("    val statics: Map<String, BackupJsonValidator.ValueType> = mapOf(")
    for key in sorted(statics):
        lines.append('        "%s" to BackupJsonValidator.ValueType.%s,' % (kotlin_str(key), statics[key]))
    lines.append("    )")
    lines.append("")
    lines.append("    val dynamics: List<DynamicRule> = listOf(")
    for pattern in sorted(dynamic):
        lines.append('        DynamicRule(Regex("%s"), BackupJsonValidator.ValueType.%s),' % (kotlin_str(pattern), dynamic[pattern]))
    lines.append("    )")
    lines.append("")
    lines.append("    fun typeFor(key: String): BackupJsonValidator.ValueType? {")
    lines.append("        statics[key]?.let { return it }")
    lines.append("        for (rule in dynamics) {")
    lines.append("            if (rule.pattern.matches(key)) return rule.type")
    lines.append("        }")
    lines.append("        return null")
    lines.append("    }")
    lines.append("}")
    io.open(OUT, "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")

    print("statics=%d dynamics=%d unresolved=%d" % (len(statics), len(dynamic), sum(unresolved.values())))
    if unresolved:
        print("unresolved sample:", list(unresolved.items())[:10])

if __name__ == "__main__":
    main()
