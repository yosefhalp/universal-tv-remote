param([string]$SigningDirectory = "$env:USERPROFILE\.config\karov\signing")
$ErrorActionPreference = 'Stop'
if (-not $env:JAVA_HOME -or -not $env:ANDROID_HOME) { throw 'יש להגדיר JAVA_HOME ו-ANDROID_HOME לפני הבנייה.' }
New-Item -ItemType Directory -Path $SigningDirectory -Force | Out-Null
$keyFile = Join-Path $SigningDirectory 'karov-release.jks'
$passwordFile = Join-Path $SigningDirectory 'password.xml'
# אותו מפתח חתימה נחוץ לכל עדכון עתידי. הסיסמה נשמרת מוצפנת ב-DPAPI, ללא קובץ טקסט גלוי.
if (-not (Test-Path -LiteralPath $keyFile)) {
    if (Test-Path -LiteralPath $passwordFile) { throw 'קיימת סיסמה ללא מפתח. יש לבדוק את גיבוי החתימה לפני יצירה מחדש.' }
    $bytes = New-Object byte[] 48
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    $random.GetBytes($bytes); $random.Dispose()
    $secure = ConvertTo-SecureString -String ([Convert]::ToBase64String($bytes)) -AsPlainText -Force
    $secure | Export-Clixml -LiteralPath $passwordFile
    [Array]::Clear($bytes,0,$bytes.Length)
}
$secure = Import-Clixml -LiteralPath $passwordFile
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
    $env:KAROV_STORE_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    $env:KAROV_KEYSTORE = $keyFile
    if (-not (Test-Path -LiteralPath $keyFile)) {
        & "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -keystore $keyFile -storetype JKS -alias karov -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Karov Family Support' -storepass:env KAROV_STORE_PASSWORD -keypass:env KAROV_STORE_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw 'יצירת מפתח החתימה נכשלה.' }
    }
    if (-not $env:KAROV_BUILD_DIR) { $env:KAROV_BUILD_DIR = "$env:USERPROFILE\.cache\karov-android-build" }
    & "$PSScriptRoot\gradlew.bat" -p $PSScriptRoot assembleRelease --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'בניית גרסת ההתקנה נכשלה.' }
    $artifacts = Join-Path (Split-Path -Parent $PSScriptRoot) 'artifacts'
    New-Item -ItemType Directory -Path $artifacts -Force | Out-Null
    Copy-Item -LiteralPath "$env:KAROV_BUILD_DIR\app\outputs\apk\release\app-release.apk" -Destination (Join-Path $artifacts 'karov-tv-1.0.0.apk')
    Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $artifacts 'karov-tv-1.0.0.apk')
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    Remove-Item Env:KAROV_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:KAROV_KEYSTORE -ErrorAction SilentlyContinue
}
