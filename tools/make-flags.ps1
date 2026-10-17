# Renders flag icons into res/drawable-nodpi/flag_<code>.png from flag-icons (MIT, see
# flag-icons-LICENSE.txt) with headless Chrome: 4:3, rounded corners and a faint edge so dark
# flags stay visible on the dark theme.
#   .\tools\make-flags.ps1 fr de es-ct      (codes as in flag-icons: ISO 3166, plus es-ct etc.)
param(
    [Parameter(Mandatory = $true)][string[]]$Codes,
    [string]$Work = (Join-Path $env:TEMP "playthenews-flags")
)
$ErrorActionPreference = "Stop"

$VERSION = "7.2.3"
$CHROME  = "C:\Program Files\Google\Chrome\Application\chrome.exe"
$OUT     = Join-Path $PSScriptRoot "..\res\drawable-nodpi"
$W = 120; $H = 90; $R = 9

New-Item -ItemType Directory -Force -Path $Work, $OUT | Out-Null

foreach ($c in $Codes) {
    $svg = Join-Path $Work "$c.svg"
    Invoke-WebRequest "https://cdn.jsdelivr.net/npm/flag-icons@$VERSION/flags/4x3/$c.svg" -OutFile $svg

    $html = Join-Path $Work "$c.html"
    Set-Content -Path $html -Encoding UTF8 -Value @"
<!doctype html><html><head><style>
html,body{margin:0;background:transparent}
.f{width:${W}px;height:${H}px;border-radius:${R}px;overflow:hidden;position:relative}
.f img{width:${W}px;height:${H}px;display:block}
.f::after{content:"";position:absolute;inset:0;border-radius:${R}px;box-shadow:inset 0 0 0 2px rgba(255,255,255,.22)}
</style></head><body><div class="f"><img src="$c.svg"></div></body></html>
"@

    $png = Join-Path (Resolve-Path $OUT) ("flag_" + $c.Replace("-", "_") + ".png")
    # Piping to Out-Null makes PowerShell wait for Chrome, a GUI-subsystem program.
    & $CHROME --headless --disable-gpu --hide-scrollbars --default-background-color=00000000 `
        "--window-size=$W,$H" "--screenshot=$png" ([Uri]$html).AbsoluteUri 2>&1 | Out-Null
    if (-not (Test-Path $png)) { throw "Chrome did not render $c" }
    Write-Host "==> $png"
}
