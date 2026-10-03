param([switch]$Deploy)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    # Login uses the user's browser, never paste credentials into source/chat.
    # Prerequisites: Node 20+ and npm/npx, Firebase project deploy permissions/Blaze.
    & npx --yes firebase-tools@15.32.1 functions:list --project family-location-884e5
    if ($LASTEXITCODE -ne 0) { throw 'Inspection failed. Run: npx --yes firebase-tools@15.32.1 login, then rerun this script.' }
    if ($Deploy) {
        & npm --prefix functions ci
        if ($LASTEXITCODE -ne 0) { throw 'Dependency installation failed' }
        & node --test functions/wake-handler.test.js
        if ($LASTEXITCODE -ne 0) { throw 'Wake handler tests failed' }
        & npx --yes firebase-tools@15.32.1 deploy --only functions:wakeChildOnRefresh --project family-location-884e5
        if ($LASTEXITCODE -ne 0) { throw 'Function deployment failed' }
        & npx --yes firebase-tools@15.32.1 functions:list --project family-location-884e5
        if ($LASTEXITCODE -ne 0) { throw 'Post-deploy inspection failed' }
    }
} finally { Pop-Location }
