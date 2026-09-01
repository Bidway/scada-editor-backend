# -*- coding: utf-8 -*-
import re, json, collections, struct

CDBX = r"docs/oldversion/BN1 MCA1 NEW.cdbx"
MPR = r"docs/oldversion/BN1_MCA1 NEW.mpr"
OUT_TXT = r"tools/mpr-import/output/object_channel_map.txt"
OUT_JSON = r"tools/mpr-import/output/object_channel_map.json"

xml = open(CDBX, "r", encoding="utf-8-sig").read()

# also capture the enclosing <subtypes:subtype> sdrvname/sdrvdefname (parameter-class template name)
subtype_blocks = re.findall(
    r"<subtypes:subtype>.*?<subtypes:sdrvname>(.*?)</subtypes:sdrvname>\s*"
    r"<subtypes:sdrvdefname>(.*?)</subtypes:sdrvdefname>.*?"
    r"<subtypes:channels[^>]*>(.*?)</subtypes:channels>\s*</subtypes:subtype>",
    xml, re.S)

channel_re = re.compile(
    r"<channels:channel>\s*"
    r"<channels:id>(\d+)</channels:id>\s*"
    r"<channels:requesttype>(-?\d+)</channels:requesttype>\s*"
    r"<channels:requestperiod>(-?\d+)</channels:requestperiod>\s*"
    r"<channels:enabled>(-?\d+)</channels:enabled>\s*"
    r"<channels:descr>(.*?)</channels:descr>\s*"
    r"<channels:delta>(-?\d+)</channels:delta>\s*"
    r"<channels:apptime>(-?\d+)</channels:apptime>\s*"
    r"<channels:protocol>(-?\d+)</channels:protocol>",
    re.S)

rows = []
for sdrvname, sdrvdefname, block in subtype_blocks:
    for m in channel_re.finditer(block):
        cid, reqtype, reqperiod, enabled, descr, delta, apptime, protocol = m.groups()
        left, _, caption = descr.partition(" -- ")
        obj, _, prop = left.partition(".")
        rows.append(dict(
            id=int(cid), obj=obj, prop=prop, caption=caption.strip(),
            enabled=(enabled == "-1"), reqtype=int(reqtype), reqperiod=int(reqperiod),
            protocol=int(protocol), sdrvname=sdrvname, sdrvdefname=sdrvdefname,
        ))

print("rows parsed:", len(rows))

# cross-check against .mpr: which channel ids are literally embedded as Int32 in the binary
mpr = open(MPR, "rb").read()
present_ids = set()

# scan the whole binary for every 4-byte LE run that equals a known channel id
wanted = {r["id"] for r in rows}
id_bytes = {struct.pack("<i", i): i for i in wanted}
# build a reverse index by scanning once (single pass, 4-byte sliding window is too slow in pure py for 1.4MB * lookups;
# instead just search occurrences of each id's bytes via bytes.find in a loop over a set - still fine for ~2700 ids on 1.4MB)
for b, i in id_bytes.items():
    if mpr.find(b) != -1:
        present_ids.add(i)

print("channel ids also found embedded in the .mpr binary:", len(present_ids), "/", len(wanted))

by_obj = collections.OrderedDict()
for r in rows:
    by_obj.setdefault(r["obj"], []).append(r)

with open(OUT_TXT, "w", encoding="utf-8") as out:
    out.write(f"Всего объектов: {len(by_obj)}, всего свойств/каналов: {len(rows)}\n")
    out.write(f"Из них id встречается в .mpr (визуально привязано в схеме): {len(present_ids)}\n\n")
    for obj, props in by_obj.items():
        out.write(f"=== {obj} ===\n")
        for r in props:
            mark = "[в схеме]" if r["id"] in present_ids else ""
            out.write(
                f"  .{r['prop']:<20} id={r['id']:<12} "
                f"тип={r['sdrvname']:<12} период={r['reqperiod']:<4} прот={r['protocol']:<3} "
                f"вкл={'да' if r['enabled'] else 'нет'} {mark}  -- {r['caption']}\n"
            )
        out.write("\n")

with open(OUT_JSON, "w", encoding="utf-8") as out:
    json.dump(rows, out, ensure_ascii=False, indent=1)

print("written:", OUT_TXT)
