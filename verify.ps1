<#
  verify.ps1 - BOTAI-RECIPE
  READ-ONLY. No escribe dentro del runtime. No arranca el servidor.
  Uso: .\verify.ps1 -Runtime "C:\ruta\al\runtime" [-Run latest]

  Hashes canonicos: se calculan sobre los bytes con los finales de linea
  normalizados a LF, de modo que CRLF y LF producen el mismo resultado.
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$Runtime,
  [string]$Run,
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

# --- bytes canonicos: se eliminan los CR para que CRLF y LF den el mismo hash ---
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

function JarMap($jar) {
  $zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
  $sha = [System.Security.Cryptography.SHA256]::Create()
  $map = @{}; $n = 0
  foreach ($e in $zip.Entries) {
    $n++
    $s = $e.Open(); $ms = New-Object System.IO.MemoryStream; $s.CopyTo($ms); $s.Close()
    $b = $ms.ToArray(); $ms.Dispose()
    $map[$e.FullName] = [pscustomobject]@{ Hash = [BitConverter]::ToString($sha.ComputeHash($b)).Replace('-',''); Size = $b.Length }
  }
  $zip.Dispose()
  return [pscustomobject]@{ Map = $map; Count = $n }
}

$out = New-Object System.Collections.Generic.List[object]
function Check($id, $name, $ok, $note, $skip = $false) {
  $st = $(if ($skip) { 'SKIP' } elseif ($ok) { 'PASS' } else { 'FAIL' })
  $out.Add([pscustomobject]@{ id = $id; name = $name; status = $st; note = $note })
  if (-not $Json) { Write-Host ('  [' + $st + '] ' + $id + ' : ' + $name + '  -> ' + $note) }
}

Write-Host ''
Write-Host ('runtime : ' + $Runtime)
Write-Host ('package : ' + $Here)
Write-Host ('toolchain: JDK ' + $m.toolchain.jdk + ' / class major ' + $m.toolchain.class_major)
Write-Host ''

# ---- localizar la corrida y su pre-estado
$runDir = $null
$runsRoot = Join-Path $Runtime '_botai-runs'
if (-not $Run -or $Run -eq 'latest') {
  # 'latest' solo debe resolver la ultima corrida de instalacion real: las crea
  # install.ps1 y se identifican por log-install.txt o pre\jar-entries-pre.txt.
  # Los directorios temporales de compilacion, verificacion y diagnostico no son
  # corridas y no deben evaluarse como tales.
  $runs = @(Get-ChildItem -LiteralPath $runsRoot -Directory -ErrorAction SilentlyContinue |
    Where-Object { ((Test-Path -LiteralPath (Join-Path $_.FullName 'log-install.txt')) -or (Test-Path -LiteralPath (Join-Path $_.FullName 'pre\jar-entries-pre.txt'))) -and (-not $_.Name.StartsWith('reverted-')) -and (-not (Test-Path -LiteralPath (Join-Path $_.FullName 'reverted.txt'))) } |
    Sort-Object LastWriteTime)
  if ($runs.Count -gt 0) { $runDir = $runs[$runs.Count - 1].FullName }
} else {
  $rd = Join-Path $runsRoot $Run
  if (Test-Path -LiteralPath $rd) { $runDir = $rd }
}
$preMap = @{}
$hasPre = $false
$preCount = 0
if ($runDir) {
  $pf = Join-Path $runDir 'pre\jar-entries-pre.txt'
  if (Test-Path -LiteralPath $pf) {
    foreach ($ln in (Get-Content -LiteralPath $pf)) {
      $preCount++
      if ($ln -match '^([0-9A-F]{64})\s+(\d+)\s+(.+)$') { $preMap[$Matches[3]] = $Matches[1]; $hasPre = $true }
    }
  }
}

# ---- C1 datapack hashes
$n1 = 0; $ok1 = $true; $notes1 = @()
foreach ($p in $m.payload) {
  if ($p.op -eq 'COMPILE') { continue }
  $f = RPath $p.destination
  $n1++
  if (-not (Test-Path -LiteralPath $f)) { $ok1 = $false; $notes1 += ((Split-Path $p.destination -Leaf) + ':AUSENTE'); continue }
  if ((CanonicalHash $f) -ne $p.sha256) { $ok1 = $false; $notes1 += ((Split-Path $p.destination -Leaf) + ':HASH-DISTINTO') }
}
Check 'C1' 'datapack hashes en el runtime' $ok1 ("$n1 archivos comparados con MANIFEST.json" + $(if ($notes1.Count -gt 0) { ' | ' + ($notes1 -join ', ') } else { '' }))

