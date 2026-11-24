param(
    [Parameter(Mandatory)][uri]$BaseUrl,
    [Parameter(Mandatory)][string]$OutputPath,
    [ValidateRange(1, 86400)][int]$DurationSeconds = 1800,
    [ValidateRange(1, 60)][int]$IntervalSeconds = 1,
    [ValidateRange(1, 60)][int]$RequestTimeoutSeconds = 5
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds($RequestTimeoutSeconds)
$samples = [System.Collections.Generic.List[object]]::new()
$elapsed = [System.Diagnostics.Stopwatch]::StartNew()
try {
    while ($elapsed.Elapsed.TotalSeconds -lt $DurationSeconds) {
        foreach ($path in @('articles?offset=0&limit=20', 'stories?offset=0&limit=20&sort=time&direction=desc')) {
            $request = [System.Diagnostics.Stopwatch]::StartNew()
            $status = 0
            $errorMessage = $null
            $response = $null
            try {
                $response = $client.GetAsync($BaseUrl.AbsoluteUri.TrimEnd('/') + '/' + $path).GetAwaiter().GetResult()
                $status = [int]$response.StatusCode
            } catch {
                $errorMessage = $_.Exception.GetBaseException().Message
            } finally {
                if ($null -ne $response) { $response.Dispose() }
            }
            $samples.Add([pscustomobject]@{
                At = [DateTimeOffset]::UtcNow.ToString('o')
                Endpoint = $path.Split('?')[0]
                Milliseconds = $request.Elapsed.TotalMilliseconds
                Status = $status
                Error = $errorMessage
            })
        }
        Start-Sleep -Seconds $IntervalSeconds
    }
} finally {
    $client.Dispose()
    $samples | Export-Csv -LiteralPath $OutputPath -NoTypeInformation
}

foreach ($group in ($samples | Group-Object Endpoint)) {
    $values = @($group.Group | Where-Object Status -eq 200 | Sort-Object Milliseconds)
    [pscustomobject]@{
        Endpoint = $group.Name
        Requests = $group.Count
        Failures = @($group.Group | Where-Object Status -ne 200).Count
        P50Milliseconds = if ($values.Count) { $values[[int][Math]::Ceiling($values.Count * 0.50) - 1].Milliseconds } else { $null }
        P95Milliseconds = if ($values.Count) { $values[[int][Math]::Ceiling($values.Count * 0.95) - 1].Milliseconds } else { $null }
    }
}
