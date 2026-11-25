<#
.SYNOPSIS
    Boot-time Ollama LAN server for Android clients.

.DESCRIPTION
    Run once as Administrator with -Install. That copies this script to
    C:\Ollama4School and registers a Scheduled Task that runs it at every boot
    as SYSTEM (no user login needed).

    On every boot the script:
      1. Downloads/extracts the standalone Ollama build to C:\Ollama if missing
      2. Opens TCP 11434 in Windows Firewall and allows mDNS (UDP 5353)
      3. Starts `ollama serve` bound to 0.0.0.0:11434 (LAN-reachable)
      4. Pulls the model (default lfm2.5:8b) and preloads it into memory
      5. Advertises the server on the LAN via mDNS / DNS-SD:
           - service  OLLAMA_SERVER._ollama._tcp.local  (port 11434, TXT: path, model, url)
             pointing at <COMPUTERNAME>.local, which Windows answers with the current IP
         Android apps find it with NsdManager (type "_ollama._tcp"), e.g. through the
         Ollama4School Java library in the android\ folder.
      6. Re-advertises whenever the DHCP address changes, restarts Ollama if it dies.

.EXAMPLE
    # One-time setup (elevated PowerShell):
    powershell -ExecutionPolicy Bypass -File .\OllamaServer.ps1 -Install

.EXAMPLE
    # Different model / name:
    powershell -ExecutionPolicy Bypass -File .\OllamaServer.ps1 -Install -Model lfm2.5:24b -ServiceName CLASSROOM_AI

.EXAMPLE
    # Remove the boot task and firewall rules (keeps C:\Ollama and models):
    powershell -ExecutionPolicy Bypass -File .\OllamaServer.ps1 -Uninstall
#>
[CmdletBinding()]
param(
    [switch]$Install,
    [switch]$Uninstall,
    [string]$Model       = 'lfm2.5:8b',
    [string]$ServiceName = "OLLAMA_SERVER-$env:COMPUTERNAME",  # DNS-SD instance name, unique per PC
    [int]   $Port        = 11434,
    [string]$OllamaDir   = 'C:\Ollama',
    [string]$InstallDir  = 'C:\Ollama4School',
    [bool]  $SetPrivateNetwork = $true       # mDNS is blocked on "Public" networks
)

$ErrorActionPreference = 'Stop'
$TaskName   = 'Ollama4School'
$OllamaExe  = Join-Path $OllamaDir 'ollama.exe'
$ModelsDir  = Join-Path $OllamaDir 'models'
$LogFile    = Join-Path $InstallDir 'ollama4school.log'
$ZipUrl     = 'https://github.com/ollama/ollama/releases/latest/download/ollama-windows-amd64.zip'

function Write-Log([string]$Message) {
    $line = '{0:yyyy-MM-dd HH:mm:ss}  {1}' -f (Get-Date), $Message
    Write-Host $line
    if (-not (Test-Path $InstallDir)) { New-Item -ItemType Directory -Force $InstallDir | Out-Null }
    # Open with ReadWrite sharing so a viewer tailing the log can't block writes
    for ($i = 0; $i -lt 5; $i++) {
        try {
            $fs = [IO.File]::Open($LogFile, 'Append', 'Write', 'ReadWrite')
            $bytes = [Text.Encoding]::UTF8.GetBytes($line + "`r`n")
            $fs.Write($bytes, 0, $bytes.Length); $fs.Close(); return
        } catch { Start-Sleep -Milliseconds 200 }
    }
}

function Assert-Admin {
    $id = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
    if (-not $id.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Run this script from an elevated (Administrator) PowerShell.'
    }
}

# ---------------------------------------------------------------------------
# mDNS / DNS-SD advertisement through the built-in Windows DNS-SD API (dnsapi.dll)
# ---------------------------------------------------------------------------
$mdnsSource = @'
using System;
using System.Runtime.InteropServices;

public static class MdnsAdvertiser
{
    [UnmanagedFunctionPointer(CallingConvention.Winapi)]
    public delegate void RegisterCompleteCallback(uint status, IntPtr context, IntPtr instance);

