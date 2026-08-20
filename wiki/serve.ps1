# MkDocs 预览服务器启动脚本
# 双击运行或在终端执行: .\serve.ps1

Set-Location $PSScriptRoot

# 优先使用项目自带的虚拟环境（已包含 mkdocs、mkdocs-material、mkdocs-static-i18n）
if (Test-Path ".\.venv\Scripts\python.exe") {
    $python = ".\.venv\Scripts\python.exe"
} else {
    $python = "python"
    Write-Host "未找到 .\.venv，回退到系统 python。若缺少依赖请执行: python -m pip install -r requirements.txt"
}

& $python -m mkdocs serve

# 按任意键关闭
Read-Host "按 Enter 键关闭"
