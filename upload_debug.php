<?php
// FTP上传脚本
$ftpServer = '43.153.203.46';
$ftpPort = 61868;
$ftpUser = 'qwed';
$ftpPass = 'z22K2Ws8r38a';

$localFile = __DIR__ . '/debug_region_search.php';
$remoteDir = '/caoyuan.ypshidifu.cn/test1/';
$remoteFile = $remoteDir . 'debug_region_search.php';

echo "Connecting to FTP...\n";
$conn = ftp_connect($ftpServer, $ftpPort);
if (!$conn) {
    die("FTP connection failed\n");
}

echo "Logging in...\n";
if (!ftp_login($conn, $ftpUser, $ftpPass)) {
    die("FTP login failed\n");
}

echo "Uploading $localFile to $remoteFile...\n";
if (ftp_put($conn, $remoteFile, $localFile, FTP_BINARY)) {
    echo "Upload successful\n";
} else {
    echo "Upload failed\n";
}

ftp_close($conn);
echo "Done\n";
?>