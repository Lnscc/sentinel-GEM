param(
    [string]$BaselineRef = '7c577e26f72aaf826ad815328039641a531247d9',
    [string]$Image = 'sentinel-gem-engine:gem116-check'
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$engine = Join-Path $repo 'engine'
Push-Location $engine
try {
    $output = 'target/gem127-benchmark'
    New-Item -ItemType Directory -Force -Path "$output/lib", "$output/reference-src", "$output/reference-classes" | Out-Null
    & ./mvnw.cmd -q '-Dtest=StoryIncrementalPairSearchEquivalenceTests' test
    if ($LASTEXITCODE -ne 0) { throw 'Equivalence tests failed' }
    $xml = [xml](Get-Content -Raw 'target/surefire-reports/TEST-com.example.globalnewsenginev1.stories.snapshot.StoryIncrementalPairSearchEquivalenceTests.xml')
    $classPath = ($xml.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
    foreach ($entry in $classPath.Split([IO.Path]::PathSeparator)) {
        if ($entry.EndsWith('.jar')) { Copy-Item -LiteralPath $entry -Destination "$output/lib" -Force }
    }
    foreach ($name in @('ExactCosine', 'StoryIncrementalPairSearch')) {
        $referencePath = $BaselineRef + ':engine/src/main/java/com/example/globalnewsenginev1/stories/snapshot/' + $name + '.java'
        $source = & git show $referencePath
        if ($LASTEXITCODE -ne 0) { throw "Cannot read baseline $referencePath" }
        $source | Set-Content -Encoding utf8 -LiteralPath "$output/reference-src/$name.java"
    }
    & javac --release 21 -proc:none -cp 'target/classes' -d "$output/reference-classes" "$output/reference-src/ExactCosine.java" "$output/reference-src/StoryIncrementalPairSearch.java"
    if ($LASTEXITCODE -ne 0) { throw 'Baseline compilation failed' }
    @'
package com.example.globalnewsenginev1.stories.snapshot;
public class Gem127BenchmarkMain {
    public static void main(String[] args) throws Exception {
        new StoryExactComparisonBenchmarkTests().measuresDenseExactIncrementalComparison();
    }
}
'@ | Set-Content -Encoding utf8 -LiteralPath "$output/Gem127BenchmarkMain.java"
    $compilePath = 'target/test-classes;target/classes;' + $output + '/lib/*'
    & javac --release 21 -proc:none -cp $compilePath -d 'target/test-classes' "$output/Gem127BenchmarkMain.java"
    if ($LASTEXITCODE -ne 0) { throw 'Runner compilation failed' }
    foreach ($variant in @('reference', 'batched')) {
        $runtimePath = '/work/target/test-classes:/work/target/classes:/work/' + $output + '/lib/*'
        if ($variant -eq 'reference') { $runtimePath = '/work/' + $output + '/reference-classes:' + $runtimePath }
        $source = if ($variant -eq 'reference') { $BaselineRef } else { (& git rev-parse HEAD).Trim() + '+working-diff' }
        & docker run --rm --name "gem127-$variant" --network none --cpus 1 --memory 2g --memory-swap 2g `
            --mount "type=bind,source=$engine,target=/work" -w /work --entrypoint java $Image `
            -Xms256m -Xmx1536m -XX:ActiveProcessorCount=1 `
            '-Dstory.exact.benchmark=true' "-Dstory.exact.variant=linux-$variant" "-Dstory.exact.source=$source" `
            "-Dstory.exact.output=$output/linux-$variant.json" -cp $runtimePath `
            com.example.globalnewsenginev1.stories.snapshot.Gem127BenchmarkMain
        if ($LASTEXITCODE -ne 0) { throw "Benchmark failed: $variant" }
    }
    $before = Get-Content -Raw "$output/linux-reference.json" | ConvertFrom-Json
    $after = Get-Content -Raw "$output/linux-batched.json" | ConvertFrom-Json
    if ($before.inputHash -ne $after.inputHash) { throw 'Frozen input mismatch' }
    for ($i = 0; $i -lt $before.runs.Count; $i++) {
        foreach ($field in @('digest', 'candidates', 'candidateDimensionProducts', 'components', 'decisions')) {
            if ($before.runs[$i].$field -ne $after.runs[$i].$field) { throw "Semantic/work mismatch: $field" }
        }
    }
    if ($before.noop.digest -ne $after.noop.digest) { throw 'No-op mismatch' }
    Write-Output 'Frozen input, semantic result, candidate and arithmetic work digests match.'
} finally {
    Pop-Location
}
