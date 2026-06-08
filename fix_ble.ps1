$f = "d:\prys\esp32\android-app\app\src\main\java\com\twister\bridge\ble\BleLinkManager.kt"
$tmp = "d:\prys\esp32\BleLinkManager_tmp.kt"
$lines = Get-Content $f
$lines | Select-Object -First 231 | Set-Content $tmp -Encoding UTF8
Copy-Item -Force $tmp $f
Remove-Item $tmp
Write-Host "Done. Lines: $((Get-Content $f).Count)"
