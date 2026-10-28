<#
.SYNOPSIS
    Builds the React-developer handoff ZIP for the deployed API, after the owner has deployed it by hand.

.DESCRIPTION
    Read-only towards the API: exactly ONE request, GET <ApiBaseUrl>/api/health. Never POST/PUT/PATCH/DELETE, never logs
    in, never creates users or records, never reads or asks for a secret.

    The package is created only when the health check returns HTTP 200 and the body is exactly {"status":"UP"}, and the
    response does NOT carry the retired "X-Invoice-Demo" header (which would mean the old cookie + CSRF demo build is
    still deployed, so the Bearer-token guide would not match it). A passing check proves ONLY that the application
    process answered - not the database schema, login, uploads, storage or the other 45 routes.

    Output (both under target/, which Git ignores; replaced on every run):
        target/react-handoff-package/      unpacked package
        target/React-Developer-Handoff.zip the file to send

.PARAMETER ApiBaseUrl
    The exact service URL shown in the Render Dashboard, e.g. https://your-service.onrender.com
    (HTTPS, no path, no query string, no user name or password).

.PARAMETER SelfTest
    Checks the packaging steps WITHOUT any network call: writes target/react-handoff-SELFTEST-not-for-sharing/ and
    target/React-Developer-Handoff-SELFTEST-not-for-sharing.zip, marked "NOT VERIFIED" everywhere. Never send it.

.EXAMPLE
    .\scripts\prepare-react-handoff.ps1 -ApiBaseUrl "https://YOUR-ACTUAL-SERVICE.onrender.com"
