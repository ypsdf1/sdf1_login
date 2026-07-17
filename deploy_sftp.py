#!/usr/bin/env python3
"""
SFTP deployment script for Minecraft plugin JAR
Uploads JAR to sfe4-connect.simpfun.cn:2046
"""

import paramiko
import os
import sys
from pathlib import Path

# SFTP Configuration
SFTP_HOST = "sfe4-connect.simpfun.cn"
SFTP_PORT = 2046
SFTP_USER = "sfe3192491.df7d2f7f"
SFTP_PASS = "5350427807"

# Remote paths
REMOTE_JAR_PATH = "/plugins/Sdf1_login.jar"

# Local JAR path
LOCAL_JAR_PATH = r"D:\桌面\源码\mc_plugn\Sdf1_login\target\Sdf1_login.jar"

def backup_remote_file(sftp, remote_path):
    """Backup existing file by renaming to .old.N"""
    try:
        # Check if file exists
        sftp.stat(remote_path)
        
        # Find next available .jar.old / .jar.old.N suffix
        # ★ 备份名必须脱离 .jar 后缀（如 Sdf1_login.jar.old / .old.1），
        #   否则 MC 服务器会加载所有 .jar 文件导致插件冲突。
        base, ext = os.path.splitext(remote_path)  # base="/plugins/Sdf1_login", ext=".jar"
        suffix = 1
        while True:
            backup_path = f"{base}{ext}.old{f'.{suffix}' if suffix > 1 else ''}"
            try:
                sftp.stat(backup_path)
                suffix += 1
            except FileNotFoundError:
                # Found available backup name
                sftp.rename(remote_path, backup_path)
                print(f"  Backed up existing file to: {backup_path}")
                return True
    except FileNotFoundError:
        # File doesn't exist, no backup needed
        return True
    except Exception as e:
        print(f"  Warning: Could not backup file: {e}")
        return False

def main():
    print("=" * 60)
    print("SFTP Deployment to sfe4-connect.simpfun.cn")
    print("=" * 60)
    
    # Check if local JAR exists
    if not os.path.exists(LOCAL_JAR_PATH):
        print(f"❌ Local JAR not found: {LOCAL_JAR_PATH}")
        return 1
    
    print(f"\nLocal JAR: {LOCAL_JAR_PATH}")
    print(f"Remote path: {REMOTE_JAR_PATH}")
    
    try:
        # Create SSH client
        print(f"\nConnecting to SFTP: {SFTP_HOST}:{SFTP_PORT}")
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        
        # Connect
        ssh.connect(
            hostname=SFTP_HOST,
            port=SFTP_PORT,
            username=SFTP_USER,
            password=SFTP_PASS,
            timeout=30
        )
        
        # Open SFTP session
        sftp = ssh.open_sftp()
        print("Connected successfully!")
        
        # Backup existing JAR
        print("\nBacking up existing JAR...")
        backup_remote_file(sftp, REMOTE_JAR_PATH)
        
        # Upload new JAR
        print("\nUploading new JAR...")
        sftp.put(LOCAL_JAR_PATH, REMOTE_JAR_PATH)
        print(f"  Uploaded: {REMOTE_JAR_PATH}")
        
        # Verify upload
        remote_stat = sftp.stat(REMOTE_JAR_PATH)
        local_size = os.path.getsize(LOCAL_JAR_PATH)
        
        print(f"\nVerification:")
        print(f"  Local size: {local_size} bytes")
        print(f"  Remote size: {remote_stat.st_size} bytes")
        
        if remote_stat.st_size == local_size:
            print("  ✅ Size match - upload verified!")
        else:
            print("  ⚠️  Size mismatch - upload may be incomplete")
        
        # Close connections
        sftp.close()
        ssh.close()
        
        print("\n" + "=" * 60)
        print("✅ SFTP deployment completed successfully!")
        print("=" * 60)
        
        return 0
        
    except paramiko.AuthenticationException:
        print("\n❌ Authentication failed. Check username/password.")
        return 1
    except paramiko.SSHException as e:
        print(f"\n❌ SSH connection failed: {e}")
        return 1
    except Exception as e:
        print(f"\n❌ Deployment failed: {e}")
        return 1

if __name__ == "__main__":
    sys.exit(main())