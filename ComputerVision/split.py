import os, re, shutil, collections

export = r"C:\Users\Rayyyan\Documents\capdata\export"
out    = r"C:\Users\Rayyyan\Documents\capdata\dataset"
os.makedirs(out, exist_ok=True)

val_sessions = {"Dawoodlowlighting"}   # whole session goes to val
split_sessions = {"DawoodNoCap"}                      # first 70% train, last 30% val
train_fraction = 0.7

pairs = []
for split in ("train", "valid", "test"):
    img_dir = os.path.join(export, split, "images")
    lbl_dir = os.path.join(export, split, "labels")
    if not os.path.isdir(img_dir):
        continue
    for f in os.listdir(img_dir):
        if f.lower().endswith((".jpg", ".jpeg", ".png")):
            m = re.match(r"^(.+?)_(\d{4})_", f)
            session = m.group(1) if m else "unknown"
            num = int(m.group(2)) if m else 0
            pairs.append((os.path.join(img_dir, f),
                          os.path.join(lbl_dir, os.path.splitext(f)[0] + ".txt"),
                          f, session, num))

max_num = collections.defaultdict(int)
for *_, session, num in pairs:
    max_num[session] = max(max_num[session], num)

counts = collections.Counter()
for img, lbl, name, session, num in pairs:
    if session in val_sessions:
        part = "val"
    elif session in split_sessions:
        part = "train" if num <= max_num[session] * train_fraction else "val"
    else:
        part = "train"
    counts[(session, part)] += 1

    os.makedirs(os.path.join(out, "images", part), exist_ok=True)
    os.makedirs(os.path.join(out, "labels", part), exist_ok=True)
    shutil.copy(img, os.path.join(out, "images", part, name))
    dst = os.path.join(out, "labels", part, os.path.splitext(name)[0] + ".txt")
    if os.path.exists(lbl):
        shutil.copy(lbl, dst)
    else:
        open(dst, "w").close()

with open(os.path.join(out, "data.yaml"), "w") as f:
    f.write("path: /content/dataset\ntrain: images/train\nval: images/val\nnames:\n  0: cap\n")

for (s, p), n in sorted(counts.items()):
    print(f"{s:20s} {p:6s} {n}")