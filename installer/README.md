# Windows installer

The installer script creates a dedicated Minecraft Launcher profile named `gafiloader-26.2`. It uses the official Fabric Loader profile for Minecraft 26.2 and adds the GafiLoader Java agent to the profile's JVM arguments. The original game and mod JARs are not rewritten.

## Package contents

Place these two files in the same folder:

- `Install-GafiLoader.ps1`
- the self-contained `gafi-loader-*.jar` produced by Gradle's `shadowJar` task (not the `-plain.jar`).

## Install

1. Install and open the official Minecraft Launcher once so that `.minecraft` exists.
2. Build GafiLoader with JDK 25: `./gradlew clean build`.
3. Copy `installer/Install-GafiLoader.ps1` and `build/libs/gafi-loader-0.1.0-SNAPSHOT.jar` into one folder.
4. Open PowerShell in that folder and run:

   `powershell -ExecutionPolicy Bypass -File .\Install-GafiLoader.ps1`

The script downloads the official Minecraft 26.2 client JAR and metadata if absent, obtains the official 1.21.11 Mojang client mappings and Fabric Intermediary mappings, writes the agent configuration, and creates the launcher profile. The launcher downloads the remaining assets and libraries on first launch.

## Uninstall

Delete the `gafiloader-26.2` directory under `.minecraft\versions` and the `.minecraft\gafiloader` directory. The base Minecraft 26.2 files are retained.

## Compatibility status

Installing a launcher profile is not proof that arbitrary 1.21.11 mods work on 26.2. Runtime compatibility must be verified against real mods; structural changes and behavior changes may still require specific bridges.
