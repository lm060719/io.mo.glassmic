param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $SkipBuild) {
    Push-Location $repo
    try {
        & .\gradlew.bat :app:compileDebugKotlin :xposed:compileDebugKotlin --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Kotlin compilation failed.' }
    } finally { Pop-Location }
}
$gradleCache = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
$stdlibRoot = Join-Path $gradleCache 'caches\modules-2\files-2.1\org.jetbrains.kotlin\kotlin-stdlib'
$stdlib = Get-ChildItem -LiteralPath $stdlibRoot -Recurse -Filter 'kotlin-stdlib-*.jar' |
    Where-Object { $_.Name -notmatch '(sources|javadoc)' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $stdlib) { throw 'Build the project first to populate the Kotlin stdlib cache.' }
$out = Join-Path $repo 'build\audio-regression'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$classpath = @(
    (Join-Path $repo 'app\build\tmp\kotlin-classes\debug'),
    (Join-Path $repo 'xposed\build\tmp\kotlin-classes\debug'),
    (Join-Path $repo 'core\build\tmp\kotlin-classes\debug'),
    $stdlib.FullName, $out
) -join [IO.Path]::PathSeparator
& javac -encoding UTF-8 -cp $classpath -d $out (Join-Path $PSScriptRoot 'AudioRegression.java') (Join-Path $PSScriptRoot 'TapTimingRegression.java') (Join-Path $PSScriptRoot 'FolderIndexRegression.java')
if ($LASTEXITCODE -ne 0) { throw 'Audio regression compilation failed.' }
& java -cp $classpath AudioRegression
if ($LASTEXITCODE -ne 0) { throw 'Audio regressions failed.' }
& java -cp $classpath TapTimingRegression
if ($LASTEXITCODE -ne 0) { throw 'Tap timing regressions failed.' }
& java -cp $classpath FolderIndexRegression
if ($LASTEXITCODE -ne 0) { throw 'Folder index regressions failed.' }
