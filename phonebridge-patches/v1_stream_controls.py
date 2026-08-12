from pathlib import Path
import sys

p=Path(sys.argv[1])
s=p.read_text(encoding='utf-8')

helper='''    private int controlInt(String json, int fallback) {\n        try {\n            int p = json.indexOf("\\\"value\\\":");\n            if (p < 0) return fallback;\n            p += 8;\n            int e = p;\n            while (e < json.length() && "0123456789-".indexOf(json.charAt(e)) >= 0) e++;\n            return Integer.parseInt(json.substring(p, e));\n        } catch (Exception ignored) { return fallback; }\n    }\n\n'''
anchor='    private void handleControl(String json) {\n'
if 'private int controlInt(' not in s:
    if anchor not in s: raise SystemExit('handleControl anchor not found')
    s=s.replace(anchor,helper+anchor,1)

needle='''        if (json.contains("\\\"torch\\\":false") || json.contains("\\\"cmd\\\":\\\"torch\\\",\\\"value\\\":false")) setTorch(false);\n'''
insert=needle+'''        if (json.contains("\\\"cmd\\\":\\\"quality\\\"")) setJpegQuality(controlInt(json, jpegQuality));\n        if (json.contains("\\\"cmd\\\":\\\"fps\\\"")) setMaxFps(controlInt(json, maxFps));\n'''
if 'setJpegQuality(controlInt(json, jpegQuality))' not in s:
    if needle not in s: raise SystemExit('torch control anchor not found')
    s=s.replace(needle,insert,1)

p.write_text(s,encoding='utf-8')
print('Added PhoneBridge v1 quality/FPS remote controls')
