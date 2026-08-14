from pathlib import Path
import sys

p = Path(sys.argv[1])
s = p.read_text(encoding='utf-8-sig').replace('\r\n','\n')
old = '''    if(ok) show(L"PASS: Windows successfully opened PhoneBridge Camera and read a video frame.\\n\\nIf a browser still says busy/blocked, fully close that browser and any Camera/Zoom/OBS app, then reopen it and select PhoneBridge Camera.\\n\\nReport: "+reportPath());
    else show(L"FAIL: Windows could not fully activate/read PhoneBridge Camera.\\n\\nThe exact HRESULT has been saved here:\\n"+reportPath()+L"\\n\\nUse Repair camera, then run this test again.",MB_ICONERROR);
'''
new = '''    if(ok) show(L"PASS: Windows successfully opened PhoneBridge Camera and read a video frame.\\n\\nIf a browser still says busy/blocked, fully close that browser and any Camera/Zoom/OBS app, then reopen it and select PhoneBridge Camera.\\n\\nReport: "+reportPath());
    else {
        std::wstring details = L"FAIL: Windows could not fully activate/read PhoneBridge Camera.\\n\\nLast HRESULT: " + HrText(hr) +
            L"\\n\\nThe full stage-by-stage report is here:\\n" + reportPath() +
            L"\\n\\nIf this still fails after v1.6.2, send the HRESULT or CameraHealth.txt.";
        show(details,MB_ICONERROR);
    }
'''
if old not in s:
    raise SystemExit('Probe patch anchor missing')
s = s.replace(old,new,1)
if 'Last HRESULT:' not in s:
    raise SystemExit('Verbose HRESULT marker missing')
p.write_text(s,encoding='utf-8',newline='\n')
print('Applied PhoneBridge v1.6.2 verbose Camera Health failure dialog')
