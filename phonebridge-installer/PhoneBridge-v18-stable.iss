#define MyAppName "PhoneBridge"
#define MyAppVersion "1.8.0"
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
OutputDir=out-v18-stable
OutputBaseFilename=PhoneBridge-v1.8-Stable-Setup
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\{#MyAppExeName}
SetupLogging=yes
CloseApplications=yes
RestartApplications=no

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Shortcuts:"; Flags: checkedonce
Name: "startup"; Description: "Start PhoneBridge when I sign in to Windows"; GroupDescription: "Startup:"; Flags: unchecked

[Files]
Source: "payload-v18-stable\PhoneBridge.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "payload-v18-stable\Camera\PhoneBridgeVirtualCameraSetup.exe"; DestDir: "{app}\Camera"; Flags: ignoreversion
Source: "payload-v18-stable\Camera\VirtualCameraMediaSource.dll"; DestDir: "{app}\Camera"; Flags: ignoreversion
Source: "payload-v18-stable\Camera\PhoneBridgeCameraProbe.exe"; DestDir: "{app}\Camera"; Flags: ignoreversion
Source: "payload-v18-stable\Android\PhoneBridge-v1.8.apk"; DestDir: "{app}\Android"; Flags: ignoreversion
Source: "payload-v18-stable\README-New-PC.txt"; DestDir: "{app}"; Flags: ignoreversion
Source: "payload-v18-stable\SHA256.txt"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\PhoneBridge"; Filename: "{app}\PhoneBridge.exe"
Name: "{autodesktop}\PhoneBridge"; Filename: "{app}\PhoneBridge.exe"; Tasks: desktopicon
Name: "{autoprograms}\PhoneBridge\Camera Health Test"; Filename: "{app}\Camera\PhoneBridgeCameraProbe.exe"
Name: "{autoprograms}\PhoneBridge\Android APK folder"; Filename: "{app}\Android"
Name: "{autoprograms}\PhoneBridge\Diagnostic logs"; Filename: "{localappdata}\PhoneBridge\Logs"
Name: "{autoprograms}\PhoneBridge\Get VB-CABLE for browser microphone"; Filename: "https://vb-audio.com/Cable/"

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "PhoneBridge"; ValueData: """{app}\PhoneBridge.exe"""; Tasks: startup; Flags: uninsdeletevalue

[Run]
Filename: "{app}\Camera\PhoneBridgeVirtualCameraSetup.exe"; Parameters: "/install /silent"; StatusMsg: "Installing PhoneBridge Camera..."; Flags: waituntilterminated runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""PhoneBridge"""; Flags: runhidden waituntilterminated
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""PhoneBridge"" dir=in action=allow program=""{app}\PhoneBridge.exe"" enable=yes profile=private"; StatusMsg: "Allowing PhoneBridge on private networks..."; Flags: runhidden waituntilterminated
Filename: "{app}\PhoneBridge.exe"; Description: "Launch PhoneBridge"; Flags: nowait postinstall skipifsilent
Filename: "{app}\Android"; Description: "Open Android APK folder"; Flags: shellexec postinstall skipifsilent unchecked
Filename: "https://vb-audio.com/Cable/"; Description: "Get VB-CABLE if this PC does not already have it (needed for browser microphone)"; Flags: shellexec postinstall skipifsilent unchecked

[UninstallRun]
Filename: "{app}\Camera\PhoneBridgeVirtualCameraSetup.exe"; Parameters: "/uninstall /silent"; Flags: runhidden waituntilterminated; RunOnceId: "RemovePhoneBridgeCamera"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""PhoneBridge"""; Flags: runhidden waituntilterminated; RunOnceId: "RemovePhoneBridgeFirewall"
