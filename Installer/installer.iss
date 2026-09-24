; RuntimeBroker — Standalone Installer (admin-only, installs a 24x7 Windows service)
; Build: ISCC.exe installer.iss /DMyAppVersion=1.0.0.2
;   (needs ..\publish-single\RuntimeBroker.exe published first)
;
; This installer:
;   1. Requires Administrator (PrivilegesRequired=admin)
;   2. Installs the agent into Program Files for all users
;   3. Asks for server IP, port, agent token (optional) and the Windows
;      service name (rename on every reinstall);
;      silent installs use /SERVERIP= /SERVERPORT= /SERVERTOKEN= /SERVICENAME=
;   4. Writes agent.config.json during install and locks it so only SYSTEM and
;      Administrators can modify it — a normal user cannot change it.
;   5. Registers + starts the 24x7 Windows service (LocalSystem) with crash
;      recovery (auto-restart).

#define MyAppName "RuntimeBroker"
#ifndef MyAppVersion
#define MyAppVersion "1.0.0.2"
#endif
#define MyAppExeName "RuntimeBroker.exe"
#define MyAppServiceName "RuntimeBroker"
#ifndef MyOutputBaseFilename
#define MyOutputBaseFilename "RuntimeBroker-Setup-1.0.0.2"
#endif
#define MyAppId "{{B4E8F2A1-9C3D-4E7B-8D5F-2A6C9E1B4D70}"

[Setup]
AppId={#MyAppId}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher=RuntimeBroker
DefaultDirName={autopf}\RuntimeBroker
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
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
UsePreviousAppDir=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Files]
Source: "..\publish-single\{#MyAppExeName}"; DestDir: "{app}"; Flags: ignoreversion; BeforeInstall: StopExistingService

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

; Remember the chosen service name so uninstall can stop/delete it even when
; it was renamed. The key is removed on uninstall (uninsdeletekey).
[Registry]
Root: HKLM; Subkey: "SOFTWARE\RuntimeBroker"; ValueType: string; ValueName: "ServiceName"; ValueData: "{code:ServiceNameForInno}"; Flags: uninsdeletekey

; Remove EVERYTHING on uninstall: reset the config ACL (locked to
; SYSTEM/Administrators), stop + delete the service, kill any running EXE,
; then remove files not in [Files] (config, logs).
[UninstallRun]
Filename: "{cmd}"; Parameters: "/c icacls ""{app}\agent.config.json"" /reset >nul 2>&1 & sc stop {#MyAppServiceName} >nul 2>&1 & sc delete {#MyAppServiceName} >nul 2>&1 & taskkill /f /im {#MyAppExeName} >nul 2>&1"; Flags: runhidden; RunOnceId: "StopService"

[UninstallDelete]
Type: files; Name: "{app}\agent.config.json"
Type: files; Name: "{app}\agent.service.log"
Type: files; Name: "{app}\*.log"
Type: dirifempty; Name: "{app}"

; Config is written and locked from Pascal code after install so the admin
; prompt supplies the server/token, and the file is ACL-restricted.
[Code]
var
  ServerPage: TInputQueryWizardPage;
  ServicePage: TInputQueryWizardPage;

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

// The Windows service name chosen on the Service wizard page (or via
// /SERVICENAME= for silent installs). Falls back to the default when blank.
function GetServiceName(): string;
begin
  Result := '';
  try
    Result := Trim(ServicePage.Values[0]);
  except
  end;
  if Result = '' then
    Result := '{#MyAppServiceName}';
end;

// Wrapper so the name can be used in {code:} constants (e.g. [Registry]).
function ServiceNameForInno(Param: string): string;
begin
  Result := GetServiceName();
end;

