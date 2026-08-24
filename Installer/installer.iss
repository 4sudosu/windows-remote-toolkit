; RuntimeBroker — Standalone Installer (admin-only, installs a 24x7 Windows service)
; Build: compile with ISCC.exe after publishing a self-contained single-file
; agent EXE into ..\publish-single\RuntimeBroker.exe
;
; This installer:
;   1. Requires Administrator (PrivilegesRequired=admin)
;   2. Installs the agent into Program Files for all users
;   3. Registers and starts the "RuntimeBroker" Windows service (runs as
;      LocalSystem) so the machine is reachable 24x7 even with no user logged on
;   4. Writes agent.config.json during install and locks it so only SYSTEM and
;      Administrators can modify it — a normal user cannot change it.
;   5. Adds crash recovery (auto-restart) so the service runs 24x7.

#define MyAppName "RuntimeBroker"
#ifndef MyAppVersion
#define MyAppVersion "1.0.0.2"
#endif
#define MyAppExeName "RuntimeBroker.exe"
#define MyAppServiceName "RuntimeBroker"
#ifndef MyOutputBaseFilename
#define MyOutputBaseFilename "RuntimeBroker-Setup-1.0.0.2"
#endif
#define MyAppId "{{7A1F0C4B-5E2D-4A8F-9B3C-1D6E8A2F4B0C}"

