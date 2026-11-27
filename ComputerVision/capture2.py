import cv2, numpy as np, urllib.request, time, os

URL = "http://192.168.4.1/stream"
SESSION = "s01_indoor"      # change for every recording session
INTERVAL = 0.4              # seconds between saved frames

os.makedirs(SESSION, exist_ok=True)
last, n = 0, 0
buf = b""

while True:
    try:
        print("Connecting...")
        stream = urllib.request.urlopen(URL, timeout=10)
        print("Connected. Click the preview window and press Q to stop (or Ctrl+C here).")
        while True:
            buf += stream.read(4096)
            a = buf.find(b"\xff\xd8")
            b = buf.find(b"\xff\xd9", a + 2) if a != -1 else -1
            if a != -1 and b != -1:
                jpg = buf[a:b + 2]
                buf = buf[b + 2:]
                frame = cv2.imdecode(np.frombuffer(jpg, np.uint8), cv2.IMREAD_COLOR)
                if frame is None:
                    continue
                cv2.imshow("ESP32 preview (Q to stop)", frame)
                now = time.time()
                if now - last >= INTERVAL:
                    with open(f"{SESSION}/{SESSION}_{n:05d}.jpg", "wb") as f:
                        f.write(jpg)
                    n += 1
                    last = now
                    if n % 10 == 0:
                        print(f"Saved {n} frames ({frame.shape[1]}x{frame.shape[0]})")
                if cv2.waitKey(1) & 0xFF in (ord("q"), 27):
                    raise SystemExit
    except (SystemExit, KeyboardInterrupt):
        break
    except Exception as e:
        print("Stream error:", e, "- retrying in 2 s")
        buf = b""
        time.sleep(2)

cv2.destroyAllWindows()
print(f"Saved {n} frames")