// Service names may not contain slashes or quotes and are limited in length.
function IsValidServiceName(const N: string): Boolean;
begin
  Result := (N <> '') and (Length(N) <= 80) and
    (Pos('\', N) = 0) and (Pos('/', N) = 0) and
    (Pos('"', N) = 0) and (Pos(#39, N) = 0);
end;

// Stop + delete any previous agent service before overwriting the EXE,
// otherwise the running service locks the file and [Files] cannot replace it.
// Stops BOTH the name entered on the Service page and the legacy default, so a
// rename (or reinstall over an old build) never leaves a stale service behind.
procedure StopExistingService;
var
  ResultCode: Integer;
  Svc: string;
begin
  Svc := GetServiceName();
  Exec('sc.exe', 'stop "' + Svc + '"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec('sc.exe', 'delete "' + Svc + '"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  if Svc <> '{#MyAppServiceName}' then
  begin
    Exec('sc.exe', 'stop {#MyAppServiceName}', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
    Exec('sc.exe', 'delete {#MyAppServiceName}', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  end;
  Exec('taskkill.exe', '/f /im {#MyAppExeName}', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
end;

procedure InitializeWizard;
begin
  ServerPage := CreateInputQueryPage(
    wpSelectTasks,
    'RuntimeBroker Server',
    'Where should the agent connect?',
    'Enter the IP address or hostname of the relay server, then the port. The token is optional — leave it empty if the server does not require one. These can be changed later by an administrator editing agent.config.json in the install folder (the agent reloads it automatically).'
  );
  ServerPage.Add('Server IP / hostname:', False);
  ServerPage.Add('Port:', False);
  ServerPage.Add('Agent token (optional):', False);
  ServerPage.Values[0] := GetCmdParam('SERVERIP');
  ServerPage.Values[1] := GetCmdParam('SERVERPORT');
  ServerPage.Values[2] := GetCmdParam('SERVERTOKEN');
  if ServerPage.Values[1] = '' then
    ServerPage.Values[1] := '4777';

  ServicePage := CreateInputQueryPage(
    ServerPage.ID,
    'RuntimeBroker Service',
    'Which name should the Windows service use?',
    'This is the name shown in services.msc. You can rename it on every install ' +
    '(e.g. to blend in). Silent installs can pass /SERVICENAME=<name>.'
  );
  ServicePage.Add('Service name:', False);
  ServicePage.Values[0] := GetCmdParam('SERVICENAME');
  if Trim(ServicePage.Values[0]) = '' then
    ServicePage.Values[0] := '{#MyAppServiceName}';
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
      ServerPage.Values[1] := '4777';
  end;
  if CurPageID = ServicePage.ID then
  begin
    if not IsValidServiceName(Trim(ServicePage.Values[0])) then
    begin
      MsgBox('Please enter a valid service name (up to 80 characters, no \ / " or apostrophes).', mbError, MB_OK);
      Result := False;
      Exit;
    end;
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
      RaiseException('No server IP was provided. Re-run setup with /SERVERIP=<ip> or run the wizard interactively.');

    // Port 443 means TLS — plain ws:// gets HTTP 400 there.
    ConfigPath := ExpandConstant('{app}\agent.config.json');
    if Port = '443' then
      Json := '{' + #13#10 +
              '  "ServerUrl": "wss://' + Ip + '/ws/agent",' + #13#10 +
              '  "Token": "' + Token + '",' + #13#10 +
              '  "ReconnectDelaySec": 5' + #13#10 +
              '}'
    else
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

    // Register + start the 24x7 Windows service (LocalSystem) under the name
    // chosen on the Service page. The script is written to a temp .ps1 file
    // and run with -File to avoid all command-line quoting problems.
    // Crash recovery (sc failure actions) makes the service auto-restart 24x7.
    AppExe := ExpandConstant('{app}') + '\{#MyAppExeName}';

    Script := '$ErrorActionPreference = ''Continue''' + #13#10 +
      '$binPath = ''"' + AppExe + '" --service''' + #13#10 +
      '$svc = ''' + GetServiceName() + '''' + #13#10 +
      'if (Get-Service $svc -ErrorAction SilentlyContinue) {' + #13#10 +
      '  sc.exe stop $svc | Out-Null' + #13#10 +
      '  sc.exe delete $svc | Out-Null' + #13#10 +
      '  Start-Sleep -Milliseconds 800' + #13#10 +
      '}' + #13#10 +
      'New-Service -Name $svc -BinaryPathName $binPath -StartupType Automatic -DisplayName $svc | Out-Null' + #13#10 +
      'sc.exe failure $svc reset= 86400 actions= restart/5000/restart/10000/restart/30000 | Out-Null' + #13#10 +
      'sc.exe start $svc | Out-Null' + #13#10 +
      '$running = $false' + #13#10 +
      'for ($i = 0; $i -lt 15; $i++) {' + #13#10 +
      '  $s = Get-Service $svc -ErrorAction SilentlyContinue' + #13#10 +
      '  if ($s -and $s.Status -eq ''Running'') { $running = $true; break }' + #13#10 +
      '  Start-Sleep -Seconds 1' + #13#10 +
      '}' + #13#10 +
      'if (-not $running) { exit 2 }' + #13#10 +
      'exit 0';

    ScriptPath := ExpandConstant('{tmp}\install-runtimebroker-service.ps1');
    SaveStringToFile(ScriptPath, Script, False);
    Exec('powershell.exe',
      '-NoProfile -ExecutionPolicy Bypass -File "' + ScriptPath + '"',
      '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
    DeleteFile(ScriptPath);

    if ResultCode = 2 then
      MsgBox('The "' + GetServiceName() + '" service was created but is not running yet. ' +
        'It may still be starting (first start collects device info). ' +
        'Check services.msc shortly, or see ' + ExpandConstant('{app}') + '\agent.service.log for details.',
        mbInformation, MB_OK)
    else if ResultCode <> 0 then
      MsgBox('The "' + GetServiceName() + '" service could not be created/started (error code ' +
        IntToStr(ResultCode) + '). Check that the agent files exist in ' +
        ExpandConstant('{app}') + ' and try again.', mbError, MB_OK);
  end;
end;

// On uninstall, stop + delete the service name saved at install time (read
// from the registry). The static [UninstallRun] entry covers the legacy
// default name as a fallback.
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  Svc: string;
  ResultCode: Integer;
begin
  if CurUninstallStep = usUninstall then
  begin
    Svc := '{#MyAppServiceName}';
    RegQueryStringValue(HKLM, 'SOFTWARE\RuntimeBroker', 'ServiceName', Svc);
    Svc := Trim(Svc);
    if Svc = '' then
      Svc := '{#MyAppServiceName}';
    Exec('sc.exe', 'stop "' + Svc + '"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
    Exec('sc.exe', 'delete "' + Svc + '"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  end;
end;
