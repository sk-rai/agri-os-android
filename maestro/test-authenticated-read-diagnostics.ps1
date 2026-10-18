$ErrorActionPreference = 'Stop'
$root = Join-Path ([IO.Path]::GetTempPath()) ('agrios-diagnostic-unit-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $root
$inputPath = Join-Path $root 'synthetic.log'
$outputPath = Join-Path $root 'summary.json'
$sentinel = 'synthetic-private-token-header-message'
$valid = '{"route":"land_context","http_status":null,"schema":null,"elapsed_ms":19,"failure_category":"OTHER_IO","response_received":false,"schema_valid":null,"exception_codes":["IO","EOF"],"transport_phase":"READING","connection_reused":true}'
$malicious = '{"route":"' + $sentinel + '","elapsed_ms":1,"response_received":false}'
$badCode = $valid.Replace('"EOF"', ('"' + $sentinel + '"'))
$badPhase = $valid.Replace('"READING"', ('"' + $sentinel + '"'))
$badReuse = $valid.Replace('"connection_reused":true', ('"connection_reused":"' + $sentinel + '"'))
[IO.File]::WriteAllLines($inputPath, @(('I AuthenticatedRead: ' + $valid), ('I AuthenticatedRead: ' + $malicious),
    ('I AuthenticatedRead: ' + $badCode), ('I AuthenticatedRead: ' + $badPhase),
    ('I AuthenticatedRead: ' + $badReuse), ('I OtherTag: ' + $sentinel)))
& (Join-Path $PSScriptRoot 'export-authenticated-read-diagnostics.ps1') -LogPath $inputPath -OutputPath $outputPath
$text = [IO.File]::ReadAllText($outputPath)
$report = $text | ConvertFrom-Json
if (@($report.records).Count -ne 1 -or $text.Contains($sentinel)) { throw 'Sanitized exporter regression' }
if ($report.records[0].failure_category -ne 'OTHER_IO' -or $report.records[0].exception_codes[1] -ne 'EOF' -or
    $report.records[0].transport_phase -ne 'READING' -or $report.records[0].connection_reused -ne $true) { throw 'Transport evidence lost' }
Write-Host 'Offline synthetic exporter privacy test passed.'
Write-Host ('Synthetic-only artifacts: ' + $root)
