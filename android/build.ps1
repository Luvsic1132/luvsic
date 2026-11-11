# Luvsic Android — 无 Gradle 构建脚本（aapt2 + javac + d8 + zipalign + apksigner）
# 用法：
#   powershell -File build.ps1            开发用 debug 包（可 run-as 调试）
#   powershell -File build.ps1 -Release   正式发布包（release 签名，可发给别的手机装）
param([switch]$Release)
$ErrorActionPreference = 'Stop'

# JDK 路径：可用环境变量覆盖
if (-not $env:JAVA_HOME) { throw '请先设置 JAVA_HOME 指向 JDK 17' }
$JDK  = $env:JAVA_HOME
if (-not $env:ANDROID_SDK_ROOT) { throw '请先设置 ANDROID_SDK_ROOT' }
$SDK  = $env:ANDROID_SDK_ROOT
$BT   = "$SDK\build-tools\34.0.0"
$PLAT = "$SDK\platforms\android-34\android.jar"
$ROOT = $PSScriptRoot
$OUT  = "$ROOT\build"
$ADB  = Join-Path $SDK 'platform-tools\adb.exe'
$VERSION_NAME = '1.28.3'

$env:JAVA_HOME = $JDK
$env:PATH = "$JDK\bin;$env:PATH"

if (Test-Path $OUT) { Remove-Item -Recurse -Force $OUT }
New-Item -ItemType Directory -Force -Path $OUT | Out-Null
New-Item -ItemType Directory -Force -Path "$OUT\classes" | Out-Null

# 0. 同步 UI（从桌面版 lumen/ui 拷过来，保持两边一致）
$UI_SRC = (Resolve-Path (Join-Path $PSScriptRoot '..\ui')).Path
$UI_DST = Join-Path $PSScriptRoot 'assets\ui'
if (Test-Path $UI_DST) { Remove-Item -Recurse -Force $UI_DST }
New-Item -ItemType Directory -Force -Path $UI_DST | Out-Null
Copy-Item "$UI_SRC\*" $UI_DST -Recurse -Force
# 手机端不用内置壁纸（横图不适配手机），不打进 APK，省体积
if (Test-Path "$UI_DST\wall") { Remove-Item -Recurse -Force "$UI_DST\wall" }
Write-Host "[0/6] 同步 UI 资源完成"

# 清单：release 去掉 debuggable，debug 保留（方便 run-as / 日志）
$MANIFEST = "$OUT\AndroidManifest.xml"
$mf = Get-Content "$ROOT\AndroidManifest.xml" -Raw
if ($Release) {
    $mf = $mf -replace 'android:debuggable="true"', ''
} elseif ($mf -notmatch 'android:debuggable') {
    $mf = $mf.Replace('android:label="Luvsic"', 'android:label="Luvsic" android:debuggable="true"')
}
Set-Content -Path $MANIFEST -Value $mf -Encoding UTF8

Write-Host '[1/6] aapt2 compile + link ...'
$verName = if ($Release) { $VERSION_NAME } else { $VERSION_NAME + '-dev' }
$aaptLink = @('-o', "$OUT\base.apk", '--manifest', $MANIFEST, '-I', $PLAT,
              '--min-sdk-version', '26', '--target-sdk-version', '34',
              '--version-code', '113', '--version-name', $verName, '--replace-version')
if (Test-Path "$ROOT\res") {
    & "$BT\aapt2.exe" compile --dir "$ROOT\res" -o "$OUT\res.zip"
    if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile 失败' }
    & "$BT\aapt2.exe" link @aaptLink "$OUT\res.zip"
} else {
    & "$BT\aapt2.exe" link @aaptLink
}
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link 失败' }

Write-Host '[2/6] javac ...'
$srcs = @(Get-ChildItem -Recurse -Filter *.java "$ROOT\src" | ForEach-Object { $_.FullName })
& "$JDK\bin\javac.exe" -nowarn -encoding UTF-8 -Xlint:-options -source 8 -target 8 -bootclasspath $PLAT -classpath $PLAT -d "$OUT\classes" $srcs
if ($LASTEXITCODE -ne 0) { throw 'javac 失败' }

Write-Host '[3/6] d8 dex ...'
$classes = @(Get-ChildItem -Recurse -Filter *.class "$OUT\classes" | ForEach-Object { $_.FullName })
& "$BT\d8.bat" --lib $PLAT --min-api 26 --output $OUT $classes
if ($LASTEXITCODE -ne 0) { throw 'd8 失败' }
if (-not (Test-Path "$OUT\classes.dex")) { throw '没有生成 classes.dex' }

Write-Host '[4/6] 打包 assets + dex ...'
& python "$ROOT\pack_apk.py" "$OUT\base.apk" "$ROOT\assets" "$OUT\classes.dex" "$OUT\app-unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw '打包 assets/dex 失败' }

Write-Host '[5/6] zipalign ...'
& "$BT\zipalign.exe" -f 4 "$OUT\app-unsigned.apk" "$OUT\app-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'zipalign 失败' }

Write-Host '[6/6] 签名 ...'
if ($Release) {
    # 正式签名：以后所有升级包都必须用同一个 keystore，否则不能覆盖安装
    $ks = "$ROOT\release.keystore"
    $props = "$ROOT\keystore.properties"
    if (-not (Test-Path $props)) {
        $pwd = 'luvsic-' + ([guid]::NewGuid().ToString('N').Substring(0, 12))
        Set-Content -Path $props -Value @("storeFile=$ks", "storePassword=$pwd", "keyAlias=luvsic", "keyPassword=$pwd") -Encoding UTF8
        Write-Host ("已生成 release keystore 密码文件: " + $props + " (务必保留，升级要用)")
    }
    $kv = @{}
    Get-Content $props | ForEach-Object { if ($_ -match '^\s*([^=]+)=(.*)$') { $kv[$matches[1]] = $matches[2] } }
    if (-not (Test-Path $ks)) {
        & "$JDK\bin\keytool.exe" -genkeypair -keystore $ks -storepass $kv['storePassword'] -keypass $kv['keyPassword'] `
            -alias $kv['keyAlias'] -keyalg RSA -keysize 2048 -validity 10000 `
            -dname "CN=Luvsic, O=Luvsic, C=CN" | Out-Null
    }
    New-Item -ItemType Directory -Force -Path "$ROOT\dist" | Out-Null
    $target = "$ROOT\dist\Luvsic-手机版-v$VERSION_NAME.apk"
    & "$BT\apksigner.bat" sign --ks $ks --ks-pass ("pass:" + $kv['storePassword']) `
        --key-pass ("pass:" + $kv['keyPassword']) --out $target "$OUT\app-aligned.apk"
    if ($LASTEXITCODE -ne 0) { throw '签名失败' }
} else {
    $ks = "$ROOT\debug.keystore"
    if (-not (Test-Path $ks)) {
        & "$JDK\bin\keytool.exe" -genkeypair -keystore $ks -storepass android -keypass android `
            -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
            -dname "CN=Android Debug,O=Android,C=US" | Out-Null
    }
    $target = "$OUT\Luvsic-手机版-debug.apk"
    & "$BT\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
        --out $target "$OUT\app-aligned.apk"
    if ($LASTEXITCODE -ne 0) { throw '签名失败' }
}

Write-Host ''
Write-Host ('APK: ' + $target)
Write-Host ('大小: ' + [math]::Round((Get-Item $target).Length / 1MB, 2) + ' MB')
