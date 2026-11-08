param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$DocsDir = "",
    [string]$Token = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($DocsDir)) {
    $DocsDir = Join-Path $PSScriptRoot "..\knowledge-base\baituan-club-materials"
}

$resolvedDocsDir = Resolve-Path -LiteralPath $DocsDir
$manifestPath = Join-Path $resolvedDocsDir "manifest.csv"
if (-not (Test-Path -LiteralPath $manifestPath)) {
    throw "未找到资料清单：$manifestPath"
}

$curl = Get-Command curl.exe -ErrorAction SilentlyContinue
if (-not $curl) {
    throw "未找到 curl.exe。请安装 curl，或使用网页端逐份上传 knowledge-base\baituan-club-materials 下的 Markdown 文件。"
}

$rows = Import-Csv -LiteralPath $manifestPath
if (-not $rows -or $rows.Count -eq 0) {
    throw "资料清单为空：$manifestPath"
}

$uploadUrl = ($BaseUrl.TrimEnd("/")) + "/api/document/upload"
Write-Host "准备上传 $($rows.Count) 份 baituan 社团资料到 $uploadUrl"

foreach ($row in $rows) {
    $filePath = Join-Path $resolvedDocsDir $row.file
    if (-not (Test-Path -LiteralPath $filePath)) {
        throw "清单中的资料文件不存在：$filePath"
    }

    $arguments = @(
        "-sS",
        "-X", "POST",
        $uploadUrl,
        "-F", "file=@$filePath",
        "-F", "docType=$($row.docType)",
        "-F", "project=$($row.project)",
        "-F", "tags=$($row.tags)",
        "-F", "description=$($row.description)"
    )
    if (-not [string]::IsNullOrWhiteSpace($Token)) {
        $arguments += @("-H", "Authorization: Bearer $Token")
    }

    Write-Host "上传：$($row.file) [$($row.docType)]"
    & $curl.Source @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "上传失败：$($row.file)"
    }
    Write-Host ""
    Start-Sleep -Milliseconds 200
}

Write-Host "baituan 社团资料上传任务已全部提交。可在网页端文档状态区查看入库进度。"
