<#
  install.ps1 - BOTAI-RECIPE
  Instala BotAI en un runtime L2J Mobius CT 2.6 High Five.
  Nunca arranca ni detiene el servidor. Nunca reconstruye el JAR.
  Uso: .\install.ps1 -Runtime "C:\ruta\al\runtime" [-Run <id>] [-Reinstall]

  Toolchain: JDK 27 / bytecode major 71 (calibrado contra la base real).
  Los hashes del payload y de las clases compiladas son canonicos: se calculan
  sobre los bytes con los finales de linea normalizados a LF, de modo que un
  checkout de Git con core.autocrlf distinto no los invalida.
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$Runtime,
  [string]$Run,
  [switch]$Reinstall,
  [switch]$Json
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$Here = $PSScriptRoot
$m = Get-Content -LiteralPath (Join-Path $Here 'MANIFEST.json') -Raw | ConvertFrom-Json
$Runtime = (Resolve-Path -LiteralPath $Runtime).Path

$script:log = New-Object System.Collections.Generic.List[string]
$script:stageWrites = $false
$script:runDirForHelp = '(sin corrida)'
function Say($x) { $script:log.Add([string]$x); if (-not $Json) { Write-Host $x } }

function RPath($p) {
  $q = ($p -replace '/', '\')
  if ($q -like '<runtime>*') { $q = $q.Substring(9) }
  return (Join-Path $Runtime $q.TrimStart('\'))
}

function Stop1($x) {
  Say ('STOP: ' + $x)
  Say ''
  Say 'PROBLEMA : instalacion abortada'
  Say ('CAUSA    : ' + $x)
  # El mensaje no puede prometer "nada fue escrito": las ETAPAS 2-7 si escriben (corrida,
  # backups, datapack y quiza el JAR). Solo las ETAPAS 0-1 abortan sin tocar el runtime.
  if ($script:stageWrites) {
    Say 'ACCION   : el runtime puede estar a medias. Ejecuta rollback.ps1 para volver al estado previo.'
    Say ('           corrida: ' + $script:runDirForHelp)
  } else {
    Say 'ACCION   : corrige la causa y vuelve a ejecutar install.ps1. Nada fue escrito.'
  }
  exit 1
}

# --- bytes canonicos: se eliminan los CR para que CRLF y LF den el mismo hash ---
function ToCanonical([byte[]]$bytes) {
  $out = New-Object System.Collections.Generic.List[byte] ($bytes.Length)
  foreach ($b in $bytes) { if ($b -ne 13) { $out.Add($b) } }
  return $out.ToArray()
}
function BytesOf([string]$path) { return [System.IO.File]::ReadAllBytes($path) }
function CanonicalHash([string]$path) {
  $sha = [System.Security.Cryptography.SHA256]::Create()
  $h = $sha.ComputeHash((ToCanonical (BytesOf $path)))
  $sha.Dispose()
  return [BitConverter]::ToString($h).Replace('-','')
}
function HasEolDrift([string]$path) {
  return ((BytesOf $path) -contains 13)
}
# Escribe SIEMPRE $dest con los bytes canonicos (LF) de $src. Devuelve $true si el destino
# contenia CR (para informar de la normalizacion).
# No se puede saltar la escritura cuando "no hay drift": el destino puede ser un fichero
# stock legitimo y distinto, en LF. Ese era el fallo de la version anterior, que solo
# reescribia si el destino tenia CR y por tanto dejaba intacto cualquier stock en LF
# (p.ej. skill/effects/Resurrection.java en este workspace) y abortaba en el hash post-copia.
function CopyCanonical([string]$src, [string]$dest) {
  $canon = ToCanonical (BytesOf $src)
  $hadDrift = $false
  if (Test-Path -LiteralPath $dest) { $hadDrift = ((BytesOf $dest) -contains 13) }
  [System.IO.File]::WriteAllBytes($dest, $canon)
  return $hadDrift
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

function JdkVersion($exe) {
  try {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $exe
    $psi.Arguments = '-version'
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $p = [System.Diagnostics.Process]::Start($psi)
    $so = $p.StandardOutput.ReadToEnd()
    $se = $p.StandardError.ReadToEnd()
    $p.WaitForExit()
    return (($so + ' ' + $se) -replace '\s+', ' ')
  } catch { return '' }
}

Say ('recipe  : ' + $m.recipe + '  v' + $m.version)
Say ('runtime : ' + $Runtime)
Say ('package : ' + $Here)
Say ''
Say '--- ETAPA 0: entorno ---'
Say ('  PowerShell : ' + $PSVersionTable.PSVersion.ToString() + '  (' + $PSVersionTable.PSEdition + ')')

# --- JDK 27, java y javac del mismo JDK
$needJdk = [string]$m.toolchain.jdk
$cands = @()
if ($env:JAVA_HOME) { $cands += (Join-Path $env:JAVA_HOME 'bin') }
$gc = Get-Command java -ErrorAction SilentlyContinue
if ($gc) { $cands += (Join-Path (Split-Path (Split-Path $gc.Source -Parent) -Parent) 'bin') }
$jdkBin = $null
foreach ($c in ($cands | Select-Object -Unique)) {
  $je = Join-Path $c 'java.exe'
  $ce = Join-Path $c 'javac.exe'
  if ((Test-Path -LiteralPath $je) -and (Test-Path -LiteralPath $ce)) {
    $v = JdkVersion $je
    if ($v -match ('version\s+"?' + $needJdk + '(\.|\s|")')) { $jdkBin = $c; break }
    Say ('  candidato  : ' + $c + ' -> ' + $v.Trim())
  }
}
if (-not $jdkBin) { Stop1 ('no se encontro un JDK ' + $needJdk + ' con java.exe y javac.exe. Revisa JAVA_HOME y el PATH.') }
$javaExe = Join-Path $jdkBin 'java.exe'
$javacExe = Join-Path $jdkBin 'javac.exe'
Say ('  JDK        : ' + $jdkBin)
Say ('  java       : ' + (JdkVersion $javaExe).Trim())
Say ('  javac      : ' + (JdkVersion $javacExe).Trim())
Say ('  mismo JDK  : OK')

foreach ($d in $m.runtime_layout.required_dirs) {
  if (-not (Test-Path -LiteralPath (RPath $d) -PathType Container)) { Stop1 ('falta el directorio requerido: <runtime>/' + $d) }
}
foreach ($f in $m.runtime_layout.required_files) {
  if (-not (Test-Path -LiteralPath (RPath $f) -PathType Leaf)) { Stop1 ('falta el archivo requerido: <runtime>/' + $f) }
}
$JarPath = RPath $m.core_entry.jar
Say '  layout     : OK'

$need = (Get-Item -LiteralPath $JarPath).Length * 2
$drive = (Get-Item -LiteralPath $Runtime).PSDrive
if ($drive.Free -lt $need) { Stop1 ('espacio insuficiente: hacen falta ~' + [math]::Round($need/1MB) + ' MB') }
Say ('  espacio    : OK (~' + [math]::Round($need/1MB) + ' MB)')

$probe = Join-Path $Runtime ('.botai_write_test_' + [Guid]::NewGuid().ToString('N') + '.tmp')
$canWrite = $true
try { [System.IO.File]::WriteAllBytes($probe, @(1)); [System.IO.File]::Delete($probe) } catch { $canWrite = $false }
if (-not $canWrite) { Stop1 'sin permiso de escritura en el runtime' }
Say '  permisos   : OK'

$procs = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction SilentlyContinue |
  Where-Object { $_.CommandLine -match 'libs[\\/].*\.jar' })
if ($procs.Count -gt 0) { Stop1 ('el servidor parece estar corriendo: ' + $procs.Count + ' proceso(s) del runtime. Detenlo antes de instalar.') }
Say '  detenido   : OK'

# --- ETAPA 1: clasificacion de estado
Say ''
Say '--- ETAPA 1: estado del runtime ---'
$jarNow = JarMap $JarPath
# Cada core_entry se compara contra SU propio expected_size y expected_class_sha256:
# la clase de referencia del toolchain no participa en la clasificacion (antes se usaba su
# tamano para la primera entry, lo que hacia inalcanzable el estado ALREADY-INSTALLED).
# El hash es el criterio fuerte: el AutoUseTaskManager.class stock mide 2913 bytes y la clase
# parcheada 3592, pero lo que decide es el sha256 declarado en el manifiesto.
$patchedCount = 0
foreach ($ce in $m.core_entries) {
  $entryNow = $jarNow.Map[$ce.entry]
  if (-not $entryNow) { Stop1 ('el JAR no contiene la entry ' + $ce.entry) }
  $sameSize = ($entryNow.Size -eq [int]$ce.expected_size)
  $sameHash = ($entryNow.Hash -eq $ce.expected_class_sha256)
  if ($sameSize -and $sameHash) { $patchedCount++ }
  elseif ($sameSize) { Say ('  sin parchear : ' + $ce.entry + ' (' + $entryNow.Size + ' bytes coinciden, pero el sha256 es el de la clase stock)') }
  else { Say ('  sin parchear : ' + $ce.entry + ' (' + $entryNow.Size + ' de ' + $ce.expected_size + ' bytes)') }
}
$jarPatched = ($patchedCount -eq $m.core_entries.Count)

$present = @(); $missing = @()
foreach ($p in $m.residue_paths) {
  if (Test-Path -LiteralPath (RPath $p)) { $present += $p } else { $missing += $p }
}
# v2.5 antibloqueo: los REPLACE (ej. 10000-10099.xml) existen en stock y nunca son
# residuo BotAI. Solo cuentan los COPY con hash del payload; un stock con hash
# distinto NO dispara PARTIAL, se respalda y se sobrescribe en ETAPA 2/3.
$cfgOk = $true
foreach ($c in $m.config) {
  $hits = @(Select-String -LiteralPath (RPath $c.file) -Pattern ('^\s*' + [regex]::Escape($c.key) + '\s*='))
  if (-not (($hits.Count -eq 1) -and ($hits[0].Line.Trim() -eq ($c.key + ' = ' + $c.value)))) { $cfgOk = $false }
}
$datapackOk = $true
foreach ($p in $m.payload) {
  if ($p.op -eq 'COMPILE') { continue }
  $f = RPath $p.destination
  if (-not (Test-Path -LiteralPath $f)) { $datapackOk = $false; continue }
  if ((CanonicalHash $f) -ne $p.sha256) { $datapackOk = $false }
}

$runsRoot = Join-Path $Runtime '_botai-runs'
# Una corrida solo bloquea si es una instalacion interrumpida: tiene destinos.json y NO
# esta marcada como revertida. rollback.ps1 renombra la carpeta a 'reverted-<run>' y deja
# un marcador reverted.txt; esas corridas siguen en disco (backups y logs intactos) pero
# ya no bloquean una instalacion nueva.
$pending = @(Get-ChildItem -LiteralPath $runsRoot -Directory -ErrorAction SilentlyContinue |
             Where-Object {
               (Test-Path -LiteralPath (Join-Path $_.FullName 'pre\destinos.json')) -and
               (-not $_.Name.StartsWith('reverted-')) -and
               (-not (Test-Path -LiteralPath (Join-Path $_.FullName 'reverted.txt')))
             })
$isInstalled = ($present.Count -eq $m.residue_paths.Count) -and $jarPatched -and $datapackOk -and $cfgOk
$isPartial   = (-not $isInstalled) -and (($present.Count -gt 0) -or $jarPatched)

Say ('  datapack presentes : ' + $present.Count + '/' + $m.residue_paths.Count)
Say ('  JAR parcheado      : ' + $jarPatched + '  (' + $patchedCount + ' de ' + $m.core_entries.Count + ' entries con tamano y sha256 esperados)')
Say ('  config aplicada     : ' + $cfgOk)

# PENDING-ROLLBACK se evalua ANTES que PARTIAL: una corrida interrumpida deja residuos Y
# una corrida con backup, y en ese caso la accion correcta es revertir, no solo informar de
# que el estado es parcial. Con el orden anterior (PARTIAL primero) esta rama era inalcanzable
# para cualquier fallo posterior a la ETAPA 3.
if ($pending.Count -gt 0) {
  Say ''
  Say ('  ESTADO: PENDING-ROLLBACK - existen ' + $pending.Count + ' corrida(s) con backup: ' + (($pending | ForEach-Object { $_.Name }) -join ', '))
  if ($present.Count -gt 0 -or $jarPatched) { Say '    (ademas, el runtime ya tiene cambios de esa corrida: la reversion los deshara)' }
  Stop1 'estado PENDING-ROLLBACK. No se creo un backup nuevo. Ejecuta rollback.ps1 primero.'
}

if ($isInstalled) {
  Say ''
  Say '  ESTADO: ALREADY-INSTALLED - BotAI ya esta instalado y correcto.'
  Say '  No se modifico nada. Usa verify.ps1 para revalidar o rollback.ps1 para revertir.'
  if (-not $Reinstall) { Say 'RESULT: ALREADY-INSTALLED'; exit 0 }
  Say '  -Reinstall: se procede a reinstalar.'
} elseif ($isPartial) {
  Say ''
  Say '  ESTADO: PARTIAL - el runtime esta modificado de forma incompleta:'
  if ($present.Count -gt 0 -and $present.Count -lt $m.residue_paths.Count) { Say ('    faltan datapack    : ' + ($missing -join ', ')) }
  elseif ($present.Count -gt 0) { Say ('    datapack presentes : ' + ($present -join ', ')) }
  if ($jarPatched -and -not $datapackOk) { Say '    el JAR esta parcheado pero el datapack esta incompleto' }
  if (-not $jarPatched -and $present.Count -gt 0) { Say '    el datapack esta instalado pero el JAR NO esta parcheado' }
  if (-not $cfgOk) { Say '    la configuracion requerida esta incompleta' }
  Stop1 'estado PARTIAL. No se sobreescribio nada. Usa rollback.ps1 para volver al estado previo.'
} else {
  Say ''
  Say '  ESTADO: CLEAN - runtime sin modificar. Se proceda a instalar.'
}

# --- ETAPA 2: backup
Say ''
Say '--- ETAPA 2: backup ---'
if (-not $Run) { $Run = 'run-' + (Get-Date -Format 'yyyyMMdd-HHmmss') }
$RunDir = Join-Path $runsRoot $Run
# A partir de aqui el instalador SI escribe: Stop1 ya no puede prometer "nada fue escrito".
$script:stageWrites = $true
$script:runDirForHelp = $RunDir
foreach ($d in @('backup\datapack', 'backup\config', 'backup\libs', 'pre', 'build', 'staged')) {
  New-Item -ItemType Directory -Force -Path (Join-Path $RunDir $d) | Out-Null
}
$preLines = New-Object System.Collections.Generic.List[string]
$bakIdx = 0
foreach ($rel in $m.backup.sources) {
  $bakIdx++
  $p = RPath $rel
  if (-not (Test-Path -LiteralPath $p -PathType Leaf)) { Stop1 ('destino a respaldar no existe: <runtime>/' + $rel) }
  $leaf = Split-Path $rel -Leaf
  if ($leaf -eq 'GameServer.jar') { $tgt = Join-Path $RunDir 'backup\libs\GameServer.jar' }
  elseif ($leaf -like '*.ini') { $tgt = Join-Path $RunDir ('backup\config\' + $leaf) }
  else { $tgt = Join-Path $RunDir ('backup\datapack\' + $leaf) }
  Copy-Item -LiteralPath $p -Destination $tgt -Force
  $h = (Get-FileHash -LiteralPath $tgt -Algorithm SHA256).Hash
  $preLines.Add($h + '  ' + $tgt.Replace(($RunDir + '\'), ''))
  Say ('  [' + $bakIdx + '/' + $m.backup.sources.Count + '] ' + $rel + '  ' + $h.Substring(0,16))
}
[System.IO.File]::WriteAllLines((Join-Path $RunDir 'pre\file-hashes-pre.txt'), $preLines)

$dest = [pscustomobject]@{
  run = $Run
  created = (Get-Date).ToString('o')
  restore = @($m.backup.sources)
  remove  = @($m.backup.absent_on_clean_runtime)
}
[System.IO.File]::WriteAllText((Join-Path $RunDir 'pre\destinos.json'), ($dest | ConvertTo-Json -Depth 4))

$preVals = New-Object System.Collections.Generic.List[string]
foreach ($c in $m.config) {
  $hits = @(Select-String -LiteralPath (RPath $c.file) -Pattern ('^\s*' + [regex]::Escape($c.key) + '\s*='))
  if ($hits.Count -ne 1) { Stop1 ('clave de config no unica: ' + $c.key + ' en ' + $c.file) }
  $preVals.Add($c.file + ' | ' + $hits[0].Line.Trim())
  Say ('  config original : ' + $c.file + ' | ' + $hits[0].Line.Trim())
}
[System.IO.File]::WriteAllLines((Join-Path $RunDir 'pre\values-pre.txt'), $preVals)

$pre = JarMap $JarPath
$jl = New-Object System.Collections.Generic.List[string]
foreach ($k in $pre.Map.Keys) { $jl.Add($pre.Map[$k].Hash + '  ' + ([string]$pre.Map[$k].Size).PadLeft(10) + '  ' + $k) }
[System.IO.File]::WriteAllLines((Join-Path $RunDir 'pre\jar-entries-pre.txt'), $jl)
Say ('  pre-estado JAR  : ' + $pre.Count + ' entries; ' + $m.core_entries.Count + ' entries objetivo sin parchear: ' + ($m.core_entries.Count - $patchedCount))
Say ('  run folder      : ' + $RunDir)

# --- ETAPA 3: copiar datapack
Say ''
Say '--- ETAPA 3: payload datapack ---'
foreach ($p in $m.payload) {
  if ($p.op -eq 'COMPILE') { continue }
  $src = Join-Path $Here ($p.source -replace '/', '\')
  if (-not (Test-Path -LiteralPath $src)) { Stop1 ('falta el payload en el paquete: ' + $p.source) }
  if ((CanonicalHash $src) -ne $p.sha256) { Stop1 ('hash del payload no coincide: ' + $p.source) }
  $dst = RPath $p.destination
  New-Item -ItemType Directory -Force -Path (Split-Path $dst -Parent) | Out-Null
  # Se escriben SIEMPRE los bytes canonicos (LF): el hash del manifiesto es canonico y asi
  # el destino queda byte-identico aunque el paquete venga con CRLF, LF o EOL mezclado.
  $hadDrift = CopyCanonical $src $dst
  $eolNote = ''
  if ($hadDrift) { $eolNote = '  [reescrito a LF canonico]' }
  if ((CanonicalHash $dst) -ne $p.sha256) { Stop1 ('hash post-copia no coincide: ' + $p.destination) }
  Say ('  ' + $p.op.PadRight(7) + ' ' + $p.destination + $eolNote)
}

# --- ETAPA 4: compilar el core (multi-entry)
Say ''
Say '--- ETAPA 4: compilar el core ---'
$compileEntries = @()
foreach ($p in $m.payload) { if ($p.op -eq 'COMPILE') { $compileEntries += $p } }
# Un unico source puede producir varias classes (la externa y sus internas), asi que la cantidad de
# entradas COMPILE no tiene que igualar la de core_entries: lo que se valida es que CADA core_entry
# encuentre su source, y eso ya lo hace el Stop1 del bucle de abajo.
if ($compileEntries.Count -eq 0) { Stop1 'no hay ninguna entrada COMPILE en el payload' }
$outDir = Join-Path $RunDir 'build'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$builtHashes = @{}
foreach ($ce in $m.core_entries) {
  $leaf = ((Split-Path $ce.entry -Leaf) -replace '\.class$', '.java')
  # La clase interna $AutoUse se compila desde el MISMO source que su clase externa.
  $stem = ($leaf -replace '\$.*\.java$', '.java')
  $cp = $compileEntries | Where-Object { (Split-Path $_.source -Leaf) -eq $stem } | Select-Object -First 1
  if (-not $cp) { $cp = $compileEntries | Where-Object { (Split-Path $_.source -Leaf) -eq $leaf } | Select-Object -First 1 }
  if (-not $cp) { Stop1 ('no hay payload COMPILE para ' + $ce.entry + ' (source esperado: ' + $stem + ')') }
  $srcJava = Join-Path $Here ($cp.source -replace '/', '\')
  if ((CanonicalHash $srcJava) -ne $cp.sha256) { Stop1 ('hash del source no coincide: ' + $cp.source) }
  Say ('  compilando ' + $ce.entry)
  # El source del core puede importar clases del datapack (por ejemplo el registro de modo FARM/SUPPORT),
  # asi que el datapack ya instalado se ofrece como sourcepath adicional y no solo como classpath.
  $jout = & $javacExe -g -encoding UTF-8 -cp $JarPath -sourcepath (Join-Path $Runtime 'game\data\scripts') -d $outDir $srcJava 2>&1
  $jcode = $LASTEXITCODE
  foreach ($l in @($jout)) { Say ('    javac> ' + $l) }
  if ($jcode -ne 0) { Stop1 ('javac fallo con codigo ' + $jcode) }
  $built = Join-Path $outDir ($ce.entry -replace '/', '\')
  if (-not (Test-Path -LiteralPath $built)) { Stop1 ('no se genero la clase: ' + $ce.entry) }
  $bBytes = [System.IO.File]::ReadAllBytes($built)
  $bHash = (Get-FileHash -LiteralPath $built -Algorithm SHA256).Hash
  $bMajor = $bBytes[6] * 256 + $bBytes[7]
  $bStr = [System.Text.Encoding]::GetEncoding(28591).GetString($bBytes)
  Say ('    compilado : ' + $bBytes.Length + ' bytes  major=' + $bMajor + '  ' + $bHash.Substring(0,16))
  if ($bBytes.Length -ne [int]$ce.expected_size) { Stop1 ('tamano de ' + $ce.entry + ' es ' + $bBytes.Length + ', se esperaba ' + $ce.expected_size) }
  if ($bMajor -ne [int]$ce.class_major) { Stop1 ('la clase es major ' + $bMajor + ', se esperaba ' + $ce.class_major) }
  if ($bHash -ne $ce.expected_class_sha256) { Stop1 ('el hash de ' + $ce.entry + ' no coincide con el declarado en el manifiesto') }
  if (-not $bStr.Contains($ce.required_class_marker)) { Stop1 ('la clase no referencia ' + $ce.required_class_marker) }
  if ($ce.forbidden_class_marker -and $bStr.Contains($ce.forbidden_class_marker)) { Stop1 ('la clase todavia referencia ' + $ce.forbidden_class_marker) }
  $builtHashes[$ce.entry] = $bHash
  Say '    gate      : OK (tamano + major + sha256 + marcadores)'
}

# --- ETAPA 5: JAR staging
Say ''
Say '--- ETAPA 5: JAR staging ---'
$staged = Join-Path $RunDir 'staged\GameServer.jar'
Copy-Item -LiteralPath $JarPath -Destination $staged -Force
$zip = [System.IO.Compression.ZipFile]::Open($staged, [System.IO.Compression.ZipArchiveMode]::Update)
foreach ($ce in $m.core_entries) {
  $e = $zip.GetEntry($ce.entry)
  if ($null -eq $e) { $zip.Dispose(); Stop1 ('entry objetivo ausente: ' + $ce.entry) }
  $e.Delete()
  $ne = $zip.CreateEntry($ce.entry, [System.IO.Compression.CompressionLevel]::Optimal)
  $bytes = [System.IO.File]::ReadAllBytes((Join-Path $outDir ($ce.entry -replace '/', '\')))
  $stw = $ne.Open(); $stw.Write($bytes, 0, $bytes.Length); $stw.Close()
}
$zip.Dispose()
$post = JarMap $staged
$changed = @(); $added = @(); $removed = @()
foreach ($k in $post.Map.Keys) { if (-not $pre.Map.ContainsKey($k)) { $added += $k } elseif ($pre.Map[$k].Hash -ne $post.Map[$k].Hash) { $changed += $k } }
foreach ($k in $pre.Map.Keys) { if (-not $post.Map.ContainsKey($k)) { $removed += $k } }
Say ('  entries : ' + $post.Count + ' (pre ' + $pre.Count + ')  changed=' + $changed.Count + ' added=' + $added.Count + ' removed=' + $removed.Count)
if ($post.Count -ne $pre.Count) { Stop1 'el numero de entries cambio en el JAR staged' }
if ($added.Count -ne 0) { Stop1 'se anadieron entries al JAR staged' }
if ($removed.Count -ne 0) { Stop1 'se eliminaron entries del JAR staged' }
# Una entry ya parcheada con el mismo contenido no cambia de hash y no cuenta como "changed".
$expectedChanged = @($m.core_entries | Where-Object { $changed -contains $_.entry })
foreach ($ce in $expectedChanged) {
  if ($post.Map[$ce.entry].Hash -ne $builtHashes[$ce.entry]) { Stop1 ('la entry staged no coincide con la clase compilada: ' + $ce.entry) }
  $sh = $ce.must_not_modify
  if (-not $pre.Map.ContainsKey($sh) -or $pre.Map[$sh].Hash -ne $post.Map[$sh].Hash) { Stop1 ($sh + ' fue alterada y no debia') }
  Say ('  OK : ' + $ce.entry)
}
foreach ($ce in $m.core_entries) {
  if ($changed -notcontains $ce.entry) {
    if ($pre.Map[$ce.entry].Hash -ne $builtHashes[$ce.entry]) { Stop1 ('la entry ' + $ce.entry + ' sigue sin el parche compilado') }
    Say ('  OK (ya aplicado) : ' + $ce.entry)
  }
}
if ($changed.Count -gt $m.core_entries.Count) { Stop1 ('el JAR staged cambio ' + $changed.Count + ' entries, mas de las ' + $m.core_entries.Count + ' objetivo') }
$unexpected = @($changed | Where-Object { $m.core_entries.entry -notcontains $_ })
if ($unexpected.Count -gt 0) { Stop1 ('el JAR staged cambio entries no objetivo: ' + ($unexpected -join ', ')) }
Say ('  gate       : OK (' + $changed.Count + ' cambiadas de ' + $m.core_entries.Count + ' objetivo, 0 anadidas, 0 eliminadas)')

# --- ETAPA 6: instalar JAR
Say ''
Say '--- ETAPA 6: instalar JAR ---'
Copy-Item -LiteralPath $staged -Destination $JarPath -Force
Say ('  instalado : ' + (Get-FileHash -LiteralPath $JarPath -Algorithm SHA256).Hash.Substring(0,16))

# --- ETAPA 7: configuracion
Say ''
Say '--- ETAPA 7: configuracion ---'
$lat = [System.Text.Encoding]::GetEncoding(28591)
foreach ($c in $m.config) {
  $f = RPath $c.file
  if (-not (Test-Path -LiteralPath $f)) { Stop1 ('falta el fichero de configuracion: ' + $c.file) }
  $lines = [System.IO.File]::ReadAllLines($f, $lat)
  $want = $c.key + ' = ' + $c.value
  # Se localiza la linea EXACTA por indice (no por reemplazo de texto): sustituir la primera
  # aparicion del texto de la linea en todo el fichero podia corromper un comentario anterior
  # que contuviera la misma cadena. Solo se reescribe esa linea; el resto queda intacto.
  $idx = -1
  for ($i = 0; $i -lt $lines.Length; $i++) {
    if ($lines[$i] -match ('^\s*' + [regex]::Escape($c.key) + '\s*=')) {
      if ($idx -ge 0) { Stop1 ('clave de config no unica: ' + $c.key + ' en ' + $c.file) }
      $idx = $i
    }
  }
  if ($idx -lt 0) { Stop1 ('clave de config ausente: ' + $c.key + ' en ' + $c.file) }
  if ($lines[$idx].Trim() -eq $want) { Say ('  ya correcto : ' + $want + '  (' + (Split-Path $f -Leaf) + ')'); continue }
  $lines[$idx] = $want
  [System.IO.File]::WriteAllLines($f, $lines, $lat)
  $rb = @(Select-String -LiteralPath $f -Pattern ('^\s*' + [regex]::Escape($c.key) + '\s*='))
  if (($rb | Select-Object -First 1).Line.Trim() -ne $want) { Stop1 ('no se pudo aplicar: ' + $want) }
  Say ('  aplicado : ' + $want + '  (' + (Split-Path $f -Leaf) + ':' + ($idx + 1) + ')')
}

[System.IO.File]::WriteAllLines((Join-Path $RunDir 'log-install.txt'), $script:log)

# --- ETAPA 8: verify
Say ''
Say '--- ETAPA 8: verificacion ---'
& (Join-Path $Here 'verify.ps1') -Runtime $Runtime -Run $Run
$vcode = $LASTEXITCODE
Say ''
Say ('backup  : ' + (Join-Path $RunDir 'backup'))
Say ('log     : ' + (Join-Path $RunDir 'log-install.txt'))
Say ('rollback: .\rollback.ps1 -Runtime "' + $Runtime + '" -Run ' + $Run + ' -Force')
Say ''
# verify.ps1 devuelve 0 = PASS, 1 = FAIL y 2 = PASS_WITH_SKIP (hay controles sin evidencia,
# tipicamente C3/C5 por falta de pre-estado). Los tres casos se distinguen aqui: tratar el 2
# como FAIL marcaba como fallida una instalacion correcta.
if ($vcode -eq 0) {
  Say 'RESULT: PASS'
  exit 0
} elseif ($vcode -eq 2) {
  Say 'RESULT: PASS_WITH_SKIP - instalacion aplicada y verificada, pero verify.ps1 dejo controles sin evidencia (revisa los SKIP de arriba).'
  exit 0
} else {
  Say ('RESULT: FAIL - verify.ps1 devolvio ' + $vcode + '. Revisa los FAIL de arriba; el rollback esta preparado.')
  exit 1
}
