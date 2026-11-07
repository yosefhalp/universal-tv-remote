param([string]$PasswordFile = '', [string]$Server = 'yoseff@192.168.31.100')
$ErrorActionPreference = 'Stop'
if ($Server -notmatch '^[a-zA-Z0-9_.-]+@[a-zA-Z0-9_.-]+$') { throw 'כתובת שרת לא תקינה' }
$directory = Join-Path $env:USERPROFILE '.config\karov'
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$secret = if ($PasswordFile) { Import-Clixml -LiteralPath $PasswordFile } else { Read-Host 'בחר סיסמת התקנה קבועה (8 תווים לפחות)' -AsSecureString }
if ($secret -isnot [Security.SecureString]) { throw 'נדרשת סיסמה מוצפנת של Windows' }
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
try {
    $start = [Diagnostics.ProcessStartInfo]::new('node')
    $start.ArgumentList.Add((Join-Path $PSScriptRoot 'password-verifier.mjs'))
    $start.UseShellExecute = $false; $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true; $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
    $start.StandardInputEncoding = [Text.UTF8Encoding]::new($false)
    $process = [Diagnostics.Process]::Start($start)
    $encoded = ConvertTo-Json -Compress -InputObject ([Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer))
    $process.StandardInput.Write($encoded); $process.StandardInput.Close(); $encoded = $null
    $verifier = $process.StandardOutput.ReadToEnd(); $process.WaitForExit()
    if ($process.ExitCode -ne 0 -or $verifier -notmatch '^scrypt-v1:[A-Za-z0-9_-]{22}:[A-Za-z0-9_-]{43}$') { throw 'לא ניתן ליצור אימות סיסמה' }
} finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
# הגיבוב בלבד נשלח לשרת. קובץ התצורה מגובה בהרשאה פרטית, בלי להציג את תוכנו.
$remote = @'
import sys,pathlib,os,re,shutil
p=pathlib.Path("/opt/yosef-services/karov/deployment.env")
value=sys.stdin.read().strip()
if not re.fullmatch(r"scrypt-v1:[A-Za-z0-9_-]{22}:[A-Za-z0-9_-]{43}",value): raise SystemExit(2)
backup=p.with_name("deployment.env.before-password")
shutil.copyfile(p,backup); os.chmod(backup,0o600)
lines=[x for x in p.read_text().splitlines() if not x.startswith("ENROLLMENT_PASSWORD_VERIFIER=")]
lines.append("ENROLLMENT_PASSWORD_VERIFIER="+value)
temporary=p.with_name("deployment.env.pending")
temporary.write_text("\n".join(lines)+"\n"); os.chmod(temporary,0o600); os.replace(temporary,p)
'@
$python = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($remote))
$verifier | & ssh $Server "sudo python3 -c 'import base64;exec(base64.b64decode(""$python""))'"
$verifier = $null
if ($LASTEXITCODE -ne 0) { throw 'עדכון התצורה בשרת נכשל' }
& ssh $Server 'cd /opt/yosef-services/karov && sudo docker compose --env-file deployment.env -f support/deploy/compose.yml up -d --no-build'
if ($LASTEXITCODE -ne 0) { throw 'התצורה הוכנה אך הפעלת השירות נכשלה; קיים גיבוי deployment.env.before-password בשרת' }
$target = Join-Path $directory 'installer-password.xml'
if (Test-Path -LiteralPath $target) { Copy-Item -LiteralPath $target -Destination (Join-Path $directory 'installer-password.previous.xml') -Force }
$secret | Export-Clixml -LiteralPath $target
Write-Output 'סיסמת ההתקנה הוגדרה. מכשירים שכבר רשומים שומרים על הגישה.'
