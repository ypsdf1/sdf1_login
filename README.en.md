# Sdf1_login Plugin User Guide

## 1. Features

| Name | Description/Function | Status |
| :--- | :--- | :--- |
| Login | User login/registration | Completed |
| World Trash Can | Clear server drops | In Progress |
| Ticket System | User ticket submission | Completed |
| Check-in System | Check-in points for rewards | Completed |

## 2. Installation

| Prerequisite | Optional |
| :--- | :--- |
| CY_beibao | Optional |
| EssentialsX | Optional |

### 2.1 Download
You can download the plugin from GitHub and Gitee, drop it into the server's plugin directory, and restart the server.

### 2.2 Configuration Files

#### 2.2.1 messages.txt
This file controls all prompt messages in the plugin. You can modify it to your needs, and then execute /sdf1_login reload to reload the plugin.

#### 2.2.2 afk_whitelist.txt
Players under this configuration will not be affected by AFK kick when AFK kick is enabled.

#### 2.2.3 SMTP Settings.txt
This file controls the SMTP mail server configuration, used for sending emails when recovering passwords.

#### 2.2.4 Plugin Settings.txt
This config controls the entire plugin, including how many accounts players can register, default admin panel password, what tags are considered admin, etc.

#### 2.2.5 chat.txt
This file controls the chat filter. When players send illegal links, they will be processed automatically according to the config rules.

## 3. Commands

- Sdf1_login main command
- Sdf1_login reload reload config
- reg register account
- login login to account
- l login to account

## 4. Screenshots

Main Interface
![img/login.png]

Invite Data
![img/login2.png]

Points Shop
![img/login3.png]

Task Center
![img/login4.png]

Trash Can
![img/login5.png]

Admin Panel
![img/login6.png]

Admin Panel - User Management
![img/login7.png]

Ticket Center
![img/login8.png]

Ticket Management
![img/login9.png]

My Tickets
![img/login10.png]

Submit Ticket
![img/login11.png]

Provider List
![img/login12.png]

Provider Grab Hall
![img/login13.png]

Provider - Order Interface
![img/login14.png]

Admin - Ticket Detail Page
![img/login15.png]

## 5. Download Links
- GitHub: https://github.com/ypsdf1/sdf1_login
- Gitee: https://gitee.com/nihaoshidifu/sdf1_login
