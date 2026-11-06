param([string]$SecretPath = "$env:USERPROFILE\.config\karov\installer-key.xml")
$ErrorActionPreference = 'Stop'
# מיועד למתקין בלבד: העתקה ללוח במקום הצגת סוד במסוף או בלוג.
$secret = Import-Clixml -LiteralPath $SecretPath
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
try { Set-Clipboard -Value ([Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
Write-Output 'מפתח ההתקנה הועתק ללוח. הדביקו אותו במסך ההכנה בסטרימר ונקו את הלוח לאחר השימוש.'
