param([string]$SecretPath = "$env:USERPROFILE\.config\karov\installer-key.xml")
$ErrorActionPreference = 'Stop'
# המפתח נשמר בהצפנת Windows עבור המשתמש הנוכחי בלבד. לשרת מועבר רק הגיבוב שלו.
if (Test-Path -LiteralPath $SecretPath) { throw 'קיים כבר מפתח התקנה בנתיב הזה. אין להחליף אותו בטעות.' }
New-Item -ItemType Directory -Path (Split-Path -Parent $SecretPath) -Force | Out-Null
$bytes = New-Object byte[] 32
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
$random.GetBytes($bytes)
$random.Dispose()
$key = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
ConvertTo-SecureString -String $key -AsPlainText -Force | Export-Clixml -LiteralPath $SecretPath
$sha = [Security.Cryptography.SHA256]::Create()
try { [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($key))).Replace('-','').ToLowerInvariant() }
finally { $sha.Dispose(); $key = $null; [Array]::Clear($bytes,0,$bytes.Length) }
