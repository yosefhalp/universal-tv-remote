param([string]$BuildDirectory = "$env:USERPROFILE\.cache\karov-android-build", [string]$Notes = 'עדכונים אוטומטיים ושמירת הזיווגים, עם סיסמת התקנה קבועה.')
$ErrorActionPreference = 'Stop'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$web = Join-Path $root 'support\web'
$manifest = [ordered]@{ schema = 1 }
$modules = [ordered]@{ tv = 'app'; phone = 'phone' }
foreach ($channel in $modules.Keys) {
    $output = Join-Path $BuildDirectory ($modules[$channel] + '\outputs\apk\release')
    $metadata = Get-Content -LiteralPath (Join-Path $output 'output-metadata.json') -Raw | ConvertFrom-Json
    $entry = $metadata.elements[0]
    if ($entry.versionName -notmatch '^\d+\.\d+\.\d+$' -or $entry.versionCode -lt 1) { throw 'מספר גרסה לא תקין' }
    $source = Join-Path $output $entry.outputFile
    $filename = "karov-$channel-$($entry.versionName).apk"
    # כתובת גרסה אינה משתנה: לקוח שהתחיל הורדה לא יקבל קובץ של גרסה אחרת באמצע הפרסום.
    $target = Join-Path $web $filename
    $hash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
    if ((Test-Path -LiteralPath $target) -and (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hash) { throw "כבר קיים קובץ אחר לגרסה $filename" }
    Copy-Item -LiteralPath $source -Destination $target -Force
    Copy-Item -LiteralPath $source -Destination (Join-Path $web "karov-$channel.apk") -Force
    $manifest[$channel] = [ordered]@{ applicationId = $metadata.applicationId; versionCode = $entry.versionCode; versionName = $entry.versionName; minSdk = 28; bytes = (Get-Item -LiteralPath $source).Length; sha256 = $hash; path = $filename; notes = $Notes }
}
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $web 'updates.json') -Encoding utf8NoBOM
Write-Output 'חבילות העדכון ונתוני הגרסה מוכנים לפרסום.'
