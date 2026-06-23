import paramiko
import sys

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
try:
    ssh.connect('sfe4-connect.simpfun.cn', port=2046, username='sfe3192491.df7d2f7f', password='5350427807', timeout=30)
    sftp = ssh.open_sftp()
    sftp.put('D:/桌面/源码/mc_plugn/Sdf1_login/target/Sdf1_login.jar', '/plugins/Sdf1_login.jar')
    sftp.close()
    print('SFTP upload success')
except Exception as e:
    print(f'Error: {e}', file=sys.stderr)
    sys.exit(1)
finally:
    ssh.close()
