$ConfigPath = Join-Path $PSScriptRoot 'config.json'
$config = Get-Content $ConfigPath -Raw | ConvertFrom-Json

$headers = @{
  Authorization = "Bearer $($config.gateway_token)"
}

Write-Host "== health =="
Invoke-RestMethod -Uri "$($config.gateway_url)$($config.health_path)" -Method Get -Headers $headers

Write-Host "`n== chat =="
$headers['Content-Type'] = 'application/json'
$body = @{ text = '请只回复：Windows 端测试正常。' } | ConvertTo-Json
Invoke-RestMethod -Uri "$($config.gateway_url)$($config.chat_path)" -Method Post -Headers $headers -Body $body
