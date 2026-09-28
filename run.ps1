Set-Location $PSScriptRoot
& docker compose up --build
exit $LASTEXITCODE
