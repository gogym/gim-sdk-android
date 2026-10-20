$ErrorActionPreference = 'Stop'
Set-Location 'D:\GitHub\gim-sdk-android\wiki'
$pages = Get-ChildItem -Filter *.md | ForEach-Object { $_.BaseName }
$set = [System.Collections.Generic.HashSet[string]]::new([string[]]$pages)
Write-Output ('total pages = ' + $pages.Count)
$bad = 0
foreach ($f in Get-ChildItem -Filter *.md) {
  $txt = Get-Content -LiteralPath $f.FullName -Raw -Encoding UTF8
  foreach ($m in [regex]::Matches($txt, '\]\(([^)]+)\)')) {
    $t = $m.Groups[1].Value
    if ($t -match '^https?://' -or $t -match '^#' -or $t -match '^mailto:') { continue }
    $t = ($t -split '#')[0]
    $t = ($t -split '\?')[0]
    if ($t -eq '') { continue }
    $clean = [System.Net.WebUtility]::UrlDecode($t)
    $clean = $clean -replace '\.md$', ''
    if (-not $set.Contains($clean)) {
      Write-Output ('BROKEN: ' + $f.Name + ' -> [' + $t + ']')
      $bad++
    }
  }
}
Write-Output ('broken internal links = ' + $bad)

# English pages created in this task (hardcoded list) - check for CJK prose leaks
$enNames = @(
  'Home-EN','Quick-Start','Architecture-Overview','Core-Concept-Packet-and-Codec',
  'Core-Concept-Connection-and-Auth','Core-Concept-Event-Listener-and-Messaging',
  'Messaging-Single-and-Group-Chat','Messaging-Heartbeat-and-Auto-Reconnect',
  'Configuration-Reference-ImConfig','Android-Integration-and-Lifecycle',
  'WebRTC-One-to-One-Call','WebRTC-Group-Video-Call'
)
$cjk = [regex]::new('[\u4e00-\u9fff]')
Write-Output '=== CJK leaks in English pages (header/link targets allowed) ==='
foreach ($n in $enNames) {
  $path = Join-Path (Get-Location) ($n + '.md')
  if (-not (Test-Path -LiteralPath $path)) { Write-Output ('MISSING EN PAGE: ' + $n); continue }
  $lines = Get-Content -LiteralPath $path -Encoding UTF8
  for ($i = 0; $i -lt $lines.Count; $i++) {
    $line = $lines[$i]
    $stripped = [regex]::Replace($line, '\]\([^)]*\)', ']()')   # drop link targets (may be Chinese filenames)
    if ($cjk.IsMatch($stripped)) {
      Write-Output ($n + ':' + ($i+1) + ': ' + $line.Trim())
    }
  }
}
Write-Output '=== done ==='
