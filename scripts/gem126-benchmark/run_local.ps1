$ErrorActionPreference = 'Stop'
$gem126Root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$gem126Engine = Join-Path $gem126Root 'engine'
$gem126Output = Join-Path $gem126Engine 'target/gem126-benchmark'
New-Item -ItemType Directory -Path $gem126Output -Force | Out-Null
Push-Location $gem126Engine
try {
    foreach ($history in @(105000, 125000)) {
        foreach ($noop in @('false', 'true')) {
            foreach ($mode in @('reference', 'selective')) {
                $name = "$mode-$($history + 2000)-$(if ($noop -eq 'true') { 'noop' } else { 'changed' })"
                $log = Join-Path $gem126Output "$name.log"
                & ./mvnw.cmd -B '-Dtest=__none__' '-Dsurefire.failIfNoSpecifiedTests=false' `
                    '-Dit.test=StoryVectorWorksetPostgresIT' '-Dit.vector.scale=true' `
                    "-Dit.vector.mode=$mode" "-Dit.vector.history=$history" "-Dit.vector.noop=$noop" `
                    '-DargLine=-Xms256m -Xmx1536m' `
                    '-Dit.postgres.jdbc-url=jdbc:postgresql://127.0.0.1:55426/gne' `
                    '-Dit.postgres.username=gne' '-Dit.postgres.password=gne' verify *> $log
                if ($LASTEXITCODE -ne 0) {
                    Get-Content -LiteralPath $log -Tail 35
                    throw "Benchmark failed: $name"
                }
                $report = Get-Content -Raw -LiteralPath (Join-Path $gem126Output "$name.json") | ConvertFrom-Json
                Write-Output "$name vectors=$($report.loadedDecodedVectors) bytes=$($report.vectorPayloadBytes) loadMs=$($report.loadMs) heapPeakBytes=$($report.heapUsedPeakSampledBytes)"
            }
        }
    }
    foreach ($count in @(107000, 127000)) {
        foreach ($case in @('changed', 'noop')) {
            $before = Get-Content -Raw (Join-Path $gem126Output "reference-$count-$case.json") | ConvertFrom-Json
            $after = Get-Content -Raw (Join-Path $gem126Output "selective-$count-$case.json") | ConvertFrom-Json
            foreach ($field in @('snapshotInputHash', 'snapshotKey', 'semanticDigest', 'comparedPairs')) {
                if ($before.$field -ne $after.$field) { throw "Reference mismatch: $count $case $field" }
            }
        }
    }
    foreach ($case in @('changed', 'noop')) {
        $base = Get-Content -Raw (Join-Path $gem126Output "selective-107000-$case.json") | ConvertFrom-Json
        $extra = Get-Content -Raw (Join-Path $gem126Output "selective-127000-$case.json") | ConvertFrom-Json
        foreach ($field in @('loadedDecodedVectors', 'vectorPayloadBytes', 'semanticDigest', 'comparedPairs')) {
            if ($base.$field -ne $extra.$field) { throw "Unrelated history changed work: $case $field" }
        }
    }
    Write-Output 'All frozen-reference and unrelated-history comparisons passed.'
} finally {
    Pop-Location
}
