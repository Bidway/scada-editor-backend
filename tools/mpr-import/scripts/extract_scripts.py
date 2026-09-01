# -*- coding: utf-8 -*-
import re, struct, json, collections

MPR = r"docs/oldversion/BN1_MCA1 NEW.mpr"
IN_JSON = r"tools/mpr-import/output/object_channel_map.json"
OUT_JSON = r"tools/mpr-import/output/object_channel_scripts.json"
OUT_TXT = r"tools/mpr-import/output/object_channel_scripts.txt"
OUT_BY_SCRIPT = r"tools/mpr-import/output/scripts_grouped.txt"

mpr = open(MPR, "rb").read()
rows = json.load(open(IN_JSON, "r", encoding="utf-8"))

SCRIPT_MARKERS = (b'self.', b'Self.', b'SELF.', b'SelFrame', b'end;')
WINDOW = 3000

def find_all(haystack, needle):
    start = 0
    res = []
    while True:
        idx = haystack.find(needle, start)
        if idx == -1:
            break
        res.append(idx)
        start = idx + 1
    return res

import struct as _struct

def extract_script_near(window):
    """Scan a byte window for a String-ish token (tag 6/7: 1-byte len cp1251;
    tag 12: 4-byte len cp1251; tag 20: 4-byte len utf8) whose decoded text
    contains a Pascal-script marker. Returns the longest match's raw bytes."""
    best = None
    n = len(window)
    for i in range(n - 1):
        tag = window[i]
        if tag in (6, 7):
            length = window[i + 1]
            s = window[i + 2:i + 2 + length]
            if len(s) != length:
                continue
        elif tag in (12, 20):
            if i + 5 >= n:
                continue
            length = _struct.unpack_from('<I', window, i + 1)[0]
            if length == 0 or length > 20000:
                continue
            s = window[i + 5:i + 5 + length]
            if len(s) != length:
                continue
        else:
            continue
        if any(mk in s for mk in SCRIPT_MARKERS):
            if best is None or len(s) > len(best):
                best = s
    return best

for r in rows:
    cid = r["id"]
    b = struct.pack("<i", cid)
    offs = find_all(mpr, b)
    scripts = []
    for off in offs:
        window = mpr[max(0, off - WINDOW):off]
        s = extract_script_near(window)
        if s:
            try:
                text = s.decode("utf-8")
            except UnicodeDecodeError:
                text = s.decode("cp1251", errors="replace")
            # a wrong length occasionally overruns into raw binary; cut at the
            # first non-text control char (keep \t \r \n)
            m = re.search(r'[\x00-\x08\x0b\x0c\x0e-\x1f]', text)
            if m:
                text = text[:m.start()]
            text = text.rstrip()
            if text:
                scripts.append(text)
    # de-duplicate, keep order
    seen = []
    for s in scripts:
        if s not in seen:
            seen.append(s)
    r["mpr_occurrences"] = len(offs)
    r["scripts"] = seen

with_script = sum(1 for r in rows if r["scripts"])
print("properties total:", len(rows))
print("properties with an extracted script:", with_script)

json.dump(rows, open(OUT_JSON, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

by_obj = collections.OrderedDict()
for r in rows:
    by_obj.setdefault(r["obj"], []).append(r)

with open(OUT_TXT, "w", encoding="utf-8") as out:
    out.write(f"Всего свойств: {len(rows)}, со скриптом из .mpr: {with_script}\n\n")
    for obj, props in by_obj.items():
        out.write(f"=== {obj} ===\n")
        for r in props:
            out.write(f"  .{r['prop']} (id={r['id']}, тип={r['sdrvname']}) -- {r['caption']}\n")
            if r["scripts"]:
                for s in r["scripts"]:
                    for line in s.split("\r\n"):
                        out.write(f"      | {line}\n")
            else:
                out.write("      (скрипт не найден / нет визуальной привязки)\n")
        out.write("\n")

# group unique scripts by sdrvname (parameter template/class) to see reuse
by_type_script = collections.OrderedDict()
for r in rows:
    for s in r["scripts"]:
        key = r["sdrvname"]
        by_type_script.setdefault(key, collections.Counter())[s] += 1

with open(OUT_BY_SCRIPT, "w", encoding="utf-8") as out:
    for sdrvname, counter in by_type_script.items():
        out.write(f"=== тип параметра: {sdrvname} ===\n")
        for s, cnt in counter.most_common():
            out.write(f"  встречается {cnt} раз(а):\n")
            for line in s.split("\r\n"):
                out.write(f"    | {line}\n")
        out.write("\n")

print("distinct sdrvname with scripts:", len(by_type_script))
print("written:", OUT_TXT)
print("written:", OUT_BY_SCRIPT)
