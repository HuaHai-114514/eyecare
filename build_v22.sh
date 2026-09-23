#!/bin/bash
# 构建 v2.2（高清 logo 母版 1017px + 结构修正）并交付
cd /data/user/0/com.ai.assistance.operit/files/workspace/bdf4b9b6-4b62-4e3e-996f-f38d6669018c || exit 9
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew assembleDebug --console=plain 2>&1 | tail -n 10
echo "BUILD_EXIT_CODE=${PIPESTATUS[0]}"

APK=app/build/outputs/apk/debug/app-debug.apk
if [ -f "$APK" ]; then
  cp "$APK" /sdcard/Download/EyeCare-v2.2-debug.apk
  ls -la /sdcard/Download/EyeCare-v2.2-debug.apk
  md5sum /sdcard/Download/EyeCare-v2.2-debug.apk
  echo "--- APK 内各密度 logo 占比（按可视区 72/108） ---"
  python3 - <<'EOF'
import zipfile, io
from PIL import Image
z = zipfile.ZipFile('/sdcard/Download/EyeCare-v2.2-debug.apk')
for n in sorted(x for x in z.namelist() if 'ic_launcher_fg' in x):
    im = Image.open(io.BytesIO(z.read(n))).convert('RGBA')
    al = im.split()[3]
    bb = al.point(lambda v: 255 if v > 128 else 0).getbbox()
    s = im.size[0]
    vis = s * 72.0 / 108.0
    print('  %-42s %-10s logo %3dpx  %.1f%%（占整画布 %.1f%%）'
          % (n, str(im.size), bb[2]-bb[0], 100.0*(bb[2]-bb[0])/vis, 100.0*(bb[2]-bb[0])/s))
EOF
else
  echo "APK NOT FOUND"
fi