#>
[CmdletBinding(DefaultParameterSetName = 'Deployed')]
param(
    [Parameter(Mandatory = $true, ParameterSetName = 'Deployed')]
    [string]$ApiBaseUrl,

    # Seconds to wait for one health response. A Free service that spun down needs about a minute to start.
    [Parameter(ParameterSetName = 'Deployed')]
    [ValidateRange(10, 300)]
    [int]$TimeoutSeconds = 120,

    [Parameter(Mandatory = $true, ParameterSetName = 'SelfTest')]
    [switch]$SelfTest
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$repoRoot   = Split-Path -Parent $PSScriptRoot
$targetDir  = Join-Path $repoRoot 'target'
if ($SelfTest) {
    $packageDir = Join-Path $targetDir 'react-handoff-SELFTEST-not-for-sharing'
    $zipPath    = Join-Path $targetDir 'React-Developer-Handoff-SELFTEST-not-for-sharing.zip'
} else {
    $packageDir = Join-Path $targetDir 'react-handoff-package'
    $zipPath    = Join-Path $targetDir 'React-Developer-Handoff.zip'
}
$utf8NoBom  = New-Object System.Text.UTF8Encoding($false)

function Fail([string]$message, [string[]]$inspect) {
    Write-Host ''
    Write-Host "HANDOFF NOT CREATED: $message" -ForegroundColor Red
    if ($inspect) {
        Write-Host 'Check:' -ForegroundColor Yellow
        foreach ($line in $inspect) { Write-Host "  - $line" }
    }
    exit 1
}

function Write-Utf8([string]$path, [string]$text) {
    $folder = Split-Path -Parent $path
    if (-not (Test-Path $folder)) { New-Item -ItemType Directory -Path $folder | Out-Null }
    [System.IO.File]::WriteAllText($path, $text.Replace("`r`n", "`n"), $utf8NoBom)
}

function Escape-Html([string]$text) {
    return $text.Replace('&', '&amp;').Replace('<', '&lt;').Replace('>', '&gt;').Replace('"', '&quot;')
}

# ------------------------------------------------------------------ 1. remove output of an earlier run first
# A failed run must never leave an older package that looks verified. Only these two paths under target/ are touched.
if (Test-Path $packageDir) { Remove-Item -LiteralPath $packageDir -Recurse -Force }
if (Test-Path $zipPath)    { Remove-Item -LiteralPath $zipPath -Force }

if ($SelfTest) {
    # Reserved .invalid name (RFC 2606): can never resolve, so this package can never point at a real service.
    $baseUrl    = 'https://selftest.invalid'
    $healthUrl  = "$baseUrl/api/health"
    $checkedAt  = 'NOT CHECKED'
    $resultText = 'NOT VERIFIED - self-test package, no network call was made. Do not share.'
    Write-Host 'SELF-TEST: no network call is made; everything in this package is marked NOT VERIFIED.' -ForegroundColor Yellow
} else {

# ------------------------------------------------------------------ 2. validate the URL (before any network call)
$uri = $null
if (-not [System.Uri]::TryCreate($ApiBaseUrl.Trim(), [System.UriKind]::Absolute, [ref]$uri)) {
    Fail 'ApiBaseUrl is not an absolute URL.' @('Copy the URL exactly as shown in the Render Dashboard, e.g. https://your-service.onrender.com')
}
if ($uri.Scheme -ne 'https')                { Fail 'ApiBaseUrl must use https://.' }
if ($uri.UserInfo)                          { Fail 'ApiBaseUrl must not contain a user name or password.' }
if ($uri.Query -or $uri.Fragment)           { Fail 'ApiBaseUrl must not contain a query string or fragment.' }
if ($uri.AbsolutePath -ne '/')              { Fail 'ApiBaseUrl must be the bare service URL without a path (no /api, no trailing segments).' }
if (-not $uri.IsDefaultPort)                { Fail 'ApiBaseUrl must use the default HTTPS port (no :port).' }
$hostName = $uri.Host.ToLowerInvariant()
$ip = $null
if ([System.Net.IPAddress]::TryParse($hostName.Trim('[', ']'), [ref]$ip)) {
    Fail 'ApiBaseUrl must be a public host name, not an IP address.'
}
if ($hostName -eq 'localhost' -or $hostName.EndsWith('.localhost') -or $hostName.EndsWith('.local') -or
    $hostName.EndsWith('.internal') -or $hostName.EndsWith('.lan') -or -not $hostName.Contains('.')) {
    Fail "ApiBaseUrl host '$hostName' is a local or internal name; the React developer needs the public URL."
}
$baseUrl = 'https://' + $hostName
if (-not $hostName.EndsWith('.onrender.com')) {
    Write-Host "Note: '$hostName' is not an onrender.com host. Continue only if this is the service's own custom domain." -ForegroundColor Yellow
}

# ------------------------------------------------------------------ 3. the one read-only request
try {
    [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.ServicePointManager]::SecurityProtocol -bor
        [System.Net.SecurityProtocolType]::Tls12
} catch { }

$healthUrl = "$baseUrl/api/health"
Write-Host "GET $healthUrl  (timeout $TimeoutSeconds s; a sleeping Free service needs about a minute)"
$checkedAt = (Get-Date).ToUniversalTime().ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
$response = $null
try {
    $response = Invoke-WebRequest -Uri $healthUrl -Method Get -UseBasicParsing -MaximumRedirection 0 `
        -TimeoutSec $TimeoutSeconds -Headers @{ Accept = 'application/json' }
} catch {
    $status = $null
    if ($_.Exception.PSObject.Properties['Response'] -and $_.Exception.Response) {
        try { $status = [int]$_.Exception.Response.StatusCode } catch { }
    }
    $detail = if ($status) { "HTTP $status" } else { $_.Exception.GetType().Name }
    Fail "health check failed ($detail)." @(
        'Render Dashboard -> the service -> Events: is the latest deploy "Live"?',
        'Logs: the first ERROR / Caused by line (SchemaSafetyGuard, StorageStartupCheck, placeholder, CORS origins).',
        'Is the URL the one shown in the Dashboard, and is the health check path /api/health?',
        'A service that spun down may need longer: rerun, or pass -TimeoutSeconds 240.')
}

if ($response.StatusCode -ne 200) {
    Fail "health check answered HTTP $($response.StatusCode), expected 200." @('Redirects are not followed on purpose: use the final https URL.')
}
$body = $null
try { $body = $response.Content | ConvertFrom-Json } catch { Fail 'health check body is not JSON.' }
$names = @($body.PSObject.Properties | ForEach-Object { $_.Name })
if ($names.Count -ne 1 -or $names[0] -ne 'status' -or $body.status -ne 'UP') {
    Fail 'health check body is not exactly {"status":"UP"}.' @('Is this really the Invoice Process API?')
}
# Header names are case-insensitive (an HTTP/2 edge sends them lower-case): look the name up explicitly that way.
$demoHeader = $null
foreach ($name in @($response.Headers.Keys)) {
    if ($name -ieq 'X-Invoice-Demo') { $demoHeader = (@($response.Headers[$name]) -join ',').Trim() }
}
if ($demoHeader) {
    Fail 'the response carries the retired "X-Invoice-Demo" header: the old cookie + CSRF free-demo build is still deployed.' @(
        'Merge and deploy the Bearer-token release first (docs/RENDER_DEPLOYMENT.md), then rerun this script.',
        'The guide in this package describes Bearer tokens and would not match the old build.')
}
Write-Host 'Health check passed: HTTP 200, {"status":"UP"}, no retired demo header.' -ForegroundColor Green
$checkedAt  = $checkedAt + ' (UTC)'
$resultText = 'Passed: HTTP 200, body {"status":"UP"}, no retired X-Invoice-Demo header'
}

# ------------------------------------------------------------------ 4. source files (must exist)
$sources = [ordered]@{
    'docs/REACT_DEVELOPER_HANDOFF.html'           = 'docs/REACT_DEVELOPER_HANDOFF.html'
    'docs/openapi.yaml'                           = 'docs/openapi.yaml'
    'docs/API_CONTRACT.md'                        = 'docs/API_CONTRACT.md'
    'docs/REACT_BACKEND_HANDOFF.md'               = 'docs/REACT_BACKEND_HANDOFF.md'
    'docs/react-handoff/README.md'                = 'docs/react-handoff/README.md'
    'docs/react-handoff/env.example'              = 'docs/react-handoff/env.example'
    'docs/react-handoff/vite.config.example.ts'   = 'docs/react-handoff/vite.config.example.ts'
    'docs/react-handoff/sample-invoices/README.md' = 'docs/react-handoff/sample-invoices/README.md'
    'postman/Invoice-Process.postman_collection.json' = 'postman/Invoice-Process.postman_collection.json'
}
foreach ($relative in $sources.Keys) {
    if (-not (Test-Path (Join-Path $repoRoot $relative))) { Fail "required file is missing: $relative" }
}

# The Postman collection is shipped unchanged, so it must already be free of credentials and foreign hosts.
$collectionText = [System.IO.File]::ReadAllText((Join-Path $repoRoot 'postman/Invoice-Process.postman_collection.json'))
$collection = $collectionText | ConvertFrom-Json
if ($collection.PSObject.Properties['auth'] -and $collection.auth) { Fail 'the Postman collection has an auth block; remove it before sharing.' }
foreach ($variable in @($collection.variable)) {
    # accessToken is filled only at run time by "00 - Login"; an exported copy must never contain one.
    if ($variable.key -match 'pass|secret|token|apikey|api_key' -and -not [string]::IsNullOrEmpty([string]$variable.value)) {
        Fail "the Postman collection variable '$($variable.key)' has a value; clear it before sharing."
    }
}
foreach ($m in [regex]::Matches($collectionText, '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}')) {
    if (-not $m.Value.ToLowerInvariant().EndsWith('@example.test')) { Fail 'the Postman collection contains a non-synthetic e-mail address.' }
}

# ------------------------------------------------------------------ 5. build the package
$commit = 'unknown'
try {
    $sha = (& git -C $repoRoot rev-parse --short HEAD 2>$null)
    if ($LASTEXITCODE -eq 0 -and $sha) { $commit = $sha.Trim() }
} catch { }
$commitText = "local checkout $commit when packaged - compare with the commit in the Render deploy log"

foreach ($relative in $sources.Keys) {
    $text = [System.IO.File]::ReadAllText((Join-Path $repoRoot $relative))
    if ($relative -eq 'docs/REACT_DEVELOPER_HANDOFF.html') {
        $text = $text.Replace('{{API_BASE_URL}}', (Escape-Html $baseUrl))
        $text = $text.Replace('{{HEALTH_RESULT}}', (Escape-Html $resultText))
        $text = $text.Replace('{{HEALTH_CHECKED_AT}}', (Escape-Html "$checkedAt, by scripts/prepare-react-handoff.ps1"))
        $text = $text.Replace('{{BACKEND_COMMIT}}', (Escape-Html $commitText))
        # Placeholder tokens are {{UPPER_CASE}}; the page's own script legitimately contains the text '{{'.
        if ($text -cmatch '\{\{[A-Z_]+\}\}') { Fail "the handoff HTML still contains the placeholder $($Matches[0])." }
    } elseif ($relative.StartsWith('docs/react-handoff/')) {
        $text = $text.Replace('https://YOUR-ACTUAL-SERVICE.onrender.com', $baseUrl)
    }
    Write-Utf8 (Join-Path $packageDir $sources[$relative]) $text
}

# The six synthetic sample invoices (examples only - the API accepts any valid PDF). Copied BYTE-EXACT, not through the
# text path above, which normalises line endings and would corrupt a PDF.
$fixtureSource = Join-Path $repoRoot 'docs/react-handoff/sample-invoices'
$fixtures = @(Get-ChildItem -LiteralPath $fixtureSource -Filter 'synthetic-sample-invoice-*.pdf' | Sort-Object Name)
if ($fixtures.Count -ne 6) { Fail "expected 6 synthetic sample invoices in docs/react-handoff/sample-invoices, found $($fixtures.Count)." }
$fixtureTarget = Join-Path $packageDir 'docs/react-handoff/sample-invoices'
foreach ($fixture in $fixtures) {
    $copy = Join-Path $fixtureTarget $fixture.Name
    Copy-Item -LiteralPath $fixture.FullName -Destination $copy
    if ((Get-FileHash -LiteralPath $copy -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $fixture.FullName -Algorithm SHA256).Hash) {
        Fail "the packaged copy of $($fixture.Name) differs from the source."
    }
}

# Postman environment for the deployed API: base URL only, credentials always empty (typed in by the developer).
$environment = [ordered]@{
    name   = 'Invoice Process - Render'
    values = @(
        [ordered]@{ key = 'baseUrl';       value = $baseUrl; type = 'default'; enabled = $true },
        [ordered]@{ key = 'adminEmail';    value = '';       type = 'default'; enabled = $true },
        [ordered]@{ key = 'adminPassword'; value = '';       type = 'secret';  enabled = $true }
    )
    _postman_variable_scope = 'environment'
}
Write-Utf8 (Join-Path $packageDir 'postman/Invoice-Process-Render.postman_environment.json') `
    (($environment | ConvertTo-Json -Depth 5) + "`n")

$report = @"
# Handoff verification report

| Item | Value |
|---|---|
| API base URL | $baseUrl |
| Check | GET $healthUrl |
| Result | $resultText |
| Checked at | $checkedAt |
| Backend | $commitText |
| Requests made | $(if ($SelfTest) { '0 (self-test).' } else { '1 (the GET above).' }) No login, no POST/PUT/PATCH/DELETE, no data created. |

## What this proves - and what it does not

It proves that the application process behind this URL answered at the time above and was not the retired free-demo
build. It does NOT prove the database schema, login, the Bearer flow, uploads, durable PDF storage, extraction or any
of the other 45 routes. Those are checked by the React developer in the browser during integration (see "First
integration session" and "Definition of done" in docs/REACT_DEVELOPER_HANDOFF.html).

## Reminders

- Use only the test accounts and synthetic sample PDFs the backend owner provides; never real invoices in development.
- Whether uploaded PDFs are durable and whether extraction runs depends on the server's configuration
  (docs/RENDER_DEPLOYMENT.md) - ask the backend owner.
- Login credentials are NOT in this package; they are shared separately. Never put an access token into a file.
"@
$selfTestBanner = ''
if ($SelfTest) { $selfTestBanner = "> **SELF-TEST PACKAGE - NOT VERIFIED - DO NOT SHARE.** No service was contacted.`n`n" }
Write-Utf8 (Join-Path $packageDir 'docs/HANDOFF_VERIFICATION.md') ($selfTestBanner + $report + "`n")

$readme = @"
Actify Invoice Process - React developer handoff (Bearer-token API)

Open docs/REACT_DEVELOPER_HANDOFF.html in a browser. It works offline; all links are relative.

  docs/REACT_DEVELOPER_HANDOFF.html     start here
  docs/HANDOFF_VERIFICATION.md          what was checked, when, and what that does not prove
  docs/REACT_BACKEND_HANDOFF.md         the complete contract (routes, JSON shapes, errors, flows)
  docs/openapi.yaml                     the same contract as OpenAPI 3.0
  docs/API_CONTRACT.md                  pointer to the two files above
  docs/react-handoff/                   project setup, env and Vite proxy examples
  docs/react-handoff/sample-invoices/   six synthetic sample PDFs (examples only; any valid PDF is accepted)
  postman/                              collection (partial coverage, not live-tested) + environment

No credentials or tokens are included; they are shared separately.
"@
Write-Utf8 (Join-Path $packageDir 'README.txt') ($selfTestBanner.Replace('> **', '').Replace('**', '') + $readme + "`n")

# ------------------------------------------------------------------ 6. last safety scan of everything in the package
$patterns = @(
    'AIza[0-9A-Za-z_-]{35}', '-----BEGIN [A-Z ]*PRIVATE KEY-----', 'eyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.',
    'postgres(ql)?://[^\s:/]+:[^\s@]+@', 'gh[pousr]_[A-Za-z0-9]{30,}'
)
foreach ($file in Get-ChildItem -LiteralPath $packageDir -Recurse -File) {
    $content = [System.IO.File]::ReadAllText($file.FullName)
    foreach ($pattern in $patterns) {
        if ($content -match $pattern) {
            $relativeFile = $file.FullName.Substring($packageDir.Length + 1)
            Remove-Item -LiteralPath $packageDir -Recurse -Force
            Fail "a secret-like value was found in $relativeFile; nothing was packaged."
        }
    }
}

# Not Compress-Archive: Windows PowerShell 5.1 writes "docs\file" entry names, which macOS/Linux unzip tools extract
# as one file literally named "docs\file" - breaking every relative link. Entries are written with "/" and sorted.
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::Open($zipPath, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in (Get-ChildItem -LiteralPath $packageDir -Recurse -File | Sort-Object FullName)) {
        $entryName = $file.FullName.Substring($packageDir.Length + 1).Replace('\', '/')
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, $entryName,
            [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally {
    $archive.Dispose()
}

Write-Host ''
Write-Host 'Handoff package created:' -ForegroundColor Green
Get-ChildItem -LiteralPath $packageDir -Recurse -File | Sort-Object FullName | ForEach-Object {
    Write-Host ('  ' + $_.FullName.Substring($packageDir.Length + 1).Replace('\', '/'))
}
Write-Host "ZIP: $zipPath"
Write-Host ''
Write-Host 'Reminder: the health check proves only that the process answered - not the database, login, storage or the other routes.' -ForegroundColor Yellow
Write-Host 'Send test-account credentials separately (never inside this ZIP).' -ForegroundColor Yellow
exit 0
