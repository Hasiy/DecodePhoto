param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArguments
)

$originalTempValue = $env:TEMP
$originalTmpValue = $env:TMP
$localTempPath = Join-Path $PSScriptRoot 'build\gradle-local-tmp'

New-Item -ItemType Directory -Force -Path $localTempPath | Out-Null

try {
    $env:TEMP = $localTempPath
    $env:TMP = $localTempPath
    & (Join-Path $PSScriptRoot 'gradlew.bat') @GradleArguments
    exit $LASTEXITCODE
} finally {
    if ($null -eq $originalTempValue) {
        Remove-Item Env:TEMP -ErrorAction SilentlyContinue
    } else {
        $env:TEMP = $originalTempValue
    }
    if ($null -eq $originalTmpValue) {
        Remove-Item Env:TMP -ErrorAction SilentlyContinue
    } else {
        $env:TMP = $originalTmpValue
    }
}
