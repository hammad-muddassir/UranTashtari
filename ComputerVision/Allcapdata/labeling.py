import os

# Folder that contains your session folders (sunny, shade, backlit, ...)
root = r"C:\Users\Rayyyan\Documents\capdata\Allcapdata"

for session in os.listdir(root):
    p = os.path.join(root, session)
    if not os.path.isdir(p):
        continue
    files = sorted(f for f in os.listdir(p)
                   if f.lower().endswith((".jpg", ".jpeg", ".png")))
    for i, f in enumerate(files):
        ext = os.path.splitext(f)[1]
        new = f"{session}_{i:04d}{ext}"
        if f == new:
            continue
        os.rename(os.path.join(p, f), os.path.join(p, new))
    print(f"{session}: renamed {len(files)} files")