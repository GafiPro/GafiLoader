# GafiLoader installer for Minecraft Java Edition on Windows.
# Run from a folder containing the self-contained gafi-loader JAR.
$ErrorActionPreference = "Stop"
$MinecraftVersion = "26.2"
$FabricLoaderVersion = "0.20.1"
$ProfileId = "gafiloader-$MinecraftVersion"
$AgentJarName = "gafi-loader.jar"

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "[GafiLoader] $Message" -ForegroundColor Cyan
}
function Get-Json([string]$Url) {
    return Invoke-RestMethod -Uri $Url -Headers @{ "User-Agent" = "GafiLoader-Installer" }
}
function Download-File([string]$Url, [string]$Destination) {
    $parent = Split-Path -Parent $Destination
    if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    if (-not (Test-Path $Destination)) {
        Write-Host "  Downloading $([IO.Path]::GetFileName($Destination))"
        Invoke-WebRequest -Uri $Url -OutFile $Destination -Headers @{ "User-Agent" = "GafiLoader-Installer" }
    }
}

try {
    $sourceJar = Get-ChildItem -File -Path $PSScriptRoot -Filter "gafi-loader*.jar" |
        Where-Object { $_.Name -notmatch "-plain\.jar$" } |
        Select-Object -First 1
    if (-not $sourceJar) {
        throw "Put the self-contained gafi-loader JAR beside this installer script."
    }

    Write-Step "Locating the Minecraft Launcher game directory"
    $gameDir = Join-Path $env:APPDATA ".minecraft"
    if (-not (Test-Path $gameDir)) {
        throw "Minecraft Java Edition folder not found at $gameDir. Install/open the official launcher first."
    }
    $versionsDir = Join-Path $gameDir "versions"
    $baseVersionDir = Join-Path $versionsDir $MinecraftVersion
    $baseJsonPath = Join-Path $baseVersionDir "$MinecraftVersion.json"
    $baseJarPath = Join-Path $baseVersionDir "$MinecraftVersion.jar"

    Write-Step "Fetching official Minecraft $MinecraftVersion metadata"
    $manifest = Get-Json "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    $versionEntry = $manifest.versions | Where-Object { $_.id -eq $MinecraftVersion } | Select-Object -First 1
    if (-not $versionEntry) {
        throw "Minecraft $MinecraftVersion was not found in Mojang's official version manifest."
    }
    $versionMeta = Get-Json $versionEntry.url
    if (-not (Test-Path $baseJsonPath)) {
        New-Item -ItemType Directory -Force -Path $baseVersionDir | Out-Null
        $json = $versionMeta | ConvertTo-Json -Depth 100
        [IO.File]::WriteAllText($baseJsonPath, $json, [Text.UTF8Encoding]::new($false))
    }
    Download-File $versionMeta.downloads.client.url $baseJarPath

    Write-Step "Fetching Fabric Loader profile"
    $fabricProfileUrl = "https://meta.fabricmc.net/v2/versions/loader/$MinecraftVersion/$FabricLoaderVersion/profile/json"
    $profile = Get-Json $fabricProfileUrl
    $profile.id = $ProfileId
    $profile.time = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    $profile.releaseTime = $profile.time
    if (-not $profile.arguments) {
        throw "Fabric profile did not include launcher arguments; cannot safely install GafiLoader."
    }
    if ($null -eq $profile.arguments.PSObject.Properties['jvm']) { $profile.arguments | Add-Member -MemberType NoteProperty -Name jvm -Value @() }
    if ($null -eq $profile.arguments.PSObject.Properties['game']) { $profile.arguments | Add-Member -MemberType NoteProperty -Name game -Value @() }

    $gafiDir = Join-Path $gameDir "gafiloader"
    New-Item -ItemType Directory -Force -Path $gafiDir | Out-Null
    $agentJarPath = Join-Path $gafiDir $AgentJarName
    Copy-Item -Force $sourceJar.FullName $agentJarPath

    Write-Step "Preparing official 1.21.11 mappings"
    $legacyEntry = $manifest.versions | Where-Object { $_.id -eq "1.21.11" } | Select-Object -First 1
    if (-not $legacyEntry) { throw "Minecraft 1.21.11 was not found in Mojang's official version manifest." }
    $legacyMeta = Get-Json $legacyEntry.url
    if (-not $legacyMeta.downloads.client_mappings.url) {
        throw "Mojang did not publish client mappings for Minecraft 1.21.11."
    }
    $legacyMappings = Join-Path $gafiDir "minecraft-1.21.11-client-mappings.txt"
    Download-File $legacyMeta.downloads.client_mappings.url $legacyMappings

    $intermediaryJar = Join-Path $gafiDir "intermediary-1.21.11-v2.jar"
    Download-File "https://maven.fabricmc.net/net/fabricmc/intermediary/1.21.11/intermediary-1.21.11-v2.jar" $intermediaryJar

    $configPath = Join-Path $gafiDir "gafi-loader-agent.properties"
    @(
        "targetGameJar=$($baseJarPath.Replace('\','/'))"
        "intermediaryMappings=$($intermediaryJar.Replace('\','/'))"
        "legacyMappings=$($legacyMappings.Replace('\','/'))"
    ) | Set-Content -Encoding ASCII $configPath

    # Fabric Loader rejects legacy mods before the runtime bridge can remap their APIs.
    # Keep the original mod JAR untouched and remove only Fabric API's Minecraft version gate.
    # This is a dependency-resolution override, not a claim that all Fabric API behavior is compatible.
    $loaderConfigDir = Join-Path $gameDir "config"
    New-Item -ItemType Directory -Force -Path $loaderConfigDir | Out-Null
    $dependencyConfigPath = Join-Path $loaderConfigDir "fabric_loader_dependencies.json"
    if (Test-Path $dependencyConfigPath) {
        $dependencyConfig = Get-Content -LiteralPath $dependencyConfigPath -Raw | ConvertFrom-Json
        if ($null -eq $dependencyConfig.overrides) {
            $dependencyConfig | Add-Member -MemberType NoteProperty -Name overrides -Value ([pscustomobject]@{})
        }
    } else {
        $dependencyConfig = [pscustomobject]@{ version = 1; overrides = [pscustomobject]@{} }
    }
    if ($null -eq $dependencyConfig.overrides.'fabric-api') {
        $dependencyConfig.overrides | Add-Member -MemberType NoteProperty -Name 'fabric-api' -Value ([pscustomobject]@{})
    }
    $fabricApiOverride = $dependencyConfig.overrides.'fabric-api'
    if ($null -eq $fabricApiOverride.'-depends') {
        $fabricApiOverride | Add-Member -MemberType NoteProperty -Name '-depends' -Value ([pscustomobject]@{})
    }
    $fabricApiOverride.'-depends' | Add-Member -MemberType NoteProperty -Name minecraft -Value "" -Force
    $dependencyConfig.version = 1
    [IO.File]::WriteAllText($dependencyConfigPath,
        ($dependencyConfig | ConvertTo-Json -Depth 100) + [Environment]::NewLine,
        [Text.UTF8Encoding]::new($false))

    # Keep the -javaagent option as one JVM argument so paths containing spaces remain valid.
    $agentArgument = "-javaagent:$($agentJarPath.Replace('\','/'))=$($configPath.Replace('\','/'))"
    $profile.arguments.jvm = @($profile.arguments.jvm | Where-Object {
        -not ($_ -is [string] -and $_.StartsWith("-javaagent:") -and $_.Contains("gafi-loader"))
    })
    $profile.arguments.jvm = @($profile.arguments.jvm) + @($agentArgument)

    $profileDir = Join-Path $versionsDir $ProfileId
    New-Item -ItemType Directory -Force -Path $profileDir | Out-Null
    $profilePath = Join-Path $profileDir "$ProfileId.json"
    $profileJson = $profile | ConvertTo-Json -Depth 100
    [IO.File]::WriteAllText($profilePath, $profileJson, [Text.UTF8Encoding]::new($false))

    Write-Step "Installed"
    Write-Host "Minecraft version: $MinecraftVersion"
    Write-Host "Launcher profile:  $ProfileId"
    Write-Host "Profile JSON:      $profilePath"
    Write-Host ""
    Write-Host "Open Minecraft Launcher -> Installations and select '$ProfileId'."
    Write-Host "Mods go in: $gameDir\mods"
    Write-Host "Original mod JARs are not modified." -ForegroundColor Green
    Write-Host ""
    Write-Host "Note: this installs the runtime bridge and profile; not every 1.21.11 mod is compatible yet."
}
catch {
    Write-Host ""
    Write-Host "[GafiLoader] Installation failed: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
