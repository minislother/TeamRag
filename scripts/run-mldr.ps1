param([ValidateSet('check', 'run')][string]$Mode = 'check')
$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '..')
try {
    python scripts/prepare-mldr.py
    if ($LASTEXITCODE -ne 0) { throw 'MLDR preparation failed' }
    mvn -q test-compile dependency:build-classpath '-Dmdep.outputFile=target/mldr-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Benchmark compilation failed' }
    $benchmarkClasspath = 'target/test-classes;target/classes;' + (Get-Content target/mldr-classpath.txt -Raw).Trim()
    # Only run mode calls paid APIs. check performs base-splitting diagnostics without models.
    java -Xmx4g '-Dfile.encoding=UTF-8' -cp $benchmarkClasspath com.team.rag.config.MldrBenchmark $Mode
    if ($LASTEXITCODE -ne 0) { throw 'MLDR incomplete; inspect target/mldr/run-status.json' }
} finally {
    Pop-Location
}
