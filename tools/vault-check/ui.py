"""ui.py dump | ui.py tap <text> : list on-screen texts with centres, or tap the first node whose text contains <text>."""
import subprocess, sys, os, re, xml.etree.ElementTree as ET
adb=os.path.join(os.environ['LOCALAPPDATA'],'Android','Sdk','platform-tools','adb.exe')
def dump():
    subprocess.run([adb,'shell','uiautomator','dump','/sdcard/ui.xml'],capture_output=True)
    x=subprocess.run([adb,'exec-out','cat','/sdcard/ui.xml'],capture_output=True).stdout.decode('utf-8','replace')
    out=[]
    for n in ET.fromstring(x).iter('node'):
        t=n.get('text') or n.get('content-desc') or ''
        if not t.strip(): continue
        m=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', n.get('bounds'))
        x1,y1,x2,y2=map(int,m.groups()); out.append((t.replace('\n',' / '),(x1+x2)//2,(y1+y2)//2))
    return out
if sys.argv[1]=='dump':
    for t,x,y in dump(): print(f'{x:5d},{y:5d}  {t[:90]}')
elif sys.argv[1]=='tap':
    want=sys.argv[2].lower(); nth=int(sys.argv[3]) if len(sys.argv)>3 else 0
    hits=[(t,x,y) for t,x,y in dump() if want in t.lower()]
    if len(hits)<=nth: print('NOT FOUND:', sys.argv[2], '| on screen:', [t[:30] for t,_,_ in dump()][:25]); sys.exit(1)
    t,x,y=hits[nth]; subprocess.run([adb,'shell','input','tap',str(x),str(y)]); print('tapped', repr(t[:50]), x, y)
