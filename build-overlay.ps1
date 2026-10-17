# Builds the RRO overlay APK that allow-lists our launcher as a news media browser caller.
$ErrorActionPreference = "Stop"

$SDK     = "C:\Users\Admin\AppData\Local\Android\Sdk"
$BT      = "$SDK\build-tools\37.0.0"
$ANDROID = "$SDK\platforms\android-36\android.jar"
$P       = "c:\Users\Admin\playthenews\overlay"
$B       = "$P\build"
$KS      = "c:\Users\Admin\playthenews\playthenews.keystore"

Remove-Item -Recurse -Force $B -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $B | Out-Null

Write-Host "==> compiling overlay resources"
& "$BT\aapt2.exe" compile --dir "$P\res" -o "$B\res.zip"
if ($LASTEXITCODE) { throw "aapt2 compile failed" }

Write-Host "==> linking overlay"
& "$BT\aapt2.exe" link -o "$B\overlay-unsigned.apk" -I $ANDROID `
    --manifest "$P\AndroidManifest.xml" `
    --min-sdk-version 21 --target-sdk-version 36 `
    "$B\res.zip"
if ($LASTEXITCODE) { throw "aapt2 link failed" }

Write-Host "==> aligning"
& "$BT\zipalign.exe" -f -p 4 "$B\overlay-unsigned.apk" "$B\overlay-aligned.apk"
if ($LASTEXITCODE) { throw "zipalign failed" }

Write-Host "==> signing"
& "$BT\apksigner.bat" sign --ks $KS --ks-pass pass:android --key-pass pass:android `
    --out "$B\playnews-callers-overlay.apk" "$B\overlay-aligned.apk" 2>&1 | Where-Object { $_ -notmatch 'WARNING' }
if ($LASTEXITCODE) { throw "apksigner failed" }

Write-Host ("==> built {0} ({1:N0} bytes)" -f "$B\playnews-callers-overlay.apk", (Get-Item "$B\playnews-callers-overlay.apk").Length)

Write-Host "==> verifying overlay resource + manifest"
& "$BT\aapt2.exe" dump resources "$B\playnews-callers-overlay.apk" 2>&1 | Select-String -Pattern "allowed_media_browser_callers|xml/"
& "$BT\aapt2.exe" dump xmltree "$B\playnews-callers-overlay.apk" --file AndroidManifest.xml 2>&1 |
    ForEach-Object { $_ -replace 'http://schemas.android.com/apk/res/android:','' -replace '\(0x[0-9a-f]+\)','' } |
    Select-String -Pattern "overlay|targetPackage|isStatic|priority|package="
