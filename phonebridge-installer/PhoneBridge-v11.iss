#define MyAppName "PhoneBridge"
#define MyAppVersion "1.1.0-rc1"
#define MyAppPublisher "PhoneBridge"
#define MyAppExeName "PhoneBridge.exe"

[Setup]
AppId={{5E2E71C7-673A-4E9B-9C93-5E5D561B3C81}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\PhoneBridge
DefaultGroupName=PhoneBridge
DisableProgramGroupPage=yes
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=out-v11
OutputBaseFilename=PhoneBridge-v1.1-RC1-Setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\{#MyAppExeName}
SetupLogging=yes

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Shortcuts:"; Flags: unchecked
Name: "startup"; Description: "Start PhoneBridge when I sign in to Windows"; GroupDescription: "Startup:"; Flags: unchecked

[Files]
Source: "payload-v11\PhoneBridge.exe"; DestDir: "{app}"; DestName: "PhoneBridge.exe"; Flags: ignoreversion
Source: "payload-v11\PhoneBridgeVirtualCameraSetup.exe"; DestDir: "{app}\Camera"; Flags: ignoreversion
Source: "payload-v11\VirtualCameraMediaSource.dll"; DestDir: "{app}\Camera"; Flags: ignoreversion
Source: "payload-v11\PhoneBridge-v1.apk"; DestDir: "{app}\Android"; Flags: ignoreversion
Source: "payload-v11\README.txt"; DestDir: "{app}"; Flags: ignoreversion
Source: "payload-v11\CHECKSUMS-SHA256.txt"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\PhoneBridge"; Filename: "{app}\PhoneBridge.exe"
Name: "{autodesktop}\PhoneBridge"; Filename: "{app}\PhoneBridge.exe"; Tasks: desktopicon
Name: "{autoprograms}\PhoneBridge\Android APK folder"; Filename: "{app}\Android"
Name: "{autoprograms}\PhoneBridge\Diagnostic logs"; Filename: "{localappdata}\PhoneBridge\Logs"

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "PhoneBridge"; ValueData: """{app}\PhoneBridge.exe"""; Tasks: startup; Flags: uninsdeletevalue

[Run]
Filename: "{app}\Camera\PhoneBridgeVirtualCameraSetup.exe"; Parameters: "/install /silent"; StatusMsg: "Installing PhoneBridge Camera..."; Flags: waituntilterminated runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""PhoneBridge"""; Flags: runhidden waituntilterminated
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""PhoneBridge"" dir=in action=allow program=""{app}\PhoneBridge.exe"" enable=yes profile=private"; StatusMsg: "Allowing PhoneBridge on private networks..."; Flags: runhidden waituntilterminated
Filename: "{app}\PhoneBridge.exe"; Description: "Launch PhoneBridge"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{app}\Camera\PhoneBridgeVirtualCameraSetup.exe"; Parameters: "/uninstall /silent"; Flags: runhidden waituntilterminated; RunOnceId: "RemovePhoneBridgeCamera"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""PhoneBridge"""; Flags: runhidden waituntilterminated; RunOnceId: "RemovePhoneBridgeFirewall"
