$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$classes = Join-Path $projectRoot 'build/classes'
New-Item -ItemType Directory -Force $classes | Out-Null
$sources = Get-ChildItem @((Join-Path $projectRoot 'src'), (Join-Path $projectRoot 'test')) -Recurse -Filter '*.java' | ForEach-Object FullName
& javac --release 8 -encoding UTF-8 -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw '歌词核心编译失败' }
$report = & java -cp $classes watchmusic.lyrics.LyricsCoreTest
$report | Write-Output
if ($LASTEXITCODE -ne 0) { throw '歌词核心验证失败' }
$report | Set-Content -LiteralPath (Join-Path $projectRoot 'build/test-results.txt') -Encoding utf8
