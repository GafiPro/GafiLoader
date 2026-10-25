# GafiLoader Windows installer

The Windows installer is packaged as `GafiLoader-Setup-0.1.0.exe`. It installs a dedicated launcher version named `gafiloader-26.2` and places the GafiLoader agent and its configuration under `%APPDATA%\\.minecraft\\gafiloader`.

## User experience

1. Install and open the official Minecraft Java Launcher once, and sign in normally.
2. Run `GafiLoader-Setup-0.1.0.exe`.
3. The installer downloads the official Minecraft 26.2 client metadata/client JAR when missing, obtains official Minecraft 1.21.11 client mappings and Fabric Intermediary mappings, and creates a Fabric-based launcher version with GafiLoader's Java agent argument.
4. Open Minecraft Launcher → **Installations** and choose **gafiloader-26.2**. On first launch, the launcher downloads the required libraries/assets.
5. Put compatible Fabric mods in `%APPDATA%\\.minecraft\\mods` (or the game directory selected in the launcher).

No manual JVM arguments, mappings, or agent properties are required.

## Build locally

Requires JDK 25 and Inno Setup 6.

```powershell
.\\gradlew.bat --no-daemon clean shadowJar
& "$env:ProgramFiles(x86)\\Inno Setup 6\\ISCC.exe" ".\\installer\\GafiLoader.iss"
```

The setup executable is written to `dist\\GafiLoader-Setup-0.1.0.exe`. The GitHub Actions workflow **Package Windows installer** builds this executable on a Windows runner and uploads it as an artifact.

## Uninstall

Uninstalling GafiLoader from Windows Apps removes the installer files. To remove the Minecraft launcher version and runtime files too, delete `%APPDATA%\\.minecraft\\versions\\gafiloader-26.2` and `%APPDATA%\\.minecraft\\gafiloader`. The official Minecraft 26.2 installation and original mod JARs are not deleted or rewritten.

## Compatibility status

The executable packages the installer and registers the launcher profile; it does **not** make every legacy mod compatible automatically. The current automated smoke test reached resource loading with a pinned 1.21.11 mod on Minecraft 26.2, but title-screen/gameplay compatibility and arbitrary mods are not yet proven. See the main README for current limits.
