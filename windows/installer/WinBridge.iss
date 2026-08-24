; WinBridge installer.
;
; Per-user by design: PrivilegesRequired=lowest means no UAC prompt and no
; administrator account needed. Everything the app does lives in the user's own
; session anyway (see docs/ARCHITECTURE.md ADR-1), so a machine-wide install
; would buy nothing and cost a permission dialog.
;
; Build:  ISCC.exe WinBridge.iss
; Expects a published app in ..\publish\win-x64

#define AppName        "WinBridge"
#define AppVersion     "0.3.0"
#define AppPublisher   "CaYatur"
#define AppURL         "https://github.com/CaYatur/Windows-to-Android-Bridge"
#define AppExeName     "WinBridge.exe"

[Setup]
AppId={{8C4E1A73-5D2B-4F6E-9A81-2F7C3B4D5E6A}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher={#AppPublisher}
AppPublisherURL={#AppURL}
AppSupportURL={#AppURL}/issues
AppUpdatesURL={#AppURL}/releases

DefaultDirName={localappdata}\Programs\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
DisableDirPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible

OutputDir=Output
OutputBaseFilename=WinBridge-Setup-{#AppVersion}
SetupIconFile=
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
UninstallDisplayName={#AppName}
UninstallDisplayIcon={app}\{#AppExeName}

; The build is not code-signed, so Windows will warn on first run. Saying so in
; the installer is better than letting it be a surprise.
VersionInfoVersion={#AppVersion}
VersionInfoCompany={#AppPublisher}
VersionInfoDescription=Monitor and control Windows from Android

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "turkish";  MessagesFile: "compiler:Languages\Turkish.isl"

[CustomMessages]
english.LaunchAfterInstall=Start {#AppName} now
english.AutoStart=Start automatically when I sign in
english.SmartScreenNote=This build is not code-signed. Windows SmartScreen may warn the first time you run it.
turkish.LaunchAfterInstall={#AppName} uygulamasını şimdi başlat
turkish.AutoStart=Oturum açtığımda otomatik başlat
turkish.SmartScreenNote=Bu sürüm imzalı değildir. Windows SmartScreen ilk çalıştırmada uyarı verebilir.

[Tasks]
Name: "autostart"; Description: "{cm:AutoStart}"; GroupDescription: "{#AppName}"

[Files]
Source: "..\publish\win-x64\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\..\README.md"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\..\LICENSE"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\{#AppExeName}"
Name: "{group}\{cm:UninstallProgram,{#AppName}}"; Filename: "{uninstallexe}"
Name: "{userdesktop}\{#AppName}"; Filename: "{app}\{#AppExeName}"; Tasks: autostart

[Registry]
; Autostart lives under HKCU so it needs no elevation and follows the user
; profile. The app keeps this in sync with its own setting afterwards.
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; \
    ValueType: string; ValueName: "WinBridge"; \
    ValueData: """{app}\{#AppExeName}"" --tray"; \
    Flags: uninsdeletevalue; Tasks: autostart

[Run]
Filename: "{app}\{#AppExeName}"; Description: "{cm:LaunchAfterInstall}"; \
    Flags: nowait postinstall skipifsilent

[UninstallDelete]
; Settings and the pairing key are the user's data; leave them unless the user
; asks. The log is ours and can go.
Type: files; Name: "{userappdata}\WinBridge\winbridge.log"
Type: files; Name: "{userappdata}\WinBridge\winbridge.log.1"

[Code]
// A running copy holds the listening port and the RFCOMM service record, so it
// has to be closed before files are replaced -- otherwise the upgrade silently
// leaves the old binary in place.
function CloseRunningInstance(): Boolean;
var
  ResultCode: Integer;
begin
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/IM {#AppExeName} /F',
       '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Sleep(600);
  Result := True;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  CloseRunningInstance();
  Result := '';
end;

function InitializeUninstall(): Boolean;
begin
  CloseRunningInstance();
  Result := True;
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then
    Log('WinBridge installed to ' + ExpandConstant('{app}'));
end;
