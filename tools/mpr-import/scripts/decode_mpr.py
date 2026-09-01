import struct, sys

SRC = r"docs/oldversion/BN1_MCA1 NEW.mpr"
OUT = r"tools/mpr-import/output/mpr_decoded.txt"

data = open(SRC, "rb").read()

def decode(pos):
    tag = data[pos]; pos += 1
    if tag == 0:
        return ('Null', None), pos
    if tag == 1:
        return ('ListStart', None), pos
    if tag == 2:
        v = struct.unpack_from('<b', data, pos)[0]; pos += 1
        return ('Int8', v), pos
    if tag == 3:
        v = struct.unpack_from('<h', data, pos)[0]; pos += 2
        return ('Int16', v), pos
    if tag == 4:
        v = struct.unpack_from('<i', data, pos)[0]; pos += 4
        return ('Int32', v), pos
    if tag == 5:
        v = data[pos:pos+10]; pos += 10
        return ('Extended', v.hex()), pos
    if tag == 6:
        l = data[pos]; pos += 1
        s = data[pos:pos+l].decode('cp1251', errors='replace'); pos += l
        return ('String', s), pos
    if tag == 7:
        l = data[pos]; pos += 1
        s = data[pos:pos+l].decode('cp1251', errors='replace'); pos += l
        return ('Ident', s), pos
    if tag == 8:
        return ('False', False), pos
    if tag == 9:
        return ('True', True), pos
    if tag == 10:
        l = struct.unpack_from('<I', data, pos)[0]; pos += 4
        b = data[pos:pos+l]; pos += l
        return ('Binary', f'<{l} bytes: {b[:32].hex()}{"..." if l>32 else ""}>'), pos
    if tag == 11:
        items = []
        while True:
            l = data[pos]; pos += 1
            if l == 0:
                break
            items.append(data[pos:pos+l].decode('cp1251', errors='replace')); pos += l
        return ('Set', items), pos
    if tag == 12:
        l = struct.unpack_from('<I', data, pos)[0]; pos += 4
        s = data[pos:pos+l].decode('cp1251', errors='replace'); pos += l
        return ('LString', s), pos
    if tag == 13:
        return ('Nil', None), pos
    if tag == 14:
        return ('CollectionStart', None), pos
    if tag == 15:
        v = struct.unpack_from('<f', data, pos)[0]; pos += 4
        return ('Single', v), pos
    if tag == 16:
        v = struct.unpack_from('<q', data, pos)[0]; pos += 8
        return ('Currency', v/10000.0), pos
    if tag == 17:
        v = struct.unpack_from('<d', data, pos)[0]; pos += 8
        return ('Date', v), pos
    if tag == 18:
        l = struct.unpack_from('<I', data, pos)[0]; pos += 4
        s = data[pos:pos+l*2].decode('utf-16le', errors='replace'); pos += l*2
        return ('WString', s), pos
    if tag == 19:
        v = struct.unpack_from('<q', data, pos)[0]; pos += 8
        return ('Int64', v), pos
    if tag == 20:
        l = struct.unpack_from('<I', data, pos)[0]; pos += 4
        s = data[pos:pos+l].decode('utf-8', errors='replace'); pos += l
        return ('UTF8String', s), pos
    if tag == 21:
        v = struct.unpack_from('<d', data, pos)[0]; pos += 8
        return ('Double', v), pos
    if tag == 22:
        endp = data.index(0, pos)
        s = data[pos:endp].decode('cp1251', errors='replace')
        pos = endp+1
        return ('CString', s), pos
    if tag == 23:
        s = data[pos:pos+1].decode('utf-8', errors='replace'); pos += 1
        return ('UTF8Char', s), pos
    raise ValueError(f'unknown tag {tag} at offset {pos-1}')

HEADER_LEN = 0x28  # "MPR" magic + fixed header, TLV stream starts here (verified by hex inspection)

pos = HEADER_LEN
depth = 0
count = 0
with open(OUT, "w", encoding="utf-8") as out:
    out.write(f"# header (0x00-0x{HEADER_LEN-1:02x}): {data[:HEADER_LEN].hex()}\n\n")
    try:
        while pos < len(data):
            off = pos
            (kind, val), pos = decode(pos)
            count += 1
            if kind in ('ListStart', 'CollectionStart'):
                depth += 1
                out.write(f"{'  '*depth}[0x{off:06x}] {kind}\n")
            elif kind == 'Null' and depth > 0:
                out.write(f"{'  '*depth}[0x{off:06x}] end-of-list\n")
                depth -= 1
            else:
                shown = repr(val) if not isinstance(val, str) else val.replace('\r\n', '\\n')
                out.write(f"{'  '*depth}[0x{off:06x}] {kind}: {shown}\n")
    except Exception as e:
        out.write(f"\n!!! STOPPED at offset 0x{pos:06x} after {count} tokens: {e}\n")
        out.write(f"context bytes: {data[max(0,pos-16):pos+32].hex()}\n")

print(f"tokens={count} last_pos=0x{pos:06x} file_len=0x{len(data):06x}")
