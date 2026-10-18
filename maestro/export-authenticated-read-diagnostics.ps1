param(
    [Parameter(Mandatory = $true)][string]$LogPath,
    [Parameter(Mandatory = $true)][string]$OutputPath
)
$ErrorActionPreference = 'Stop'
# Offline extraction only. Never copy raw Logcat lines or parse other tags into the report.
$routes = @('profile','soil_profiles','land_context','land_summary','eligible_parcels','assigned_work','assigned_farmer')
$categories = @('CONNECT_TIMEOUT','READ_TIMEOUT','CONNECTION_REFUSED','CONNECTION_RESET','DNS','PROTOCOL','CANCELLED','OTHER_IO','UNEXPECTED')
$schemas = @('land_intelligence_context.v1','land_intelligence_summary.v1','field_agent_worklist.v1')
$phases = @('UNKNOWN','DNS','CONNECTING','TLS','CONNECTED','WRITING','READING')
$exceptionCodes = @('EOF','SOCKET_TIMEOUT','CONNECT','SOCKET','DNS','PROTOCOL','TLS','INTERRUPTED_IO','CANCELLED','INTERRUPTED','IO','UNKNOWN')
$records = @(
    foreach ($line in [IO.File]::ReadLines((Resolve-Path -LiteralPath $LogPath).Path)) {
        if ($line -notmatch '\bAuthenticatedRead\s*:\s*(\{.*\})\s*$') { continue }
        try { $item = $Matches[1] | ConvertFrom-Json } catch { continue }
        if ($item.route -notin $routes -or $item.elapsed_ms -isnot [ValueType] -or $item.elapsed_ms -lt 0) { continue }
        if ($item.response_received -isnot [bool]) { continue }
        if ($null -ne $item.http_status -and ($item.http_status -isnot [ValueType] -or $item.http_status -lt 100 -or $item.http_status -gt 599)) { continue }
        if ($item.response_received -ne ($null -ne $item.http_status)) { continue }
        if ($null -ne $item.failure_category -and $item.failure_category -notin $categories) { continue }
        if ($item.response_received -and $null -ne $item.failure_category) { continue }
        if (-not $item.response_received -and $item.failure_category -notin $categories) { continue }
        if ($null -ne $item.schema -and $item.schema -notin $schemas) { continue }
        if ($null -ne $item.schema_valid -and $item.schema_valid -isnot [bool]) { continue }
        # Old records remain readable, but new diagnostic values must be fixed enums/scalars.
        $phase = 'UNKNOWN'
        if ($null -ne $item.transport_phase) {
            if ($item.transport_phase -notin $phases) { continue }
            $phase = [string]$item.transport_phase
        }
        $codes = @()
        if ($null -ne $item.exception_codes) {
            if ($item.exception_codes -isnot [array] -or $item.exception_codes.Count -gt 12) { continue }
            if (@($item.exception_codes | Where-Object { $_ -isnot [string] -or $_ -notin $exceptionCodes }).Count -gt 0) { continue }
            $codes = @($item.exception_codes)
        }
        if ($null -ne $item.connection_reused -and $item.connection_reused -isnot [bool]) { continue }
        if ($item.response_received -and $codes.Count -gt 0) { continue }
        [ordered]@{ route = [string]$item.route; http_status = $item.http_status; schema = $item.schema;
            elapsed_ms = [long]$item.elapsed_ms; failure_category = $item.failure_category;
            response_received = $item.response_received; schema_valid = $item.schema_valid;
            exception_codes = @($codes); transport_phase = $phase; connection_reused = $item.connection_reused }
    }
)
$report = [ordered]@{ schema_version = 'android_authenticated_read_diagnostics.v1'; records = @($records) }
[IO.File]::WriteAllText([IO.Path]::GetFullPath($OutputPath), ($report | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
Write-Host ('Sanitized diagnostic records exported: ' + $records.Count)
