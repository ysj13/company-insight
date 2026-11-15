<#
  company-insight development environment setup (Windows 10/11)

  Run once on a new computer (PowerShell):
    powershell -ExecutionPolicy Bypass -c "irm https://raw.githubusercontent.com/ysj13/company-insight/main/scripts/setup-windows.ps1 | iex"

  Or from an existing clone:
    powershell -ExecutionPolicy Bypass -File scripts\setup-windows.ps1

  Safe to run again: installed tools and existing files are skipped.

  Environment variables (optional):
    CI_SETUP_DIR      clone location when run outside the repo (default C:\workspace\company-insight)
    CI_SETUP_SKIP_IDE set to 1 to skip IntelliJ IDEA and DBeaver
    CI_SETUP_SKIP_BUILD set to 1 to skip the Gradle build
#>

$ErrorActionPreference = 'Stop'
$RepoUrl = 'https://github.com/ysj13/company-insight.git'
$DefaultDir = if ($env:CI_SETUP_DIR) { $env:CI_SETUP_DIR } else { 'C:\workspace\company-insight' }

function Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Info($msg) { Write-Host "    $msg" }
function Warn($msg) { Write-Host "    ! $msg" -ForegroundColor Yellow }

function Refresh-Path {
    $machine = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $user = [Environment]::GetEnvironmentVariable('Path', 'User')
    $env:Path = "$machine;$user"
    $jh = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')
    if ($jh) { $env:JAVA_HOME = $jh }
}

# Windows PowerShell 5.1 turns native stderr into a terminating error when
# $ErrorActionPreference is Stop and stderr is redirected. Commands that report
# "not logged in" / "not running" on stderr must run with Continue.
function Test-NativeOk([scriptblock]$Command) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Command *> $null } catch { $global:LASTEXITCODE = 1 }
    finally { $ErrorActionPreference = $old }
    return ($LASTEXITCODE -eq 0)
}

function Install-Package($id, $name) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $listed = winget list --id $id -e --accept-source-agreements 2>$null
    $ErrorActionPreference = $old
    $installed = $listed | Select-String -SimpleMatch $id
    if ($installed) { Info "${name}: already installed"; return }
    Info "${name}: installing ($id)"
    winget install --id $id -e --source winget --accept-package-agreements --accept-source-agreements --silent
    if ($LASTEXITCODE -ne 0) { throw "${name} install failed (exit $LASTEXITCODE)" }
}

# 1. Tools ------------------------------------------------------------------
Step 'Install tools with winget'
if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
    throw 'winget not found. Install "App Installer" from Microsoft Store and run again.'
}
Install-Package 'Git.Git' 'Git'
Install-Package 'GitHub.cli' 'GitHub CLI'
Install-Package 'EclipseAdoptium.Temurin.25.JDK' 'JDK 25 (Temurin)'
Install-Package 'Docker.DockerDesktop' 'Docker Desktop'
if ($env:CI_SETUP_SKIP_IDE -ne '1') {
    Install-Package 'JetBrains.IntelliJIDEA.Ultimate' 'IntelliJ IDEA'
    Install-Package 'DBeaver.DBeaver.Community' 'DBeaver'
}
Refresh-Path

$jdk = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue |
    Where-Object Name -like 'jdk-25*' | Sort-Object Name -Descending | Select-Object -First 1
if ($jdk) { $env:JAVA_HOME = $jdk.FullName; Info "JAVA_HOME = $($jdk.FullName)" }
$dockerBin = 'C:\Program Files\Docker\Docker\resources\bin'
if (Test-Path $dockerBin) { $env:Path = "$dockerBin;$env:Path" }

# 2. Virtualization --------------------------------------------------------
Step 'Check CPU virtualization (needed by Docker)'
$cs = Get-CimInstance Win32_ComputerSystem
$cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
if ($cs.HypervisorPresent -or $cpu.VirtualizationFirmwareEnabled) {
    Info 'enabled'
} else {
    Warn 'Virtualization is OFF in BIOS/UEFI. Docker will not start until it is enabled.'
    Warn 'Settings > System > Recovery > Advanced startup > Restart now >'
    Warn 'Troubleshoot > Advanced options > UEFI Firmware Settings, then enable'
    Warn 'Intel Virtualization Technology (VT-x) or SVM Mode (AMD). Use a USB keyboard/mouse.'
}

# 3. GitHub login ----------------------------------------------------------
Step 'GitHub login'
if (-not (Test-NativeOk { gh auth status })) {
    Info 'A browser will open. Enter the one-time code shown here.'
    gh auth login --hostname github.com --git-protocol https --web
    if ($LASTEXITCODE -ne 0) { throw 'GitHub login failed' }
} else { Info 'already logged in' }

# 4. Repository ------------------------------------------------------------
Step 'Repository'
$inRepo = (Test-Path '.\settings.gradle.kts') -and (Select-String -Path '.\settings.gradle.kts' -Pattern 'company-insight' -Quiet)
if ($inRepo) {
    $RepoDir = (Get-Location).Path
    Info "using current folder: $RepoDir"
} elseif (Test-Path (Join-Path $DefaultDir '.git')) {
    $RepoDir = $DefaultDir
    Info "already cloned: $RepoDir"
} else {
    New-Item -ItemType Directory -Force -Path (Split-Path $DefaultDir) | Out-Null
    git clone $RepoUrl $DefaultDir
    if ($LASTEXITCODE -ne 0) { throw 'git clone failed' }
    $RepoDir = $DefaultDir
}
Set-Location $RepoDir

$login = gh api user --jq .login
$uid = gh api user --jq .id
if (-not (git config user.email)) {
    git config user.name $login
    git config user.email "$uid+$login@users.noreply.github.com"
    Info "git author: $login <$uid+$login@users.noreply.github.com>"
}

# 5. .env --------------------------------------------------------------------
Step 'Local .env'
if (Test-Path '.env') {
    Info '.env exists, not changed'
} else {
    $chars = [char[]]'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
    $pw = -join (1..24 | ForEach-Object { $chars | Get-Random })
    (Get-Content '.env.example') `
        -replace '^POSTGRES_PASSWORD=$', "POSTGRES_PASSWORD=$pw" `
        -replace '^DB_PASSWORD=$', "DB_PASSWORD=$pw" |
        Set-Content -Encoding ascii '.env'
    Info '.env created with a random local DB password'
}

# 6. Docker + DB -------------------------------------------------------------
Step 'Docker engine'
if (-not (Test-NativeOk { docker info })) {
    $exe = 'C:\Program Files\Docker\Docker\Docker Desktop.exe'
    if (Test-Path $exe) { Start-Process $exe }
    Info 'waiting for Docker Desktop (accept its terms on first run)...'
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Seconds 5
        if (Test-NativeOk { docker info }) { break }
    }
}
$dockerOk = Test-NativeOk { docker info }
if (-not $dockerOk) {
    Warn 'Docker engine is not running. Fix it (see virtualization above), then run this script again.'
} else {
    Info 'running'
    Step 'Local PostgreSQL'
    docker compose up -d
    if ($LASTEXITCODE -ne 0) { throw 'docker compose up failed' }
}

# 7. Build -------------------------------------------------------------------
if ($dockerOk -and $env:CI_SETUP_SKIP_BUILD -ne '1') {
    Step 'Build and test'
    .\gradlew.bat build --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }
}

# 8. Next ---------------------------------------------------------------------
Step 'Done'
Info "Folder: $RepoDir"
Info 'Fill in DART_API_KEY and GEMINI_API_KEY in .env when you have them.'
Info 'Run the app: .\gradlew.bat bootRun  ->  http://localhost:8080/actuator/health'
