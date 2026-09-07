$devId = "USB\VID_0FCE&PID_0DDE\QV7017NH1F"
# Remove the device so Windows re-enumerates it
pnputil /remove-device "$devId" 2>$null
# Add winusb.inf as a driver for this specific hardware
$infPath = "C:\Windows\INF\winusb.inf"
# Use devcon-style approach: assign via registry  
$enumPath = "HKLM:\SYSTEM\CurrentControlSet\Enum\$devId"
Set-ItemProperty -Path $enumPath -Name "ConfigFlags" -Value 0 -Type DWord
New-ItemProperty -Path $enumPath -Name "ClassGUID" -PropertyType String -Value "{88bae032-5a81-49f0-bc3d-a4ff138216d6}" -Force
New-ItemProperty -Path $enumPath -Name "Class" -PropertyType String -Value "AndroidUsbDeviceClass" -Force
pnputil /scan-devices
Start-Sleep 3
Get-PnpDevice | Where-Object { $_.InstanceId -like "*0DDE*" } | Format-List Status,FriendlyName
