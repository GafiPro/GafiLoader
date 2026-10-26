#define AppName "GafiLoader"
#define AppVersion "0.1.2"
#define AppPublisher "GafiPro"
#define AgentJarName "gafi-loader-0.1.2.jar"

[Setup]
AppId={{B0B6A12A-0C78-4F2E-9AF2-8C01B7F3C026}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppPublisher}
DefaultDirName={localappdata}\Programs\GafiLoader
DefaultGroupName=GafiLoader
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..\dist
OutputBaseFilename=GafiLoader-Setup-{#AppVersion}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayName=GafiLoader
CloseApplications=no
ArchitecturesInstallIn64BitMode=x64
SetupLogging=yes

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Files]
Source: "..\installer\Install-GafiLoader.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\build\libs\{#AgentJarName}"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\GafiLoader Installer"; Filename: "{app}\Install-GafiLoader.ps1"; Parameters: ""; IconFilename: "{sys}\imageres.dll"; IconIndex: 1
Name: "{group}\Uninstall GafiLoader"; Filename: "{uninstallexe}"

[Code]
procedure CurStepChanged(CurStep: TSetupStep);
var
  ResultCode: Integer;
  PowerShellPath: string;
  ScriptPath: string;
begin
  if CurStep = ssPostInstall then
  begin
    PowerShellPath := ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe');
    ScriptPath := ExpandConstant('{app}\Install-GafiLoader.ps1');
    if not FileExists(PowerShellPath) then
      PowerShellPath := 'powershell.exe';

    if not Exec(PowerShellPath,
      '-NoProfile -ExecutionPolicy Bypass -File "' + ScriptPath + '"',
      ExpandConstant('{app}'), SW_SHOW, ewWaitUntilTerminated, ResultCode) then
      RaiseException('Could not start the GafiLoader setup script. Check that Windows PowerShell is installed.');

    if ResultCode <> 0 then
      RaiseException('GafiLoader could not be registered in the Minecraft Launcher. The installer stopped with exit code ' + IntToStr(ResultCode) + '. Review the setup output, fix the reported issue, and run the installer again.');
  end;
end;
