$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
# Gradle may inherit PowerShell 7 module paths; use this Windows PowerShell's own modules.
$env:PSModulePath = Join-Path $PSHOME 'Modules'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$downloads = Join-Path $projectRoot 'build/downloads'
$sdk = Join-Path $projectRoot 'build/webview2-sdk'
$output = Join-Path $projectRoot 'build/native-host'
$archive = Join-Path $downloads 'webview2-1.0.2903.40.zip'
$checksum = 'ef128016dd1e51c59178c827ed5b8aa3322c57afa8675d930f8109505542ad74'
New-Item -ItemType Directory -Force -Path $downloads, $output | Out-Null
if (-not (Test-Path -LiteralPath $archive) -or (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $checksum) {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -UseBasicParsing -Uri 'https://api.nuget.org/v3-flatcontainer/microsoft.web.webview2/1.0.2903.40/microsoft.web.webview2.1.0.2903.40.nupkg' -OutFile $archive -TimeoutSec 180
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $checksum) { throw 'WebView2 SDK checksum mismatch.' }
if (-not (Test-Path -LiteralPath (Join-Path $sdk 'lib/net462/Microsoft.Web.WebView2.WinForms.dll'))) {
    Expand-Archive -LiteralPath $archive -DestinationPath $sdk -Force
}
Copy-Item -LiteralPath (Join-Path $sdk 'lib/net462/Microsoft.Web.WebView2.Core.dll'), (Join-Path $sdk 'lib/net462/Microsoft.Web.WebView2.WinForms.dll'), (Join-Path $sdk 'build/native/x64/WebView2Loader.dll') -Destination $output -Force
Copy-Item -LiteralPath (Join-Path $sdk 'LICENSE.txt') -Destination (Join-Path $output 'WebView2-LICENSE.txt') -Force
Copy-Item -LiteralPath (Join-Path $sdk 'NOTICE.txt') -Destination (Join-Path $output 'WebView2-NOTICE.txt') -Force
$compiler = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
if (-not (Test-Path -LiteralPath $compiler)) { throw '.NET Framework 4.8 is required to build the Windows browser host.' }
& $compiler /nologo /target:winexe /platform:x64 /optimize+ /reference:System.Windows.Forms.dll /reference:System.Drawing.dll /reference:System.dll /reference:System.Core.dll "/reference:$output/Microsoft.Web.WebView2.Core.dll" "/reference:$output/Microsoft.Web.WebView2.WinForms.dll" "/out:$output/LiveSpyer.BrowserHost.exe" (Join-Path $projectRoot 'native/windows/BrowserHost.cs')
if ($LASTEXITCODE -ne 0) { throw 'Browser host compilation failed.' }
