# Extracts named files from a remote zip with HTTP range requests (only those bytes are downloaded):
#   remote_zip.py <url> <out-dir> <file name>...
import io, sys, zipfile, urllib.request, os
URL, OUT = sys.argv[1], sys.argv[2]
want = sys.argv[3:]
class R(io.RawIOBase):
    def __init__(s, url):
        s.url=url; s.pos=0
        req=urllib.request.Request(url, method='HEAD'); s.size=int(urllib.request.urlopen(req).headers['Content-Length'])
    def seekable(s): return True
    def readable(s): return True
    def tell(s): return s.pos
    def seek(s, o, w=0):
        s.pos = o if w==0 else s.pos+o if w==1 else s.size+o
        return s.pos
    def readinto(s, b):
        n=min(len(b), s.size-s.pos)
        if n<=0: return 0
        req=urllib.request.Request(s.url, headers={'Range': f'bytes={s.pos}-{s.pos+n-1}'})
        d=urllib.request.urlopen(req).read(); b[:len(d)]=d; s.pos+=len(d); return len(d)
z=zipfile.ZipFile(io.BufferedReader(R(URL), buffer_size=1<<20))
for i in z.infolist():
    name=os.path.basename(i.filename)
    if name in want:
        print(name, i.file_size)
        with z.open(i) as src, open(os.path.join(OUT,name),'wb') as dst:
            while True:
                c=src.read(1<<20)
                if not c: break
                dst.write(c)
