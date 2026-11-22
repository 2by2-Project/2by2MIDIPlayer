param([Parameter(Mandatory = $true)][string]$Path)
$ErrorActionPreference = 'Stop'
$installer = New-Object -ComObject WindowsInstaller.Installer
$database = $installer.OpenDatabase((Get-Item -LiteralPath $Path).FullName, 0)
function Read-Table([string]$Query, [int]$Columns) {
    $view = $database.OpenView($Query)
    try {
        [void]$view.Execute()
        while ($record = $view.Fetch()) {
            $values = @(for ($i = 1; $i -le $Columns; $i++) { $record.StringData($i) })
            ,$values
        }
    } finally { [void]$view.Close() }
}
$properties = @(Read-Table 'SELECT `Property`, `Value` FROM `Property`' 2)
function Get-Property([string]$Name) { ($properties | Where-Object { $_[0] -eq $Name })[1] }
if ((Get-Property 'ProductName') -cne '2by2 MIDI Player') { throw 'The product name must be 2by2 MIDI Player' }
if ((Get-Property 'Manufacturer') -cne '2by2 Project') { throw 'The manufacturer must be 2by2 Project' }
if (!($properties | Where-Object { $_[0] -eq 'WIXUI_INSTALLDIR' -and $_[1] -eq 'INSTALLDIR' })) {
    throw 'The wizard must select INSTALLDIR'
}
$dialogs = @(Read-Table 'SELECT `Dialog` FROM `Dialog`' 1)
foreach ($name in @('WelcomeDlg', 'InstallDirDlg', 'VerifyReadyDlg', 'ProgressDlg', 'ExitDialog', 'MaintenanceWelcomeDlg', 'MaintenanceTypeDlg')) {
    if (!($dialogs | Where-Object { $_[0] -eq $name })) { throw "Missing wizard dialog: $name" }
}
$events = @(Read-Table 'SELECT `Dialog_`, `Control_`, `Event`, `Argument`, `Ordering` FROM `ControlEvent`' 5)
if (!($events | Where-Object { $_[0] -eq 'WelcomeDlg' -and $_[1] -eq 'Next' -and $_[2] -eq 'NewDialog' -and $_[3] -eq 'InstallDirDlg' -and $_[4] -eq '2' })) {
    throw 'Welcome must lead to directory selection'
}
# Associations are always registered; there is no optional feature or checkbox for them.
$features = @(Read-Table 'SELECT `Feature` FROM `Feature`' 1)
if ($features | Where-Object { $_[0] -match 'Association' }) { throw 'Unexpected file-association feature' }
# Root 2 is HKEY_LOCAL_MACHINE.
$registry = @(Read-Table 'SELECT `Root`, `Key`, `Name`, `Value` FROM `Registry`' 4)
if ($registry | Where-Object { $_[1] -match 'UserChoice' }) { throw 'The MSI must never write UserChoice' }
$command = '"[INSTALLDIR]2by2MIDIPlayer.exe" "%1"'
$expected = @(
    @('Software\Project2by2\MIDIPlayer\Capabilities', 'ApplicationName', '2by2 MIDI Player'),
    @('Software\Project2by2\MIDIPlayer\Capabilities\FileAssociations', '.mid', 'Project2by2.MIDI'),
    @('Software\Project2by2\MIDIPlayer\Capabilities\FileAssociations', '.midi', 'Project2by2.MIDI'),
    @('Software\RegisteredApplications', '2by2 MIDI Player', 'Software\Project2by2\MIDIPlayer\Capabilities'),
    @('Software\Classes\Project2by2.MIDI\shell\open\command', '', $command),
    # WiX may encode an empty REG_SZ specially, so only the presence of these values is checked.
    @('Software\Classes\.mid\OpenWithProgids', 'Project2by2.MIDI', $null),
    @('Software\Classes\.midi\OpenWithProgids', 'Project2by2.MIDI', $null),
    @('Software\Classes\Applications\2by2MIDIPlayer.exe', 'FriendlyAppName', '2by2 MIDI Player')
)
foreach ($row in $expected) {
    if (!($registry | Where-Object { $_[0] -eq '2' -and $_[1] -eq $row[0] -and $_[2] -eq $row[1] -and ($null -eq $row[2] -or $_[3] -eq $row[2]) })) {
        throw "Missing association registry value: $($row[0]) [$($row[1])]"
    }
}
# Shortcut names are stored as "short|long" when the long name is not 8.3.
$shortcuts = @(Read-Table 'SELECT `Name`, `Directory_` FROM `Shortcut`' 2)
$named = @($shortcuts | Where-Object { ($_[0] -split '\|')[-1] -ceq '2by2 MIDI Player' })
if ($shortcuts.Count -ne 2 -or $named.Count -ne 2 -or
    !($named | Where-Object { $_[1] -eq 'ProgramMenuFolder' }) -or !($named | Where-Object { $_[1] -eq 'DesktopFolder' })) {
    throw 'Expected exactly the Start menu and desktop shortcuts named 2by2 MIDI Player'
}
$customActions = @(Read-Table 'SELECT `Source` FROM `CustomAction`' 1)
if ($customActions | Where-Object { $_[0] -eq 'REINSTALL' }) { throw 'Unexpected forced reinstall action' }
$upgrades = @(Read-Table 'SELECT `UpgradeCode`, `VersionMax`, `Attributes`, `ActionProperty` FROM `Upgrade`' 4)
$replacement = @($upgrades | Where-Object { $_[3] -eq 'JP_UPGRADABLE_FOUND' })
$version = Get-Property 'ProductVersion'
if ($replacement.Count -ne 1 -or $replacement[0][0] -ne '{6EF55885-394E-4BB7-B098-44BE9AD975D3}' -or
    $replacement[0][1] -ne $version -or !(([int]$replacement[0][2]) -band 512) -or (([int]$replacement[0][2]) -band 2)) {
    throw 'The MSI must upgrade older and equal versions with the existing UpgradeCode'
}
Write-Output 'Verified names, installer wizard, maintenance dialogs, upgrade rules, file-association candidates, and shortcuts.'