    [StructLayout(LayoutKind.Sequential)]
    private struct DNS_SERVICE_REGISTER_REQUEST
    {
        public uint   Version;
        public uint   InterfaceIndex;
        public IntPtr pServiceInstance;
        public IntPtr pRegisterCompletionCallback;
        public IntPtr pQueryContext;
        public IntPtr hCredentials;
        public int    unicastEnabled;
    }

    [DllImport("dnsapi.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr DnsServiceConstructInstance(
        string pServiceName, string pHostName, ref uint pIp4, IntPtr pIp6,
        ushort wPort, ushort wPriority, ushort wWeight, uint dwPropertiesCount,
        [MarshalAs(UnmanagedType.LPArray, ArraySubType = UnmanagedType.LPWStr)] string[] keys,
        [MarshalAs(UnmanagedType.LPArray, ArraySubType = UnmanagedType.LPWStr)] string[] values);

    [DllImport("dnsapi.dll")]
    private static extern void DnsServiceFreeInstance(IntPtr pInstance);

    [DllImport("dnsapi.dll")]
    private static extern uint DnsServiceRegister(ref DNS_SERVICE_REGISTER_REQUEST pRequest, IntPtr pCancel);

    [DllImport("dnsapi.dll")]
    private static extern uint DnsServiceDeRegister(ref DNS_SERVICE_REGISTER_REQUEST pRequest, IntPtr pCancel);

    private const uint DNS_REQUEST_PENDING = 9506;

    // Kept in static fields so the GC never collects them while Windows holds the pointers.
    private static RegisterCompleteCallback _callback = OnComplete;
    private static IntPtr _instance = IntPtr.Zero;
    private static DNS_SERVICE_REGISTER_REQUEST _request;
    public static uint LastStatus = uint.MaxValue;

    private static void OnComplete(uint status, IntPtr context, IntPtr instance)
    {
        LastStatus = status;
        if (instance != IntPtr.Zero) DnsServiceFreeInstance(instance);
    }

    public static uint Register(string serviceName, string hostName, byte[] ipv4, ushort port,
                                string[] keys, string[] values)
    {
        Unregister();
        uint ip = BitConverter.ToUInt32(ipv4, 0); // bytes are already in network order
        _instance = DnsServiceConstructInstance(serviceName, hostName, ref ip, IntPtr.Zero,
                                                port, 0, 0, (uint)keys.Length, keys, values);
        if (_instance == IntPtr.Zero) return 1;

        _request = new DNS_SERVICE_REGISTER_REQUEST();
        _request.Version = 1;
        _request.InterfaceIndex = 0;
        _request.pServiceInstance = _instance;
        _request.pRegisterCompletionCallback = Marshal.GetFunctionPointerForDelegate(_callback);

        uint rc = DnsServiceRegister(ref _request, IntPtr.Zero);
        return rc == DNS_REQUEST_PENDING ? 0 : rc;
    }

    public static void Unregister()
    {
        if (_instance == IntPtr.Zero) return;
        try { DnsServiceDeRegister(ref _request, IntPtr.Zero); } catch { }
        _instance = IntPtr.Zero;
    }
}
'@

function Get-LanIPv4 {
    # The IPv4 address of the adapter that holds the default route (i.e. the LAN/DHCP address).
    $route = Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue |
             Sort-Object { $_.RouteMetric + $_.InterfaceMetric } | Select-Object -First 1
    if (-not $route) { return $null }
    Get-NetIPAddress -InterfaceIndex $route.InterfaceIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -notlike '169.254.*' } |
        Select-Object -First 1 -ExpandProperty IPAddress
}

function Register-Mdns([string]$Ip) {
    if (-not ('MdnsAdvertiser' -as [type])) { Add-Type -TypeDefinition $mdnsSource -Language CSharp }
    $bytes  = [System.Net.IPAddress]::Parse($Ip).GetAddressBytes()
    $keys   = [string[]]@('path', 'model', 'url')
    $values = [string[]]@('/api', $Model, "http://${Ip}:$Port")
    # The SRV record must point at the PC's real name: Windows only answers mDNS address
    # queries for its own computer name, so a made-up host name would never resolve.
    $hostFqdn = "$env:COMPUTERNAME.local".ToLower()
    $rc = [MdnsAdvertiser]::Register("$ServiceName._ollama._tcp.local", $hostFqdn,
                                     $bytes, [uint16]$Port, $keys, $values)
    if ($rc -eq 0) {
        Write-Log "mDNS: advertising $ServiceName._ollama._tcp -> ${hostFqdn}:$Port ($Ip)"
    } else {
        Write-Log "mDNS: DnsServiceRegister failed with code $rc (clients can still use http://${Ip}:$Port)"
    }
}

