from pathlib import Path
import sys
p=Path(sys.argv[1])
s=p.read_text(encoding='utf-8')
marker='bool sendControl(const std::string& json);'
if marker not in s:
    anchor='uint32_t be32(const uint8_t* p)'
    pos=s.find(anchor)
    if pos < 0:
        raise SystemExit('be32 anchor not found')
    s=s[:pos]+marker+'\n\n'+s[pos:]
p.write_text(s,encoding='utf-8')
print('Forward-declared sendControl for v1 helpers')
