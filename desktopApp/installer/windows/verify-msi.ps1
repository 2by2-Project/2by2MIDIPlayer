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
$features = @(Read-Table 'SELECT `Feature` FROM `Feature`' 1)
if ($features | Where-Object { $_[0] -match 'Association' }) { throw 'Unexpected file-association feature' }
$registry = @(Read-Table 'SELECT `Key` FROM `Registry`' 1)
if ($registry | Where-Object { $_[0] -match 'UserChoice|OpenWithProgids|SupportedTypes|RegisteredApplications|Capabilities|Software\\Classes' }) {
    throw 'The MSI must not register file associations'
}
$customActions = @(Read-Table 'SELECT `Source` FROM `CustomAction`' 1)
if ($customActions | Where-Object { $_[0] -eq 'REINSTALL' }) { throw 'Unexpected forced reinstall action' }
$upgrades = @(Read-Table 'SELECT `UpgradeCode`, `VersionMax`, `Attributes`, `ActionProperty` FROM `Upgrade`' 4)
$replacement = @($upgrades | Where-Object { $_[3] -eq 'JP_UPGRADABLE_FOUND' })
$version = ($properties | Where-Object { $_[0] -eq 'ProductVersion' })[1]
if ($replacement.Count -ne 1 -or $replacement[0][0] -ne '{6EF55885-394E-4BB7-B098-44BE9AD975D3}' -or
    $replacement[0][1] -ne $version -or !(([int]$replacement[0][2]) -band 512) -or (([int]$replacement[0][2]) -band 2)) {
    throw 'The MSI must upgrade older and equal versions with the existing UpgradeCode'
}
Write-Output 'Verified installer wizard, maintenance dialogs, upgrade rules, and absence of file associations.'
