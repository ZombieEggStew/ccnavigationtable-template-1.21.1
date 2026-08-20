# MkDocs 预览服务器启动脚本
# 双击运行或在终端执行: .\serve.ps1

Set-Location $PSScriptRoot
python -m mkdocs serve

# 按任意键关闭
Read-Host "按 Enter 键关闭"