# ---------------------------------------------------------------------------
# Setup pieces (all idempotent)
# ---------------------------------------------------------------------------
function Install-Ollama {
    if (Test-Path $OllamaExe) { return }
    Write-Log "Ollama not found - downloading standalone build to $OllamaDir"
    New-Item -ItemType Directory -Force $OllamaDir | Out-Null
    $zip = Join-Path $env:TEMP 'ollama-windows-amd64.zip'

    # Wait for network (at boot it may not be up yet)
    for ($i = 0; $i -lt 60 -and -not (Test-Connection github.com -Count 1 -Quiet); $i++) { Start-Sleep 5 }

    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $ProgressPreference = 'SilentlyContinue'   # makes Invoke-WebRequest dramatically faster
    Invoke-WebRequest -Uri $ZipUrl -OutFile $zip -UseBasicParsing
    Expand-Archive -Path $zip -DestinationPath $OllamaDir -Force
    Remove-Item $zip -Force -ErrorAction SilentlyContinue
    if (-not (Test-Path $OllamaExe)) { throw "ollama.exe not found in $OllamaDir after extraction" }
    Write-Log "Ollama installed to $OllamaDir"
}

function Set-NetworkAccess {
    if (-not (Get-NetFirewallRule -DisplayName 'Ollama4School API' -ErrorAction SilentlyContinue)) {
        New-NetFirewallRule -DisplayName 'Ollama4School API' -Direction Inbound -Protocol TCP `
            -LocalPort $Port -Action Allow -Profile Any | Out-Null
        Write-Log "Firewall: opened TCP $Port"
    }
    if (-not (Get-NetFirewallRule -DisplayName 'Ollama4School mDNS' -ErrorAction SilentlyContinue)) {
        New-NetFirewallRule -DisplayName 'Ollama4School mDNS' -Direction Inbound -Protocol UDP `
            -LocalPort 5353 -Action Allow -Profile Any | Out-Null
        Write-Log 'Firewall: opened UDP 5353 (mDNS)'
    }
    if ($SetPrivateNetwork) {
        Get-NetConnectionProfile -ErrorAction SilentlyContinue |
            Where-Object { $_.NetworkCategory -eq 'Public' } |
            ForEach-Object {
                Set-NetConnectionProfile -InterfaceIndex $_.InterfaceIndex -NetworkCategory Private
                Write-Log "Network '$($_.Name)' switched from Public to Private"
            }
    }
}

