param([switch]$DatabaseOnly)
$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$mysqlDataDir = Join-Path $projectRoot "data\mysql-local"
$mysqlExecutable = "C:\Program Files\MySQL\MySQL Server 9.7\bin\mysqld.exe"
# 仅启动本项目已经初始化的数据库，避免重新初始化覆盖持久化数据。
if (-not (Test-Path -LiteralPath (Join-Path $mysqlDataDir "auto.cnf"))) { throw "本地数据库未初始化，请先按README完成数据库配置。" }
$listening = Get-NetTCPConnection -LocalPort 3307 -State Listen -ErrorAction SilentlyContinue
if (-not $listening) {
    Start-Process -FilePath $mysqlExecutable -ArgumentList @("--no-defaults", "--standalone", '--basedir="C:/Program Files/MySQL/MySQL Server 9.7"', ('--datadir="' + $mysqlDataDir + '"'), "--port=3307", "--bind-address=127.0.0.1", "--mysqlx=0", "--console") -WindowStyle Hidden -RedirectStandardOutput (Join-Path $projectRoot "data/mysql-start.log") -RedirectStandardError (Join-Path $projectRoot "data/mysql-start-error.log") | Out-Null
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if (Get-NetTCPConnection -LocalPort 3307 -State Listen -ErrorAction SilentlyContinue) { break }
        Start-Sleep -Seconds 1
    }
    if (-not (Get-NetTCPConnection -LocalPort 3307 -State Listen -ErrorAction SilentlyContinue)) { throw "数据库启动失败，请查看 data/mysql-start-error.log。" }
}
Write-Host "项目本地MySQL已监听127.0.0.1:3307。"
if (-not $DatabaseOnly) {
    Push-Location $projectRoot
    try { & mvn spring-boot:run; if ($LASTEXITCODE -ne 0) { throw "应用启动失败。" } }
    finally { Pop-Location }
}
