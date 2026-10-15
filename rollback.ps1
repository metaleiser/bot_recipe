<#
  rollback.ps1 - BOTAI-RECIPE
  Revierte una instalacion usando los backups de la corrida.
  Dry-run por defecto. No borra ni sobrescribe backups validos.
  Uso: .\rollback.ps1 -Runtime "..." [-Run latest] [-Force]
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$Runtime,
  [string]$Run,
  [switch]$Force,
  [switch]$Json
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$Here = $PSScriptRoot
$m = Get-Content -LiteralPath (Join-Path $Here 'MANIFEST.json') -Raw | ConvertFrom-Json
$Runtime = (Resolve-Path -LiteralPath $Runtime).Path
function RPath($p) {
  $q = ($p -replace '/', '\')
  if ($q -like '<runtime>*') { $q = $q.Substring(9) }
  return (Join-Path $Runtime $q.TrimStart('\'))
}
# Hashes canonicos: se eliminan los CR para que CRLF y LF den el mismo resultado.
# El payload se instala con bytes canonicos (LF), asi que la restauracion debe
# compararse igual que install.ps1/verify.ps1 o un backup con CRLF daria falso FAIL.
function ToCanonical([byte[]]$bytes) {
  $out = New-Object System.Collections.Generic.List[byte] ($bytes.Length)
  foreach ($b in $bytes) { if ($b -ne 13) { $out.Add($b) } }
  return $out.ToArray()
}
function CanonicalHash([string]$path) {
  $sha = [System.Security.Cryptography.SHA256]::Create()
  $h = $sha.ComputeHash((ToCanonical ([System.IO.File]::ReadAllBytes($path))))
  $sha.Dispose()
  return [BitConverter]::ToString($h).Replace('-','')
}
function Say($x) { if (-not $Json) { Write-Host $x } }
function Stop1($x) { Say ('STOP: ' + $x); Say ('PROBLEMA : rollback abortado'); Say ('CAUSA    : ' + $x); Say 'ACCION   : no se toco nada. Revisa el run indicado.'; exit 1 }

$runsRoot = Join-Path $Runtime '_botai-runs'
# 'latest' solo debe resolver la ultima corrida de instalacion real (log-install.txt
# o pre\jar-entries-pre.txt); los temporales de compilacion/verificacion/diagnostico
# no son corridas y revertirlos no tendria sentido.
$runDirs = @(Get-ChildItem -LiteralPath $runsRoot -Directory -ErrorAction SilentlyContinue |
             Where-Object { ((Test-Path -LiteralPath (Join-Path $_.FullName 'log-install.txt')) -or (Test-Path -LiteralPath (Join-Path $_.FullName 'pre\jar-entries-pre.txt'))) -and (-not $_.Name.StartsWith('reverted-')) -and (-not (Test-Path -LiteralPath (Join-Path $_.FullName 'reverted.txt'))) } |
             Sort-Object LastWriteTime)
if ($runDirs.Count -eq 0) { Stop1 'no hay ninguna corrida de instalacion valida en <runtime>/_botai-runs' }
if (-not $Run -or $Run -eq 'latest') { $runDir = $runDirs[$runDirs.Count - 1].FullName }
else { $runDir = Join-Path $runsRoot $Run }
if (-not (Test-Path -LiteralPath $runDir)) { Stop1 ('no existe la corrida: ' + $Run) }
$destFile = Join-Path $runDir 'pre\destinos.json'
if (-not (Test-Path -LiteralPath $destFile)) { Stop1 'la corrida no tiene pre\destinos.json; no se sabe que restaurar' }
$dest = Get-Content -LiteralPath $destFile -Raw | ConvertFrom-Json

Say ''
Say ('run     : ' + $runDir)
Say ('created : ' + $dest.created)
Say ''

# --- precheck 1: runtime detenido
# Only the java processes that actually load this engine are counted. A plain Get-Process java, javaw also
# matches unrelated JVMs on the machine, such as the language server of an IDE, and would block a rollback
# that is perfectly safe to perform.
$procs = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction SilentlyContinue |
  Where-Object { $_.CommandLine -match 'libs[\\/].*\.jar' })
if ($procs.Count -gt 0) { Stop1 ('el servidor parece estar corriendo (' + $procs.Count + ' proceso(s) del runtime). Detenlo antes de revertir.') }

# --- precheck 2: TODOS los backups existen con el hash correcto (antes de tocar nada)
$hashFile = Join-Path $runDir 'pre\file-hashes-pre.txt'
if (-not (Test-Path -LiteralPath $hashFile)) { Stop1 'falta pre\file-hashes-pre.txt' }
$bad = @(); $n = 0
foreach ($ln in (Get-Content -LiteralPath $hashFile)) {
  if ($ln -match '^([0-9A-F]{64})\s+(.+)$') {
    $n++
    $t = Join-Path $runDir $Matches[2]
    if (-not (Test-Path -LiteralPath $t)) { $bad += ($Matches[2] + ' (ausente)') }
    elseif ((Get-FileHash -LiteralPath $t -Algorithm SHA256).Hash -ne $Matches[1]) { $bad += ($Matches[2] + ' (hash distinto)') }
  }
}
if ($bad.Count -gt 0) { Say ('  backups con problema:'); $bad | ForEach-Object { Say ('    - ' + $_) }; Stop1 'faltan backups o no coinciden; no se toco nada' }
# v2.5 antibloqueo: el XML 10000-10099 se respalda (10 backups). Runs v2.4 tienen 9.
if (($n -ne $m.expected.installed_backups) -and ($n -ne 9)) { Stop1 ('se esperaban ' + $m.expected.installed_backups + ' backups (o 9 de v2.4), hay ' + $n) }
Say ('  precheck : ' + $n + '/' + $m.expected.installed_backups + ' backups OK, runtime detenido')

# --- plan
Say ''
Say 'PLAN:'
foreach ($rel in $dest.remove) { Say ('  borrar   <runtime>/' + $rel) }
foreach ($rel in $dest.restore) {
  $leaf = Split-Path $rel -Leaf
  if ($leaf -eq 'GameServer.jar') { $src = 'backup\libs\GameServer.jar' }
  elseif ($leaf -like '*.ini') { $src = 'backup\config\' + $leaf }
  else { $src = 'backup\datapack\' + $leaf }
  Say ('  restaurar <runtime>/' + $rel + '   <- ' + $src)
}
Say ''
if (-not $Force) {
  Say 'DRY-RUN: no se modifico nada. Re-ejecuta con -Force para aplicar.'
  Say 'RESULT: DRY-RUN'
  exit 0
}

# --- aplicar
foreach ($rel in $dest.remove) {
  $f = RPath $rel
  if (Test-Path -LiteralPath $f) { Remove-Item -LiteralPath $f -Force; Say ('  borrado    ' + $rel) }
  else { Say ('  (ya ausente) ' + $rel) }
}
foreach ($rel in $dest.restore) {
  $f = RPath $rel
  $leaf = Split-Path $rel -Leaf
  if ($leaf -eq 'GameServer.jar') { $src = Join-Path $runDir 'backup\libs\GameServer.jar' }
  elseif ($leaf -like '*.ini') { $src = Join-Path $runDir ('backup\config\' + $leaf) }
  else { $src = Join-Path $runDir ('backup\datapack\' + $leaf) }
  Copy-Item -LiteralPath $src -Destination $f -Force
  Say ('  restaurado ' + $rel)
}

# --- verificar restauracion
Say ''
$okF = $true
foreach ($rel in $dest.restore) {
  $f = RPath $rel
  $leaf = Split-Path $rel -Leaf
  if ($leaf -eq 'GameServer.jar') { $src = Join-Path $runDir 'backup\libs\GameServer.jar' }
  elseif ($leaf -like '*.ini') { $src = Join-Path $runDir ('backup\config\' + $leaf) }
  else { $src = Join-Path $runDir ('backup\datapack\' + $leaf) }
  if ((CanonicalHash $f) -ne (CanonicalHash $src)) { $okF = $false; Say ('  hash FAIL ' + $rel) }
}
$okD = $true
foreach ($rel in $dest.remove) { if (Test-Path -LiteralPath (RPath $rel)) { $okD = $false; Say ('  sigue presente ' + $rel) } }

# mapa completo de entries del JAR vs pre-estado
$jf = RPath $m.core_entry.jar
$zip = [System.IO.Compression.ZipFile]::OpenRead($jf)
$sha = [System.Security.Cryptography.SHA256]::Create()
$now = @{}
foreach ($e in $zip.Entries) { $s = $e.Open(); $ms = New-Object System.IO.MemoryStream; $s.CopyTo($ms); $s.Close(); $b = $ms.ToArray(); $ms.Dispose(); $now[$e.FullName] = [BitConverter]::ToString($sha.ComputeHash($b)).Replace('-','') }
$zip.Dispose()
$preMap = @{}
foreach ($l in (Get-Content -LiteralPath (Join-Path $runDir 'pre\jar-entries-pre.txt'))) {
  if ($l -match '^([0-9A-F]{64})\s+(\d+)\s+(.+)$') { $preMap[$Matches[3]] = $Matches[1] }
}
$okJ = ($now.Count -eq $preMap.Count)
foreach ($k in $preMap.Keys) { if (-not $now.ContainsKey($k)) { $okJ = $false } elseif ($now[$k] -ne $preMap[$k]) { $okJ = $false } }

Say ('  archivos restaurados : ' + $(if ($okF) { 'PASS' } else { 'FAIL' }))
Say ('  archivos nuevos borrados: ' + $(if ($okD) { 'PASS' } else { 'FAIL' }))
Say ('  mapa de entries del JAR : ' + $(if ($okJ) { 'PASS' } else { 'FAIL' }) + '  (' + $now.Count + ' entries)')
Say ''
if ($okF -and $okD -and $okJ) {
  # Un rollback completado deja el runtime en su estado previo, pero el run folder sigue
  # teniendo pre\destinos.json: install.ps1 lo interpretaria como PENDING-ROLLBACK y se
  # negaria a crear un backup nuevo, bloqueando toda instalacion posterior. Se marca la
  # corrida como revertida renombrando la carpeta: los backups, los hashes previos y los
  # logs se conservan intactos (nada se borra), y la corrida deja de clasificarse como
  # pendiente porque el marcador pasa a ser 'reverted-<run>'.
  $revertedName = 'reverted-' + (Split-Path $runDir -Leaf)
  $revertedDir = Join-Path $runsRoot $revertedName
  if (Test-Path -LiteralPath $revertedDir) { $revertedDir = $revertedDir + '-' + (Get-Date -Format 'HHmmss') }
  try {
    Rename-Item -LiteralPath $runDir -NewName (Split-Path $revertedDir -Leaf) -ErrorAction Stop
    # Marcador explicito: install.ps1 lo usa (ademas del prefijo del nombre) para no
    # clasificar esta corrida como PENDING-ROLLBACK.
    [System.IO.File]::WriteAllText((Join-Path $revertedDir 'reverted.txt'), ('reverted at ' + (Get-Date).ToString('o')))
    Say ('  corrida marcada como revertida: ' + (Split-Path $revertedDir -Leaf))
    Say '  (los backups y logs de esa corrida se conservan dentro de la carpeta renombrada)'
  } catch {
    Say ('  AVISO: no se pudo marcar la corrida como revertida: ' + $_.Exception.Message)
    Say '  La restauracion SI se aplico; para permitir otra instalacion, mueve o renombra'
    Say ('  manualmente ' + $runDir + ' fuera de _botai-runs.')
  }
  Say ''
  Say 'RESULT: PASS'
  exit 0
} else {
  Say 'RESULT: FAIL'
  exit 1
}