# ---- C2 source de core parcheado: TODAS las entradas COMPILE, no solo la ultima
$c2items = @($m.payload | Where-Object { $_.op -eq 'COMPILE' })
$ok2 = $true; $notes2 = @()
foreach ($p in $c2items) {
  $csrc = Join-Path $Here ($p.source -replace '/', '\')
  $leaf = Split-Path $csrc -Leaf
  if (-not (Test-Path -LiteralPath $csrc)) { $ok2 = $false; $notes2 += ($leaf + ':AUSENTE'); continue }
  $txt = [System.IO.File]::ReadAllText($csrc)
  $hasReq = $txt.Contains($p.required_source_marker)
  $hasForb = $false
  if ($p.forbidden_source_marker) { $hasForb = $txt.Contains($p.forbidden_source_marker) }
  $srcOk = $hasReq -and (-not $hasForb)
  if (-not $srcOk) { $ok2 = $false }
  $notes2 += ($leaf + ':req=' + $hasReq + ',fbd=' + $hasForb)
}
Check 'C2' ('source de core parcheado (' + $c2items.Count + ' entradas COMPILE)') $ok2 ($notes2 -join ' | ')

# ---- C3 JAR: solo entries de core, 0 anadidas, 0 eliminadas
$jar = RPath $m.core_entry.jar
$post = JarMap $jar
$changed = @(); $added = @(); $removed = @()
if ($hasPre) {
  foreach ($k in $post.Map.Keys) {
    if (-not $preMap.ContainsKey($k)) { $added += $k } elseif ($preMap[$k] -ne $post.Map[$k].Hash) { $changed += $k }
  }
  foreach ($k in $preMap.Keys) { if (-not $post.Map.ContainsKey($k)) { $removed += $k } }
}
$entrySet = @($m.core_entries.entry)
$ok3 = ($added.Count -eq 0) -and ($removed.Count -eq 0)
foreach ($k in $changed) { if ($entrySet -notcontains $k) { $ok3 = $false } }
if ($changed.Count -gt $entrySet.Count) { $ok3 = $false }
# Sin pre-estado no hay nada que comparar: SKIP explicito, nunca PASS.
$skip3 = -not $hasPre
Check 'C3' 'JAR: solo entries de core, 0 anadidas, 0 eliminadas' $ok3 ("entries=$($post.Count) changed=$($changed.Count) added=$($added.Count) removed=$($removed.Count)" + $(if ($skip3) { ' | sin pre-estado: no comparable' } else { '' })) $skip3

# ---- C4 entries del core: tamano, major, sha256 y marcadores de bytecode
$zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
$ok4 = $true
$notes4 = @()
$sha4 = [System.Security.Cryptography.SHA256]::Create()
foreach ($ce in $m.core_entries) {
  $tb = $null
  $en = $zip.GetEntry($ce.entry)
  if ($null -ne $en) { $s2 = $en.Open(); $ms2 = New-Object System.IO.MemoryStream; $s2.CopyTo($ms2); $s2.Close(); $tb = $ms2.ToArray(); $ms2.Dispose() }
  if ($null -eq $tb) { $ok4 = $false; $notes4 += ((Split-Path $ce.entry -Leaf) + ':AUSENTE'); continue }
  $major = $tb[6] * 256 + $tb[7]
  $ts = [System.Text.Encoding]::GetEncoding(28591).GetString($tb)
  $hash = [BitConverter]::ToString($sha4.ComputeHash($tb)).Replace('-','')
  $okSize = ($tb.Length -eq [int]$ce.expected_size)
  $okMajor = ($major -eq [int]$ce.class_major)
  $okHash = ($hash -eq $ce.expected_class_sha256)
  $okMark = $ts.Contains($ce.required_class_marker)
  $okForb = $true
  if ($ce.forbidden_class_marker) { $okForb = -not $ts.Contains($ce.forbidden_class_marker) }
  if (-not ($okSize -and $okMajor -and $okHash -and $okMark -and $okForb)) { $ok4 = $false }
  # req = marcador requerido PRESENTE; noFbd = marcador prohibido AUSENTE (true es lo correcto)
  $notes4 += ((Split-Path $ce.entry -Leaf) + ":sz=$($tb.Length)/$($ce.expected_size),maj=$major,sha=$okHash,req=$okMark,noFbd=$okForb")
}
$sha4.Dispose()
$zip.Dispose()
$note4 = ($notes4 -join ' | ')
Check 'C4' 'entries del core: tamano + major + sha256 + bytecode' $ok4 $note4

# ---- C5 clases protegidas intactas
$ok5 = $true; $note5 = 'sin pre-estado: no verificable'
$skip5 = -not $hasPre
if ($hasPre) {
  $notes5 = @()
  foreach ($ce in $m.core_entries) {
    $sh = $ce.must_not_modify
    if (-not $preMap.ContainsKey($sh) -or -not $post.Map.ContainsKey($sh) -or $preMap[$sh] -ne $post.Map[$sh].Hash) { $ok5 = $false; $notes5 += ((Split-Path $sh -Leaf) + ':DIFIERE') }
    else { $notes5 += ((Split-Path $sh -Leaf) + ':intacta') }
  }
  $note5 = $notes5 -join ' | '
}
Check 'C5' 'clases protegidas del core intactas' $ok5 $note5 $skip5

# ---- C6 las configuraciones requeridas
$ok6 = $true; $note6 = ''
foreach ($c in $m.config) {
  $f = RPath $c.file
  if (-not (Test-Path -LiteralPath $f)) { $ok6 = $false; $note6 += (Split-Path $f -Leaf) + ':' + $c.key + '=MISSING '; continue }
  $hits = @(Select-String -LiteralPath $f -Pattern ('^\s*' + [regex]::Escape($c.key) + '\s*='))
  $applied = ($hits.Count -eq 1) -and ($hits[0].Line.Trim() -eq ($c.key + ' = ' + $c.value))
  if (-not $applied) { $ok6 = $false }
  $note6 += (Split-Path $f -Leaf) + ':' + $c.key + '=' + $(if ($applied) { 'OK' } else { 'FAIL' }) + ' '
}
Check 'C6' ('configuracion requerida (' + @($m.config).Count + ' valores)') $ok6 $note6

# ---- C7 sin tokens administrativos
# Excepcion acotada (v2.7): AdminHandoffTest y AdminSwapTest son handlers de admin
# por naturaleza (implementan IAdminCommandHandler) y fueron promovidos a payload
# de produccion porque el flujo Login del .bot consolidado los invoca
# (AdminBotManager.runSwap/runHandoff) y MasterHandler los registra. El token
# IAdminCommandHandler sigue prohibido para todos los demas archivos; la excepcion
# es por ruta exacta, no por nombre de token. Estos ficheros siguen cubiertos por
# C1 (hash), la compilacion y el backup/rollback.
$c7Exempt = @(
  'game/data/scripts/handlers/chat/commands/admin/AdminHandoffTest.java',
  'game/data/scripts/handlers/chat/commands/admin/AdminSwapTest.java'
)
$ok7 = $true
foreach ($p in $m.payload) {
  if ($p.op -eq 'COMPILE') { continue }
  if ($c7Exempt -contains $p.destination) { continue }
  $f = RPath $p.destination
  if (-not (Test-Path -LiteralPath $f)) { continue }
  foreach ($t in $m.expected.forbidden_tokens) {
    if (@(Select-String -LiteralPath $f -Pattern $t -SimpleMatch).Count -gt 0) { $ok7 = $false }
  }
}
Check 'C7' 'sin tokens administrativos en el payload' $ok7 'literal search en los datapack (AdminHandoffTest/AdminSwapTest exentos por ruta: handlers admin autorizados del flujo .bot)'

# ---- C8 backups integros
$ok8 = $true; $n8 = 0; $skip8 = $false
if (-not $runDir) {
  $skip8 = $true
} else {
  $bf = Join-Path $runDir 'pre\file-hashes-pre.txt'
  if (-not (Test-Path -LiteralPath $bf)) {
    # El run no tiene pre-estado de backups (p.ej. una instalacion manual): no hay
    # evidencia que verificar, asi que se omite en vez de marcarse FAIL o PASS.
    $skip8 = $true
  } else {
    foreach ($ln in (Get-Content -LiteralPath $bf)) {
      if ($ln -match '^([0-9A-F]{64})\s+(.+)$') {
        $n8++
        $t = Join-Path $runDir $Matches[2]
        if (-not (Test-Path -LiteralPath $t)) { $ok8 = $false; continue }
        if ((Get-FileHash -LiteralPath $t -Algorithm SHA256).Hash -ne $Matches[1]) { $ok8 = $false }
      }
    }
  }
}
Check 'C8' 'backups integros' $ok8 ("archivos=$n8 run=$(if($runDir){Split-Path $runDir -Leaf}else{'ninguno'})") $skip8

# ---- C9 rollback preparado
$ok9 = $false; $skip9 = $false
if (-not $runDir) {
  $skip9 = $true
} elseif (-not (Test-Path -LiteralPath (Join-Path $runDir 'pre\destinos.json'))) {
  # Sin destinos.json no se sabe que restauraria el rollback: evidencia ausente.
  $skip9 = $true
} else {
  $ok9 = (Test-Path -LiteralPath (Join-Path $runDir 'backup\libs\GameServer.jar'))
}
Check 'C9' 'rollback preparado' $ok9 'backup + registro de destinos' $skip9

$fails = @($out | Where-Object { $_.status -eq 'FAIL' })
$skips = @($out | Where-Object { $_.status -eq 'SKIP' })
$passes = @($out | Where-Object { $_.status -eq 'PASS' })
Write-Host ''
Write-Host ('SUMMARY: PASS=' + $passes.Count + '  FAIL=' + $fails.Count + '  SKIP=' + $skips.Count)
if ($Json) { $out | ConvertTo-Json -Depth 4 }
if ($fails.Count -gt 0) { Write-Host 'RESULT: FAIL'; exit 1 }
if ($skips.Count -gt 0) { Write-Host 'RESULT: PASS_WITH_SKIP (no es PASS completo: hay controles sin evidencia)'; exit 2 }
Write-Host 'RESULT: PASS'; exit 0