[Setup]
AppId={#MyAppId}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher=Runtime Broker
DefaultDirName={autopf}\RuntimeBroker
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
DisableDirPage=no
OutputDir=..\installer-output
OutputBaseFilename={#MyOutputBaseFilename}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
CloseApplications=yes
UninstallDisplayName={#MyAppName}

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Files]
Source: "..\publish-single\RuntimeBroker.exe"; DestDir: "{app}"; Flags: ignoreversion; BeforeInstall: StopExistingService
Source: "..\publish-single\D3DCompiler_47_cor3.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\publish-single\PenImc_cor3.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\publish-single\PresentationNative_cor3.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\publish-single\vcruntime140_cor3.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\publish-single\wpfgfx_cor3.dll"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

; Remove EVERYTHING on uninstall: stop the service + any running EXE, reset the
; config ACL (it is locked to SYSTEM/Administrators), delete the service, then
; remove files that were not part of [Files] (config, staged update, logs).
[UninstallRun]
Filename: "{cmd}"; Parameters: "/c icacls ""{app}\agent.config.json"" /reset >nul 2>&1 & sc stop {#MyAppServiceName} >nul 2>&1 & sc delete {#MyAppServiceName} >nul 2>&1 & taskkill /f /im {#MyAppExeName} >nul 2>&1"; Flags: runhidden; RunOnceId: "StopService"

[UninstallDelete]
Type: files; Name: "{app}\agent.config.json"
Type: files; Name: "{app}\RuntimeBroker.new.exe"
Type: files; Name: "{app}\snow_apply_update.bat"
Type: files; Name: "{app}\agent.service.log"
Type: files; Name: "{app}\*.log"
Type: dirifempty; Name: "{app}"

; Config is written and locked from Pascal code after install so the admin
; prompt supplies the server/token, and the file is ACL-restricted.
[Code]
var
  ServerPage: TInputQueryWizardPage;

// Read a command-line parameter like /SERVERIP=1.2.3.4 (case-insensitive).
// Used so silent/automated installs can supply the server settings.
function GetCmdParam(const ParamName: string): string;
var
  I: Integer;
  P: string;
begin
  Result := '';
  for I := 1 to ParamCount do
  begin
    P := ParamStr(I);
    if (Pos('/' + ParamName + '=', P) = 1) or (Pos('-' + ParamName + '=', P) = 1) then
    begin
      Result := Copy(P, Pos('=', P) + 1, Length(P));
      Exit;
    end;
  end;
end;

// Stop + delete any previous RuntimeBroker service before overwriting the EXE,
// otherwise the running service locks the file and [Files] cannot replace it.
procedure StopExistingService;
var
  ResultCode: Integer;
begin
  Exec('sc.exe', 'stop {#MyAppServiceName}', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec('sc.exe', 'delete {#MyAppServiceName}', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec('taskkill.exe', '/f /im {#MyAppExeName}', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
end;

procedure InitializeWizard;
begin
  ServerPage := CreateInputQueryPage(
    wpSelectTasks,
    'RuntimeBroker Server',
    'Where should the agent connect?',
    'Enter the IP address or hostname of the RuntimeBroker server, then the port and the agent token. These can be changed later by an administrator editing agent.config.json in the install folder (the agent reloads it automatically).'
  );
  ServerPage.Add('Server IP / hostname:', False);
  ServerPage.Add('Port:', False);
  ServerPage.Add('Agent token:', False);
  ServerPage.Values[0] := GetCmdParam('SERVERIP');
  ServerPage.Values[1] := GetCmdParam('SERVERPORT');
  ServerPage.Values[2] := GetCmdParam('SERVERTOKEN');
  if ServerPage.Values[1] = '' then
    ServerPage.Values[1] := '3001';
  if ServerPage.Values[2] = '' then
    ServerPage.Values[2] := 'RUNTIME_BROKER_TOKEN_0001';
end;

function NextButtonClick(CurPageID: Integer): Boolean;
begin
  Result := True;
  if CurPageID = ServerPage.ID then
  begin
    if Trim(ServerPage.Values[0]) = '' then
    begin
      MsgBox('Please enter the server IP or hostname.', mbError, MB_OK);
      Result := False;
      Exit;
    end;
    if Trim(ServerPage.Values[1]) = '' then
      ServerPage.Values[1] := '3001';
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  ConfigPath, Json: string;
  Ip, Port, Token: string;
  ResultCode: Integer;
  AppExe, ScriptPath, Script: string;
begin
  if CurStep = ssPostInstall then
  begin
    Ip := Trim(ServerPage.Values[0]);
    Port := Trim(ServerPage.Values[1]);
    Token := Trim(ServerPage.Values[2]);

    if Ip = '' then
      RaiseException('No server IP was provided. Re-run setup with /SERVERIP=<ip> (e.g. /SERVERIP=10.64.173.167) or run the wizard interactively.');

    ConfigPath := ExpandConstant('{app}\agent.config.json');
    Json := '{' + #13#10 +
            '  "ServerUrl": "ws://' + Ip + ':' + Port + '/ws/agent",' + #13#10 +
            '  "Token": "' + Token + '",' + #13#10 +
            '  "ReconnectDelaySec": 5' + #13#10 +
            '}';
    SaveStringToFile(ConfigPath, Json, False);

    // Lock the config: strip inherited ACL, grant only SYSTEM + Administrators
    // full control and Users read-only. A normal user cannot modify it.
    Exec('icacls.exe',
      '"' + ConfigPath + '" /inheritance:r /grant:r "SYSTEM:(F)" "Administrators:(F)" "BUILTIN\Users:(R)"',
      '', SW_HIDE, ewWaitUntilTerminated, ResultCode);

    // Register + start the 24x7 Windows service (LocalSystem). The script is
    // written to a temp .ps1 file and run with -File to avoid all command-line
    // quoting problems (sc.exe and -Command both mangle quotes in "path --service").
    // Crash recovery (sc failure actions) makes the service auto-restart 24x7.
    AppExe := ExpandConstant('{app}') + '\{#MyAppExeName}';

    Script := '$ErrorActionPreference = ''Continue''' + #13#10 +
      '$binPath = ''"' + AppExe + '" --service''' + #13#10 +
      '$svc = ''{#MyAppServiceName}''' + #13#10 +
      'if (Get-Service $svc -ErrorAction SilentlyContinue) {' + #13#10 +
      '  sc.exe stop $svc | Out-Null' + #13#10 +
      '  sc.exe delete $svc | Out-Null' + #13#10 +
      '  Start-Sleep -Milliseconds 800' + #13#10 +
      '}' + #13#10 +
       'New-Service -Name $svc -BinaryPathName $binPath -StartupType Automatic -DisplayName ''Runtime Broker'' | Out-Null' + #13#10 +
       '# Repair any stale service registration left by an older install.' + #13#10 +
       'sc.exe config $svc binPath= $binPath start= auto | Out-Null' + #13#10 +
      'sc.exe failure $svc reset= 86400 actions= restart/5000/restart/10000/restart/30000 | Out-Null' + #13#10 +
      'sc.exe start $svc | Out-Null' + #13#10 +
      'if (-not (Get-Service $svc -ErrorAction SilentlyContinue)) { exit 1 }' + #13#10 +
      'exit 0';

    ScriptPath := ExpandConstant('{tmp}\install-runtimebroker-service.ps1');
    SaveStringToFile(ScriptPath, Script, False);
    Exec('powershell.exe',
      '-NoProfile -ExecutionPolicy Bypass -File "' + ScriptPath + '"',
      '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
    DeleteFile(ScriptPath);

    if ResultCode <> 0 then
      MsgBox('The RuntimeBroker service could not be created/started (error code ' +
        IntToStr(ResultCode) + '). Check that the agent files exist in ' +
        ExpandConstant('{app}') + ' and try again.', mbError, MB_OK);
  end;
end;
