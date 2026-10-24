# -*- coding: utf-8 -*-
"""QPM 静态校验：XML 格式 / 中英文 string key 对齐 / 引用完整性 / Kotlin 括号配平"""
import os, re, sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "app", "src", "main")
errors, warns = [], []

# 1. XML well-formedness
xml_files = []
for base in ("res", "AndroidManifest.xml"):
    p = os.path.join(APP, base)
    if os.path.isdir(p):
        for d, _, fs in os.walk(p):
            xml_files += [os.path.join(d, f) for f in fs if f.endswith(".xml")]
    elif os.path.isfile(p):
        xml_files.append(p)
for f in xml_files:
    try:
        ET.parse(f)
    except ET.ParseError as e:
        errors.append("XML 解析失败 %s: %s" % (f, e))
print("[1] XML 格式：%d 个文件" % len(xml_files))

# 2. zh/en key 对齐
def string_keys(path):
    t = ET.parse(path).getroot()
    return {c.get("name") for c in t.findall("string")}
zh = string_keys(os.path.join(APP, "res", "values", "strings.xml"))
en = string_keys(os.path.join(APP, "res", "values-en", "strings.xml"))
only_zh, only_en = zh - en, en - zh
if only_zh: errors.append("仅中文有的 key: %s" % sorted(only_zh))
if only_en: errors.append("仅英文有的 key: %s" % sorted(only_en))
print("[2] string key：zh=%d en=%d，差集 zh-only=%d en-only=%d" % (len(zh), len(en), len(only_zh), len(only_en)))

# 3. 引用完整性
refs = set()
for d, _, fs in os.walk(os.path.join(APP, "java")):
    for f in fs:
        if f.endswith(".kt"):
            refs |= set(re.findall(r"R\.string\.(\w+)", open(os.path.join(d, f), encoding="utf-8").read()))
for f in xml_files:
    if "values" in f: continue
    refs |= set(re.findall(r"@string/(\w+)", open(f, encoding="utf-8").read()))
missing = refs - zh
if missing: errors.append("引用了但不存在的 string: %s" % sorted(missing))
unused = zh - refs
if unused: warns.append("定义但未被引用的 string: %s" % sorted(unused))
print("[3] 引用：R.string/@string 共 %d 个，缺失 %d，未使用 %d" % (len(refs), len(missing), len(unused)))

# 4. Kotlin 括号配平（状态机扫描：正确处理字符串/字符/注释，URL 中的 // 不误判）
def brace_balance(src):
    depth = {"{": 0, "(": 0, "[": 0}
    pairs = {"}": "{", ")": "(", "]": "["}
    i, n = 0, len(src)
    state = "code"  # code / line_comment / block_comment / string / char
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if state == "code":
            if c == "/" and nxt == "/":
                state = "line_comment"; i += 2; continue
            if c == "/" and nxt == "*":
                state = "block_comment"; i += 2; continue
            if c == '"':
                state = "string"; i += 1; continue
            if c == "'":
                state = "char"; i += 1; continue
            if c in depth: depth[c] += 1
            elif c in pairs: depth[pairs[c]] -= 1
        elif state == "line_comment":
            if c == "\n": state = "code"
        elif state == "block_comment":
            if c == "*" and nxt == "/":
                state = "code"; i += 2; continue
        elif state == "string":
            if c == "\\": i += 2; continue
            if c == '"': state = "code"
        elif state == "char":
            if c == "\\": i += 2; continue
            if c == "'": state = "code"
        i += 1
    return depth
for d, _, fs in os.walk(os.path.join(APP, "java")):
    for f in fs:
        if not f.endswith(".kt"): continue
        src = open(os.path.join(d, f), encoding="utf-8").read()
        depth = brace_balance(src)
        bad = {k: v for k, v in depth.items() if v != 0}
        if bad: errors.append("括号不配平 %s: %s" % (f, bad))
print("[4] Kotlin 括号配平检查完成")

print()
for e in errors: print("ERROR:", e)
for w in warns: print("WARN:", w)
print("\n结果：%d error, %d warn" % (len(errors), len(warns)))
sys.exit(1 if errors else 0)
