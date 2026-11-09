#ifndef AppSourceDir
  #error AppSourceDir must name the verified staged product directory.
#endif
#ifndef ReleaseVersion
  #error ReleaseVersion is required.
#endif
#ifndef ReleaseOutputDir
  #error ReleaseOutputDir is required.
#endif

[Setup]
AppId={{C43F52F2-127C-4EC6-AEBC-8C78376CE880}
AppName=open Goland
AppVersion={#ReleaseVersion}
AppPublisher=open Goland contributors
AppPublisherURL=https://github.com/shubolab/opengoland
AppSupportURL=https://github.com/shubolab/opengoland/issues
DefaultDirName={localappdata}\Programs\open Goland
DefaultGroupName=open Goland
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.17763
OutputDir={#ReleaseOutputDir}
OutputBaseFilename=open-Goland-{#ReleaseVersion}-windows-x64-setup
Compression=lzma2/fast
SolidCompression=yes
WizardStyle=modern
UninstallDisplayName=open Goland
UninstallDisplayIcon={app}\bin\opengoland64.exe
SetupLogging=yes
CloseApplications=yes
RestartApplications=no
DisableProgramGroupPage=yes

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Shortcuts:"; Flags: unchecked

[Files]
Source: "{#AppSourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\open Goland"; Filename: "{app}\bin\opengoland64.exe"; WorkingDir: "{app}"
Name: "{autodesktop}\open Goland"; Filename: "{app}\bin\opengoland64.exe"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{app}\bin\opengoland64.exe"; Description: "Launch open Goland"; Flags: nowait postinstall skipifsilent
