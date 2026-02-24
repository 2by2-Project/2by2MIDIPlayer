param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs
)

$ErrorActionPreference = "Stop"

$jbrPath = "C:\Program Files\Android\Android Studio\jbr"
if (-not (Test-Path $jbrPath)) {
    Write-Error "Android Studio JBR not found: $jbrPath"
}

$env:JAVA_HOME = $jbrPath
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:GRADLE_USER_HOME = Join-Path $PSScriptRoot ".gradle-user"

Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host "GRADLE_USER_HOME=$env:GRADLE_USER_HOME"

& (Join-Path $PSScriptRoot "gradlew.bat") @GradleArgs
exit $LASTEXITCODE
