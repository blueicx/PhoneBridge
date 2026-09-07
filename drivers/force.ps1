Add-Type -TypeDefinition "using System; using System.Runtime.InteropServices; public class DevCon { [DllImport(`"newdev.dll`", SetLastError=true, CharSet=CharSet.Unicode)] public static extern bool UpdateDriverForPlugAndPlayDevices(IntPtr hwndParent, string HardwareId, string FullInfPath, int InstallFlags, ref bool bRebootRequired); }"
$hwId = "USB\VID_0FCE&PID_0DDE"
$reboot = $false
$result = [DevCon]::UpdateDriverForPlugAndPlayDevices([IntPtr]::Zero, $hwId, "C:\Windows\INF\winusb.inf", 0, [ref]$reboot)
Write-Output "Result: $result Error: $([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
pnputil /scan-devices
