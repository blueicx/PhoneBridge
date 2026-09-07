$hwId = "USB\VID_0FCE&PID_0DDE"
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public class DevInstall {
    [DllImport("newdev.dll", SetLastError=true, CharSet=CharSet.Unicode)]
    public static extern bool UpdateDriverForPlugAndPlayDevicesW(
        IntPtr hwndParent,
        string HardwareId,
        string FullInfPath,
        uint InstallFlags,
        out bool bRebootRequired);
}
"@
$reboot = [ref]$false
$result = [DevInstall]::UpdateDriverForPlugAndPlayDevicesW([IntPtr]::Zero, $hwId, "C:\Users\blueice\AppData\Local\Temp\usb_driver_ext\usb_driver\sony_bootloader.inf", 1, $reboot)
Write-Output "Result=$result Err=$([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
pnputil /scan-devices
