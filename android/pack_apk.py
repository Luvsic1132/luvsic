"""把 assets/ 和 classes.dex 以正确的正斜杠路径写进 APK。

aapt2 在 Windows 上会把 -A 传入的资源写成 "ui\\app.js" 这种反斜杠条目，
Android 的 AssetManager 按 "/" 解析，于是运行期 assets 全查不到。
这里改用 Python zipfile 直接写条目，路径一律用 "/"。
"""
import os
import sys
import zipfile

apk = sys.argv[1]          # 输入：aapt2 link 产出的 base.apk
assets_dir = sys.argv[2]   # assets 根目录
dex = sys.argv[3]          # classes.dex
out = sys.argv[4]          # 输出 apk

EXTS = {".html": zipfile.ZIP_DEFLATED, ".js": zipfile.ZIP_DEFLATED,
        ".css": zipfile.ZIP_DEFLATED, ".json": zipfile.ZIP_DEFLATED,
        ".txt": zipfile.ZIP_DEFLATED}

src = zipfile.ZipFile(apk, "r")
dst = zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED)

# 1. 原 APK 里的条目（AndroidManifest.xml、resources.arsc 等）
for item in src.infolist():
    data = src.read(item.filename)
    zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
    zi.compress_type = item.compress_type
    zi.external_attr = item.external_attr
    dst.writestr(zi, data)

# 2. assets/ 下的所有文件，路径统一 "assets/xxx/yyy"
count = 0
for base, dirs, files in os.walk(assets_dir):
    dirs.sort()
    for fn in sorted(files):
        full = os.path.join(base, fn)
        rel = os.path.relpath(full, assets_dir).replace(os.sep, "/")
        name = "assets/" + rel
        ext = os.path.splitext(fn)[1].lower()
        zi = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
        zi.compress_type = EXTS.get(ext, zipfile.ZIP_STORED)
        zi.external_attr = 0o644 << 16
        with open(full, "rb") as f:
            dst.writestr(zi, f.read())
        count += 1

# 3. classes.dex 必须叫 classes.dex 放在根
zi = zipfile.ZipInfo("classes.dex", date_time=(2026, 1, 1, 0, 0, 0))
zi.compress_type = zipfile.ZIP_DEFLATED
zi.external_attr = 0o644 << 16
with open(dex, "rb") as f:
    dst.writestr(zi, f.read())

dst.close()
src.close()
print("packed %d asset files -> %s" % (count, out))
