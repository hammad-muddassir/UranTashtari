import cv2
import threading
import time
from ultralytics import YOLO

URL = "http://192.168.4.1/stream"
MODEL_PATH = r"C:\Users\Rayyyan\Documents\capdata\best.pt"
IMGSZ = 416
CONF = 0.4
SKIP = 1  # run the model on every Nth frame (set 2 if still laggy)


class LatestFrame:
    """Reads the stream in a background thread and keeps only the newest frame."""

    def __init__(self, url):
        self.cap = cv2.VideoCapture(url)
        self.cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
        self.frame = None
        self.running = True
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        while self.running:
            ok, f = self.cap.read()
            if ok:
                self.frame = f
            else:
                time.sleep(0.01)

    def read(self):
        return self.frame

    def stop(self):
        self.running = False
        self.cap.release()


model = YOLO(MODEL_PATH)
stream = LatestFrame(URL)

cv2.namedWindow("cam", cv2.WINDOW_NORMAL)
cv2.resizeWindow("cam", 960, 720)

printed_shape = False
n = 0
last_boxes = []
snap = 0
t_prev = time.time()
fps = 0.0

while True:
    frame = stream.read()
    if frame is None:
        if cv2.waitKey(10) & 0xFF == ord("q"):
            break
        continue

    if not printed_shape:
        print("Stream frame shape (h, w, channels):", frame.shape)
        printed_shape = True

    n += 1
    infer_ms = 0.0
    if n % SKIP == 0:
        r = model.predict(frame, imgsz=IMGSZ, conf=CONF, verbose=False)[0]
        infer_ms = r.speed["inference"]
        last_boxes = []
        for b in r.boxes:
            x1, y1, x2, y2 = map(int, b.xyxy[0])
            last_boxes.append((x1, y1, x2, y2, float(b.conf[0])))

    view = frame.copy()
    for x1, y1, x2, y2, c in last_boxes:
        cv2.rectangle(view, (x1, y1), (x2, y2), (0, 255, 0), 2)
        cv2.putText(view, f"cap {c:.2f}", (x1, max(y1 - 6, 12)),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 255, 0), 2)

    now = time.time()
    fps = 0.9 * fps + 0.1 * (1.0 / max(now - t_prev, 1e-6))
    t_prev = now
    cv2.putText(view, f"{fps:.1f} fps  infer {infer_ms:.0f} ms", (8, 22),
                cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 255, 255), 2)

    cv2.imshow("cam", view)
    key = cv2.waitKey(1) & 0xFF
    if key == ord("q"):
        break
    if key == ord("s"):
        snap += 1
        cv2.imwrite(f"snapshot_{snap}.jpg", frame)
        print("saved", f"snapshot_{snap}.jpg")

stream.stop()
cv2.destroyAllWindows()