function Start-OllamaProcess {
    $env:OLLAMA_HOST       = "0.0.0.0:$Port"  # listen on all interfaces, not just localhost
    $env:OLLAMA_ORIGINS    = '*'              # allow WebView / browser-based clients too
    $env:OLLAMA_MODELS     = $ModelsDir
    $env:OLLAMA_KEEP_ALIVE = '-1'             # keep the model loaded in memory forever
    New-Item -ItemType Directory -Force $ModelsDir | Out-Null

    Get-Process ollama, 'ollama app' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    $proc = Start-Process -FilePath $OllamaExe -ArgumentList 'serve' -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $InstallDir 'ollama-serve.out.log') `
        -RedirectStandardError  (Join-Path $InstallDir 'ollama-serve.err.log')

    for ($i = 0; $i -lt 60; $i++) {
        try { Invoke-RestMethod "http://127.0.0.1:$Port/api/version" -TimeoutSec 2 | Out-Null; break }
        catch { Start-Sleep 1 }
    }
    Write-Log "ollama serve started (PID $($proc.Id)) on 0.0.0.0:$Port"
    $proc
}

function Initialize-Model {
    $tags = Invoke-RestMethod "http://127.0.0.1:$Port/api/tags"
    if ($tags.models.name -notcontains $Model) {
        Write-Log "Pulling $Model (first run only, this can take a while)..."
        # Pull through the REST API: the CLI writes progress to stderr, which PowerShell 5.1
        # turns into terminating errors under $ErrorActionPreference = 'Stop'.
        # Interrupted downloads resume from where they stopped.
        $pulled = $false
        for ($attempt = 1; $attempt -le 20 -and -not $pulled; $attempt++) {
            try {
                $r = Invoke-RestMethod "http://127.0.0.1:$Port/api/pull" -Method Post -TimeoutSec 7200 `
                        -Body (@{ model = $Model; stream = $false } | ConvertTo-Json)
                $pulled = $r.status -eq 'success'
            } catch {
                Write-Log "Pull attempt $attempt failed ($($_.Exception.Message)), retrying in 30s"
                Start-Sleep 30
            }
        }
        if (-not $pulled) { throw "Could not pull $Model" }
    }
    # Load into memory now so the first Android request isn't slow
    Invoke-RestMethod "http://127.0.0.1:$Port/api/generate" -Method Post -TimeoutSec 600 `
        -Body (@{ model = $Model; prompt = ''; keep_alive = -1 } | ConvertTo-Json) | Out-Null
    Write-Log "$Model is ready"
}

# ---------------------------------------------------------------------------
# -Install / -Uninstall
# ---------------------------------------------------------------------------
if ($Install) {
    Assert-Admin
    New-Item -ItemType Directory -Force $InstallDir | Out-Null
    $target = Join-Path $InstallDir 'OllamaServer.ps1'
    if ($PSCommandPath -ne $target) { Copy-Item $PSCommandPath $target -Force }

    $taskArgs = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$target`" " +
                "-Model `"$Model`" -ServiceName `"$ServiceName`" " +
                "-Port $Port -OllamaDir `"$OllamaDir`" -InstallDir `"$InstallDir`""
    $action    = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $taskArgs
    $trigger   = New-ScheduledTaskTrigger -AtStartup
    $principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
    # Priority 4 = normal; the default (7) runs Ollama at "below normal" CPU priority
    $settings  = New-ScheduledTaskSettingsSet -Priority 4 -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
                    -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 `
                    -RestartInterval (New-TimeSpan -Minutes 1) -StartWhenAvailable
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Principal $principal `
        -Settings $settings -Force | Out-Null
    Write-Log "Scheduled task '$TaskName' registered (runs at boot as SYSTEM)"

    Set-NetworkAccess
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue   # replace a running old copy
    Start-ScheduledTask -TaskName $TaskName
    Write-Log "Task started now. Progress: $LogFile"
    return
}

if ($Uninstall) {
    Assert-Admin
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    Get-NetFirewallRule -DisplayName 'Ollama4School*' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    Get-Process ollama -ErrorAction SilentlyContinue | Stop-Process -Force
    Write-Log "Uninstalled task and firewall rules. Delete $OllamaDir manually to remove Ollama and models."
    return
}

# ---------------------------------------------------------------------------
# Boot run: install, serve, advertise, then supervise forever
# ---------------------------------------------------------------------------
try {
    Write-Log '=== Ollama4School starting ==='
    Install-Ollama
    Set-NetworkAccess
    $proc = Start-OllamaProcess
    Initialize-Model

    # Keep the PC from sleeping while it serves (screen may still turn off). Lasts while this
    # task runs; Windows power settings are left unchanged.
    Add-Type -Namespace Win32 -Name Power -MemberDefinition `
        '[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint flags);'
    $ES_CONTINUOUS = [uint32]'0x80000000'; $ES_SYSTEM_REQUIRED = [uint32]1
    [void][Win32.Power]::SetThreadExecutionState($ES_CONTINUOUS -bor $ES_SYSTEM_REQUIRED)
    Write-Log 'Sleep disabled while serving'

    $currentIp = $null
    while ($true) {
        if ($proc.HasExited) {
            Write-Log "ollama serve exited (code $($proc.ExitCode)) - restarting"
            $proc = Start-OllamaProcess
            Initialize-Model
        }
        $ip = Get-LanIPv4
        if ($ip -and $ip -ne $currentIp) {
            Write-Log "LAN address: http://${ip}:$Port"
            Register-Mdns $ip
            $currentIp = $ip
        }
        Start-Sleep 30
    }
}
catch {
    Write-Log "ERROR: $($_.Exception.Message)"
    exit 1   # the scheduled task's restart policy will try again in 1 minute
}
