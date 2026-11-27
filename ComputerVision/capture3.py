import urllib.request

paths = ["/", "/stream", "/mjpeg", "/video", "/capture", "/jpg", "/cam"]

for p in paths:
    url = "http://192.168.4.1" + p
    try:
        r = urllib.request.urlopen(url, timeout=5)
        ctype = r.headers.get("Content-Type")
        first = r.read(40)
        print(f"{p:10} -> {r.status} {ctype} | starts: {first[:20]}")
        r.close()
    except Exception as e:
        print(f"{p:10} -> FAILED: {e}")