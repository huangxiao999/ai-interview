# One-time Windows local-maintenance setup. Changes only this checkout's root ACL;
# existing child files inherit the protected root. Inspect the script before running.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path -LiteralPath (Split-Path $PSScriptRoot -Parent)).ProviderPath
$operator = [Security.Principal.WindowsIdentity]::GetCurrent()
$current = Get-Acl -LiteralPath $projectRoot
if ($current.Owner -ne $operator.Name) {
    throw 'Only the current checkout owner can protect this workspace.'
}
foreach ($path in @('.env', 'scripts/Provision-TrialAccount.ps1',
        'backend/target/interview-coach-backend-0.0.1-SNAPSHOT.jar')) {
    if (-not (Test-Path -LiteralPath (Join-Path $projectRoot $path) -PathType Leaf)) {
        throw "Required provisioning file is missing: $path"
    }
}

$checkedPaths = @('.', '.env', 'backend', 'backend/target',
    'backend/target/interview-coach-backend-0.0.1-SNAPSHOT.jar',
    'scripts', 'scripts/Provision-TrialAccount.ps1')
$alreadyPrivate = $current.AreAccessRulesProtected
foreach ($path in $checkedPaths) {
    $acl = Get-Acl -LiteralPath (Join-Path $projectRoot $path)
    $unexpected = @($acl.Access | Where-Object {
            $_.AccessControlType -eq 'Allow' -and
            $_.IdentityReference.Value -notin @($operator.Name, 'BUILTIN\Administrators',
                'NT AUTHORITY\SYSTEM')
        })
    if ($acl.Owner -ne $operator.Name -or $unexpected.Count -ne 0) {
        $alreadyPrivate = $false
    }
}
if ($alreadyPrivate) {
    Write-Output 'This project checkout already has a private provisioning ACL.'
    return
}

$cache = Join-Path $projectRoot '.cache'
New-Item -ItemType Directory -Path $cache -Force | Out-Null
$backup = Join-Path $cache ('dt02-root-acl-before-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.sddl')
Set-Content -LiteralPath $backup -Value $current.Sddl -Encoding UTF8

$current.SetAccessRuleProtection($true, $false)
foreach ($entry in @($current.Access)) {
    $current.RemoveAccessRuleSpecific($entry) | Out-Null
}
$inheritance = [Security.AccessControl.InheritanceFlags]::ContainerInherit -bor
    [Security.AccessControl.InheritanceFlags]::ObjectInherit
foreach ($principal in @($operator.User,
        ([Security.Principal.SecurityIdentifier]'S-1-5-32-544'),
        ([Security.Principal.SecurityIdentifier]'S-1-5-18'))) {
    $rule = [Security.AccessControl.FileSystemAccessRule]::new($principal,
        [Security.AccessControl.FileSystemRights]::FullControl, $inheritance,
        [Security.AccessControl.PropagationFlags]::None,
        [Security.AccessControl.AccessControlType]::Allow)
    $current.AddAccessRule($rule)
}
Set-Acl -LiteralPath $projectRoot -AclObject $current

foreach ($path in $checkedPaths) {
    $acl = Get-Acl -LiteralPath (Join-Path $projectRoot $path)
    $unexpected = @($acl.Access | Where-Object {
            $_.AccessControlType -eq 'Allow' -and
            $_.IdentityReference.Value -notin @($operator.Name, 'BUILTIN\Administrators',
                'NT AUTHORITY\SYSTEM')
        })
    if ($acl.Owner -ne $operator.Name -or $unexpected.Count -ne 0) {
        throw "Provisioning ACL remains too broad: $path. Review the saved ACL at $backup."
    }
}
Write-Output 'This project checkout is restricted to its current owner, Administrators, and SYSTEM.'
Write-Output "Previous root ACL saved in ignored local cache: $backup"
