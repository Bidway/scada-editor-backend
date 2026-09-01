import re

CDBX = r"docs/oldversion/BN1 MCA1 NEW.cdbx"
DECODED = r"tools/mpr-import/output/mpr_decoded.txt"
OUT = r"tools/mpr-import/output/mpr_decoded_linked.txt"

xml = open(CDBX, "r", encoding="utf-8-sig").read()

# Each <channels:channel> block: pull id + descr (+ protocol/requestperiod as a bonus)
id_to_descr = {}
for m in re.finditer(
    r"<channels:channel>\s*<channels:id>(\d+)</channels:id>.*?<channels:descr>(.*?)</channels:descr>",
    xml, re.S):
    cid, descr = m.group(1), m.group(2).strip()
    id_to_descr[int(cid)] = descr

print(f"channels indexed from cdbx: {len(id_to_descr)}")

pat = re.compile(r"Int32: (\d+)")
matched = 0
with open(DECODED, "r", encoding="utf-8") as f, open(OUT, "w", encoding="utf-8") as out:
    for line in f:
        m = pat.search(line)
        if m:
            cid = int(m.group(1))
            if cid in id_to_descr:
                matched += 1
                line = line.rstrip("\n") + f"    <-- channel: {id_to_descr[cid]}\n"
        out.write(line)

print(f"Int32 values matched to a channel id: {matched}")
