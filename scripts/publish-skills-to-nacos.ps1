<#
.SYNOPSIS
    把 src/main/resources/skills 下的 5 份提示词规则发布到 Nacos 配置中心。

.DESCRIPTION
    发布之后，线上改提示词只需要在 Nacos 控制台编辑并点"发布"，应用会自动热更新，
    不需要重新编译、打包、发布 jar。

    默认行为（安全优先）：
        如果 Nacos 上已经存在同名 dataId，脚本会【跳过】而不是覆盖。
        原因：本地文件是"出厂版本"，线上的版本可能已经被业务方改过很多轮。
        新人 clone 仓库后无脑跑一次脚本，如果直接覆盖，就会把线上调好的规则冲掉。
        确实要拿本地覆盖线上时，显式加 -Force。

.PARAMETER NacosAddr
    Nacos 地址，默认 localhost:18848（本机 Docker 把 8848 映射到了 18848）。
    标准部署环境一般是 localhost:8848。

.PARAMETER Group
    分组，默认 INTERVIEW_SKILL。必须与 application.yml 里 interview.skill.nacos.group 一致。

.PARAMETER Prefix
    dataId 前缀，默认 interview-skill-。最终 dataId = 前缀 + 文件名（含 .md）。
    必须与 interview.skill.nacos.data-id-prefix 一致。

.PARAMETER Namespace
    命名空间 ID，留空表示 public。

.PARAMETER Force
    强制覆盖 Nacos 上已有的配置。

.EXAMPLE
    .\publish-skills-to-nacos.ps1

.EXAMPLE
    .\publish-skills-to-nacos.ps1 -NacosAddr localhost:8848 -Force
#>
param(
    [string]$NacosAddr = "localhost:18848",
    [string]$Group = "INTERVIEW_SKILL",
    [string]$Prefix = "interview-skill-",
    [string]$Namespace = "",
    [switch]$Force
)

$ErrorActionPreference = "Stop"

# 从脚本位置往上找 skills 目录，避免依赖调用者的当前工作目录。
$skillDir = Join-Path (Split-Path -Parent $PSScriptRoot) "src\main\resources\skills"
if (-not (Test-Path $skillDir)) {
    Write-Error "找不到技能目录：$skillDir"
    exit 1
}

$files = Get-ChildItem -Path $skillDir -Filter "*.md" | Sort-Object Name
if ($files.Count -eq 0) {
    Write-Error "技能目录里没有 .md 文件：$skillDir"
    exit 1
}

$configUrl = "http://$NacosAddr/nacos/v1/cs/configs"

Write-Host "Nacos  : $NacosAddr"
Write-Host "Group  : $Group"
Write-Host "Namespace: $(if ($Namespace) { $Namespace } else { 'public' })"
Write-Host "源目录 : $skillDir"
Write-Host "模式   : $(if ($Force) { '强制覆盖' } else { '仅新建（已存在则跳过）' })"
Write-Host ("-" * 72)

$published = 0
$skipped = 0
$failed = 0

foreach ($file in $files) {
    $dataId = "$Prefix$($file.BaseName).md"

    # 先探测是否已存在：Nacos 对不存在的 dataId 返回 404 + "config data not exist"。
    $probeArgs = @("-s", "-o", "/dev/null", "-w", "%{http_code}", "-G", $configUrl,
        "--data-urlencode", "dataId=$dataId",
        "--data-urlencode", "group=$Group")
    if ($Namespace) { $probeArgs += @("--data-urlencode", "tenant=$Namespace") }
    $httpCode = (& curl.exe @probeArgs).Trim()

    if ($httpCode -eq "200" -and -not $Force) {
        Write-Host "[跳过] $dataId  （Nacos 上已存在；确认要用本地版本覆盖请加 -Force）" -ForegroundColor Yellow
        $skipped++
        continue
    }

    # --data-urlencode "content@文件路径" 会读取文件全部内容并做 URL 编码，
    # 中文和 Markdown 里的 # * > 等特殊字符都不会破坏表单提交。
    $pushArgs = @("-s", "-X", "POST", $configUrl,
        "--data-urlencode", "dataId=$dataId",
        "--data-urlencode", "group=$Group",
        "--data-urlencode", "type=markdown",
        "--data-urlencode", "content@$($file.FullName)")
    if ($Namespace) { $pushArgs += @("--data-urlencode", "tenant=$Namespace") }

    $response = (& curl.exe @pushArgs).Trim()
    if ($response -eq "true") {
        Write-Host "[成功] $dataId  ($($file.Length) 字节)" -ForegroundColor Green
        $published++
    }
    else {
        Write-Host "[失败] $dataId  返回：$response" -ForegroundColor Red
        $failed++
    }
}

Write-Host ("-" * 72)
Write-Host "发布 $published 份 / 跳过 $skipped 份 / 失败 $failed 份"

if ($published -gt 0) {
    Write-Host ""
    Write-Host "已生效的规则可通过接口确认："
    Write-Host "  curl http://localhost:18080/api/skills"
    Write-Host "  curl http://localhost:18080/api/skills/remote/status"
}

if ($failed -gt 0) { exit 1 }
