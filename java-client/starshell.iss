; StarShell Inno Setup 安装包脚本
; 作者: 星眸有光
; 将 jpackage 生成的 app-image (target\installer\StarShell) 打包为标准 Windows 安装包

[Setup]
AppName=StarShell
AppVersion=1.0.0
AppPublisher=星眸有光
AppPublisherURL=https://starshell.app
AppSupportURL=https://starshell.app
AppComments=StarShell - AI运维助手 (作者:星眸有光, 仿FinalShell)
DefaultDirName={autopf}\StarShell
DefaultGroupName=StarShell
DisableProgramGroupPage=yes
OutputDir=installer-output
OutputBaseFilename=StarShell-Setup-1.0.0
Compression=lzma2
SolidCompression=yes
ArchitecturesInstallIn64BitMode=x64
ArchitecturesAllowed=x64
UninstallDisplayIcon={app}\StarShell.exe
PrivilegesRequired=admin
WizardStyle=modern

[Languages]
Name: "chinesesimp"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式(&D)"; GroupDescription: "附加图标:"

[Files]
; 把整个 app-image 目录递归打包（含 StarShell.exe、app\*.jar、runtime\JRE）
Source: "target\installer\StarShell\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\StarShell"; Filename: "{app}\StarShell.exe"; Comment: "StarShell - AI运维助手"
Name: "{group}\卸载 StarShell"; Filename: "{uninstallexe}"
Name: "{commondesktop}\StarShell"; Filename: "{app}\StarShell.exe"; Tasks: desktopicon; Comment: "StarShell - AI运维助手 (作者:星眸有光)"

[Run]
Filename: "{app}\StarShell.exe"; Description: "立即启动 StarShell"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
Type: filesandordirs; Name: "{app}"
