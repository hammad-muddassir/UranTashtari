import cv2, time, os

URL = "http://192.168.4.1"   # use the IP that opens your camera page
SESSION = "s01_indoor"                 # change for every recording session
INTERVAL = 0.4                         # seconds between saved frames

os.makedirs(SESSION, exist_ok=True)
cap = cv2.VideoCapture(URL)
last, n = 0, 0

while True:
    ok, frame = cap.read()
    if not ok:
        cap.release()
        time.sleep(1)
        cap = cv2.VideoCapture(URL)
        continue

    cv2.imshow("ESP32 preview (press Q to stop)", frame)
    now = time.time()
    if now - last >= INTERVAL:
        cv2.imwrite(f"{SESSION}/{SESSION}_{n:05d}.jpg", frame,
                    [cv2.IMWRITE_JPEG_QUALITY, 95])
        n += 1
        last = now
    if cv2.waitKey(1) & 0xFF in (ord('q'), 27):
        break

cap.release()
cv2.destroyAllWindows()
print(f"Saved {n} frames")