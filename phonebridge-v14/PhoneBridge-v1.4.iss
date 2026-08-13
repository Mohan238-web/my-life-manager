#define MyAppName "PhoneBridge"
#define MyAppVersion "1.4.0"
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
OutputDir=out-v14
OutputBaseFilename=PhoneBridge-v1.4-Setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\{#MyAppExeName}
SetupLogging=yes
CloseApplications=yes
RestartApplications=no

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Shortcuts:"; Flags: unchecked
Name: "startup"; Description: "Start PhoneBridge when I sign in to Windows"; GroupDescription: "Startup:"; Flags: unchecked

[Files]
Source: "payload-v14\PhoneBridge.exe"; DestDir: "{app}"; DestName: "PhoneBridge.exe"; Flags: ignoreversion
Source: "payload-v14\PhoneBridgeVirtualCameraSetup.exe"; DestDir: "{app}\Camera"; Flags: ignoreversion
Source: "payload-v14\PhoneBridgeCameraProbe.exe"; DestDir: "{app}\Camera"; Flags: ignoreversion
Source: "payload-v14\VirtualCameraMediaSource.dll"; DestDir: "{app}\Camera"; Flags: ignoreversion
Source: "payload-v14\PhoneBridge-v1.2.apk"; DestDir: "{app}\Android"; Flags: ignoreversion
Source: "payload-v14\README.txt"; DestDir: "{app}"; Flags: ignoreversion
Source: "payload-v14\BROWSER-AUDIO-CAMERA.txt"; DestDir: "{app}"; Flags: ignoreversion
Source: "payload-v14\CHECKSUMS-SHA256.txt"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\PhoneBridge"; Filename: "{app}\PhoneBridge.exe"
Name: "{autodesktop}\PhoneBridge"; Filename: "{app}\PhoneBridge.exe"; Tasks: desktopicon
Name: "{autoprograms}\PhoneBridge\Android APK folder"; Filename: "{app}\Android"
Name: "{autoprograms}\PhoneBridge\Camera Health Test"; Filename: "{app}\Camera\PhoneBridgeCameraProbe.exe"
Name: "{autoprograms}\PhoneBridge\Windows Audio Devices"; Filename: "{sys}\control.exe"; Parameters: "mmsys.cpl,,1"
Name: "{autoprograms}\PhoneBridge\Camera Privacy Settings"; Filename: "ms-settings:privacy-webcam"
Name: "{autoprograms}\PhoneBridge\Microphone Privacy Settings"; Filename: "ms-settings:privacy-microphone"

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "PhoneBridge"; ValueData: """{app}\PhoneBridge.exe"""; Tasks: startup; Flags: uninsdeletevalue

[Run]
; v1.2/v1.3 created a recurring elevated camera repair task. v1.4 deliberately removes it;
; persistent Media Foundation System-lifetime registration does not need to be rewritten at every logon.
Filename: "{sys}\schtasks.exe"; Parameters: "/Delete /F /TN PhoneBridgeCameraRepair"; Flags: runhidden waituntilterminated
Filename: "{app}\Camera\PhoneBridgeVirtualCameraSetup.exe"; Parameters: "/repair /silent"; StatusMsg: "Cleaning old PhoneBridge Camera registrations and installing the v1.4 camera..."; Flags: waituntilterminated runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""PhoneBridge"""; Flags: runhidden waituntilterminated
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""PhoneBridge"" dir=in action=allow program=""{app}\PhoneBridge.exe"" enable=yes profile=private"; StatusMsg: "Allowing PhoneBridge on private networks..."; Flags: runhidden waituntilterminated
Filename: "{app}\PhoneBridge.exe"; Description: "Launch PhoneBridge"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{sys}\schtasks.exe"; Parameters: "/Delete /F /TN PhoneBridgeCameraRepair"; Flags: runhidden waituntilterminated; RunOnceId: "RemoveLegacyPhoneBridgeCameraTask"
Filename: "{app}\Camera\PhoneBridgeVirtualCameraSetup.exe"; Parameters: "/uninstall /silent"; Flags: runhidden waituntilterminated; RunOnceId: "RemovePhoneBridgeCamera"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""PhoneBridge"""; Flags: runhidden waituntilterminated; RunOnceId: "RemovePhoneBridgeFirewall"
