#!/usr/bin/env python3
"""
PHP only deployment script for caoyuan.ypshidifu.cn
Uploads PHP files via FTP to /plugin directory
"""

import ftplib
import os
import sys
from pathlib import Path

# FTP Configuration
FTP_HOST = "43.153.203.46"
FTP_PORT = 38367
FTP_USER = "zxcvb"
FTP_PASS = "32RPNtkKF68j"
FTP_REMOTE_BASE = "/plugin"

# Local PHP directory
PHP_DIR = r"D:\桌面\源码\mc_plugn\Web"

# Files/directories to exclude from upload
EXCLUDE_PATTERNS = [
    'db/',
    'db\\',
    '.git',
    '.gitignore',
    'deploy_*.py',
    '__pycache__',
    '*.pyc',
    '密钥*.txt',
    '密钥*.md',
    '*.bak',
    'node_modules',
    'vendor',
]

def should_exclude(path):
    """Check if file should be excluded from upload"""
    for pattern in EXCLUDE_PATTERNS:
        if pattern in path:
            return True
    return False

def upload_file(ftp, local_path, remote_path):
    """Upload a single file via FTP"""
    try:
        with open(local_path, 'rb') as f:
            ftp.storbinary(f'STOR {remote_path}', f)
        print(f"  Uploaded: {remote_path}")
        return True
    except Exception as e:
        print(f"  Failed: {remote_path} - {e}")
        return False

def fix_root_config_db_path(config_path):
    """Fix DB_PATH in root config.php to point to /plugin/db/web.db"""
    try:
        with open(config_path, 'r', encoding='utf-8') as f:
            content = f.read()
        
        # Check if DB_PATH needs fixing
        if 'DB_PATH' in content and '/plugin/db/web.db' not in content:
            # Replace DB_PATH with correct path
            import re
            new_content = re.sub(
                r"define\s*\(\s*['\"]DB_PATH['\"]\s*,\s*['\"][^'\"]*['\"]\s*\)",
                "define('DB_PATH', '/plugin/db/web.db')",
                content
            )
            if new_content != content:
                with open(config_path, 'w', encoding='utf-8') as f:
                    f.write(new_content)
                print(f"Fixed DB_PATH in {config_path}")
    except Exception as e:
        print(f"Warning: Could not fix DB_PATH in {config_path}: {e}")

def main():
    print("=" * 60)
    print("PHP Deployment to caoyuan.ypshidifu.cn")
    print("=" * 60)
    
    # Fix root config.php DB_PATH
    root_config = os.path.join(PHP_DIR, 'config.php')
    if os.path.exists(root_config):
        fix_root_config_db_path(root_config)
    
    # Connect to FTP
    try:
        print(f"\nConnecting to FTP: {FTP_HOST}:{FTP_PORT}")
        ftp = ftplib.FTP()
        ftp.connect(FTP_HOST, FTP_PORT)
        ftp.login(FTP_USER, FTP_PASS)
        print("Connected successfully!")
        
        # Ensure remote base directory exists
        try:
            ftp.cwd(FTP_REMOTE_BASE)
        except:
            try:
                ftp.mkd(FTP_REMOTE_BASE)
                ftp.cwd(FTP_REMOTE_BASE)
                print(f"Created remote directory: {FTP_REMOTE_BASE}")
            except Exception as e:
                print(f"Warning: Could not create remote directory: {e}")
        
        # Walk through local directory and upload files
        uploaded = 0
        skipped = 0
        failed = 0
        
        for root, dirs, files in os.walk(PHP_DIR):
            # Skip excluded directories
            dirs[:] = [d for d in dirs if not should_exclude(os.path.join(root, d))]
            
            for file in files:
                local_path = os.path.join(root, file)
                
                # Skip excluded files
                if should_exclude(local_path):
                    skipped += 1
                    continue
                
                # Calculate relative path
                rel_path = os.path.relpath(local_path, PHP_DIR)
                remote_path = os.path.join(FTP_REMOTE_BASE, rel_path).replace('\\', '/')
                
                # Create remote directory if needed
                remote_dir = os.path.dirname(remote_path)
                try:
                    ftp.cwd(remote_dir)
                except:
                    try:
                        ftp.mkd(remote_dir)
                        ftp.cwd(remote_dir)
                    except:
                        pass
                
                # Upload file
                if upload_file(ftp, local_path, remote_path):
                    uploaded += 1
                else:
                    failed += 1
        
        # Close connection
        ftp.quit()
        
        print("\n" + "=" * 60)
        print("Deployment Summary:")
        print(f"  Uploaded: {uploaded} files")
        print(f"  Skipped: {skipped} files")
        print(f"  Failed: {failed} files")
        print("=" * 60)
        
        if failed == 0:
            print("\n✅ PHP deployment completed successfully!")
        else:
            print(f"\n⚠️  Deployment completed with {failed} failures")
            return 1
        
        return 0
        
    except Exception as e:
        print(f"\n❌ FTP connection failed: {e}")
        return 1

if __name__ == "__main__":
    sys.exit(main())