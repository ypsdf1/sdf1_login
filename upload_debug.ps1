$ftpServer = "43.153.203.46"
$ftpPort = 61868
$ftpUser = "qwed"
$ftpPass = "z22K2Ws8r38a"

$localFile = "D:\桌面\源码\mc_plugn\Web\debug_region_search.php"
$remoteDir = "/caoyuan.ypshidifu.cn/test1/"
$remoteFile = $remoteDir + "debug_region_search.php"

Write-Host "Connecting to FTP..."
$ftpRequest = [System.Net.FtpWebRequest]::Create("ftp://$ftpServer`:$ftpPort$remoteFile")
$ftpRequest.Method = [System.Net.WebRequestMethods+Ftp]::UploadFile
$ftpRequest.Credentials = New-Object System.Net.NetworkCredential($ftpUser, $ftpPass)
$ftpRequest.UseBinary = $true
$ftpRequest.UsePassive = $true

$fileContents = [System.IO.File]::ReadAllBytes($localFile)
$ftpRequest.ContentLength = $fileContents.Length

try {
    $requestStream = $ftpRequest.GetRequestStream()
    $requestStream.Write($fileContents, 0, $fileContents.Length)
    $requestStream.Close()
    $requestStream.Dispose()
    Write-Host "Upload successful"
} catch {
    Write-Host "Upload failed: $_"
}

Write-Host "Done"