param([Parameter(Mandatory=$true)][string]$JdkHome)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$env:PSModulePath = Join-Path $PSHOME 'Modules'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$appVersion = '1.2.0'
$buildRoot = Join-Path $projectRoot 'build/packaging'
$downloads = Join-Path $buildRoot 'downloads'
$toolsRoot = Join-Path $buildRoot 'tools'
$staging = Join-Path $buildRoot 'input'
$images = Join-Path $buildRoot "app-$appVersion"
$output = Join-Path $buildRoot 'distributions'
New-Item -ItemType Directory -Force -Path $downloads,$toolsRoot,$output | Out-Null

function Reset-BuildDirectory([string]$path) {
    $resolved = [IO.Path]::GetFullPath($path)
    if (-not $resolved.StartsWith($buildRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clear a directory outside build: $resolved"
    }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
    New-Item -ItemType Directory -Path $resolved -Force | Out-Null
}
function Download-Verified([string]$name,[string]$url,[string]$hash) {
    $path = Join-Path $downloads $name
    if (-not (Test-Path -LiteralPath $path) -or (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $hash) {
        Write-Host "Downloading $name"
        Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $path -TimeoutSec 900
    }
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $hash) { throw "Checksum mismatch: $name" }
    return $path
}
function Expand-VerifiedZip([string]$archive,[string]$directory) {
    $hash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
    $marker = Join-Path $directory '.archive-sha256'
    if (-not (Test-Path -LiteralPath $marker) -or (Get-Content -LiteralPath $marker -Raw).Trim() -ne $hash) {
        Reset-BuildDirectory $directory
        Expand-Archive -LiteralPath $archive -DestinationPath $directory -Force
        Set-Content -LiteralPath $marker -Value $hash -Encoding ascii
    }
}

$jreZip = Download-Verified 'temurin-jre-21.0.12.1_1.zip' 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jre_x64_windows_hotspot_21.0.12.1_1.zip' 'd35f31e712f0fcf6ac5a093edc90204fbff22f720ba3950bd09d331d5e621636'
$wixZip = Download-Verified 'wix314-binaries.zip' 'https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip' '6ac824e1642d6f7277d0ed7ea09411a508f6116ba6fae0aa5f2c7daa2ff43d31'
$webviewCab = Download-Verified 'webview2-fixed-153.0.4234.48-x64.cab' 'https://msedge.sf.dl.delivery.mp.microsoft.com/filestreamingservice/files/08cd33ee-d109-49b8-9301-9f0bea43c575/Microsoft.WebView2.FixedVersionRuntime.153.0.4234.48.x64.cab' '11e8240cb0bc56dcd3e4498907203c251346f65107fe35a3a13e152c7d51c79e'
Expand-VerifiedZip $jreZip (Join-Path $toolsRoot 'jre')
Expand-VerifiedZip $wixZip (Join-Path $toolsRoot 'wix')
$jre = (Get-ChildItem -LiteralPath (Join-Path $toolsRoot 'jre') -Directory | Select-Object -First 1).FullName
$webviewExtract = Join-Path $toolsRoot 'webview2'
$webview = Join-Path $webviewExtract 'Microsoft.WebView2.FixedVersionRuntime.153.0.4234.48.x64'
if (-not (Test-Path -LiteralPath (Join-Path $webview 'msedgewebview2.exe'))) {
    Reset-BuildDirectory $webviewExtract
    & (Join-Path $env:WINDIR 'System32/expand.exe') $webviewCab '-F:*' $webviewExtract | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'WebView2 extraction failed.' }
}
if (-not (Test-Path -LiteralPath (Join-Path $webview 'msedgewebview2.exe'))) { throw 'Fixed WebView2 runtime not found.' }

Reset-BuildDirectory $staging
Get-ChildItem -LiteralPath (Join-Path $buildRoot 'libraries') -Filter '*.jar' | Copy-Item -Destination $staging
Copy-Item -LiteralPath (Join-Path $buildRoot 'native-host') -Destination (Join-Path $staging 'native-host') -Recurse
Copy-Item -LiteralPath $webview -Destination (Join-Path $staging 'native-host/WebView2Runtime') -Recurse
Copy-Item -LiteralPath (Join-Path $projectRoot 'packaging/QUICKSTART.txt') -Destination $staging
Copy-Item -LiteralPath (Join-Path $projectRoot 'packaging/THIRD-PARTY-NOTICES.txt') -Destination $staging
# Keep dependency licenses shipped inside their JARs, the complete JRE legal directory and WebView2 notices.
$jpackage = Join-Path $JdkHome 'bin/jpackage.exe'
if (-not (Test-Path -LiteralPath $jpackage)) { throw 'JDK 21 jpackage is required for packaging.' }
Reset-BuildDirectory $images
Write-Host 'Building standalone GUI application...'
& $jpackage --type app-image --dest $images --input $staging --name LiveSpyer --app-version $appVersion `
    --main-jar LiveSpyer-1.0-SNAPSHOT.jar --main-class top.vrilhyc.applications.Main --runtime-image $jre `
    --vendor Vrilhyc --description 'LiveSpyer live streaming desktop client' --icon (Join-Path $projectRoot 'packaging/LiveSpyer.ico') `
    --java-options '-Dfile.encoding=UTF-8'
if ($LASTEXITCODE -ne 0) { throw 'Application image build failed.' }
$appImage = Join-Path $images 'LiveSpyer'
# Fixed WebView2 on Windows 11 needs read/execute for AppContainer identities. These are public program files only.
& (Join-Path $env:WINDIR 'System32/icacls.exe') (Join-Path $appImage 'app/native-host/WebView2Runtime') /grant '*S-1-15-2-1:(OI)(CI)(RX)' '*S-1-15-2-2:(OI)(CI)(RX)' /T /Q | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'WebView2 runtime permissions could not be prepared.' }

$env:PATH = (Join-Path $toolsRoot 'wix') + ';' + $env:PATH
$installer = Join-Path $output "LiveSpyer-$appVersion.exe"
if (Test-Path -LiteralPath $installer) { Remove-Item -LiteralPath $installer -Force }
Write-Host 'Building per-user EXE installer...'
& $jpackage --type exe --dest $output --app-image $appImage --name LiveSpyer --app-version $appVersion `
    --vendor Vrilhyc --win-per-user-install --win-menu --win-shortcut --win-dir-chooser `
    --win-upgrade-uuid 'b44a6b90-6cdc-49ae-84b2-4b4a2405b30d'
if ($LASTEXITCODE -ne 0) { throw 'EXE installer build failed.' }
Write-Host 'Compressing portable application...'
Compress-Archive -LiteralPath $appImage -DestinationPath (Join-Path $output "LiveSpyer-$appVersion-windows-x64-portable.zip") -Force
Get-FileHash -LiteralPath $installer,(Join-Path $output "LiveSpyer-$appVersion-windows-x64-portable.zip") -Algorithm SHA256 |
    ForEach-Object { $_.Hash.ToLowerInvariant() + '  ' + [IO.Path]::GetFileName($_.Path) } |
    Set-Content -LiteralPath (Join-Path $output "LiveSpyer-$appVersion-SHA256SUMS.txt") -Encoding ascii
Write-Host "Packages ready in $output"
