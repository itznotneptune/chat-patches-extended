# Makes a shareable copy of a Minecraft log for a Chat Patches Extended bug report.
# Usage (PowerShell, from anywhere):
#   powershell -ExecutionPolicy Bypass -File redact-log.ps1 -Log "<game dir>\logs\latest.log" -Player "<your Minecraft name>"
# Writes latest.redacted.log next to the input. ALWAYS read the result before sharing it.
#
# Removes: every chat line ([CHAT]), the "Setting user" line, server addresses on connect lines.
# Replaces: your Windows user name and your Minecraft name with placeholders.
# Not executed on Windows by the author (no PowerShell on the build machine); it uses only standard cmdlets.
param(
    [Parameter(Mandatory = $true)][string]$Log,
    [string]$Player = ""
)
$out = [System.IO.Path]::ChangeExtension($Log, ".redacted.log")
$user = [regex]::Escape($env:USERNAME)
Get-Content -LiteralPath $Log | Where-Object {
    $_ -notmatch '\[CHAT\]' -and $_ -notmatch 'Setting user:'
} | ForEach-Object {
    $line = $_ -replace $user, '<windows-user>'
    if ($Player -ne "") { $line = $line -replace [regex]::Escape($Player), '<player>' }
    $line -replace '(Connecting to )\S+', '$1<server>'
} | Set-Content -LiteralPath $out -Encoding UTF8
Write-Host "Wrote $out - check it before sharing (search it for names, server addresses and chat text)."
