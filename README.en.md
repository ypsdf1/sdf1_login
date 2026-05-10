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
This file controls all prompt messages in the plugin. You can modify it to your needs, and then execute `/sdf1_login reload` to reload the plugin.

```txt
# ==========================================
#      Sdf1_login Messages Config
# ==========================================
# Color codes use § symbol
#   §0 black §1 dark blue §2 dark green §3 dark cyan
#   §4 dark red §5 dark purple §6 gold §7 gray
#   §8 dark gray §9 blue §a green §b light blue
#   §c red §d pink §e yellow §f white
#   §l bold §n underline §o italic §r reset
#
# Placeholders use {xxx}, replaced at runtime
# Supports \n or <br> for line breaks
# Execute /sdf1_login reload after editing
# ==========================================

# ---- Login/Registration ----
login_timeout=§cLogin timeout, please login again
not_registered=§eYou are not registered, please use /reg <password>
not_logged_in=§eYou are not logged in, please use /login <password>
already_registered=§cYou have already registered
already_logged_in=§cYou are already logged in
reg_success=§aRegistration successful! Welcome to the server
login_success=§aLogin successful!
login_failed=§cWrong password, please try again

# ---- Password Related ----
password_wrong=§cWrong password
password_changed=§aPassword changed successfully!
password_format_error=§cPassword must be 6+ characters with uppercase, lowercase, and numbers
password_same=§cNew password cannot be the same as old password
reset_no_email=§cYou have not bound an email, cannot recover password
reset_sent=§aTemporary password sent to your email, please check

# ---- Check-in Points ----
checkin_already=§cYou have already checked in today
checkin_success=§aCheck-in successful! Earned {points} points, {streak} consecutive days, multiplier x{multi}
points_insufficient=§cInsufficient points, current: {points}
points_purchase_success=§aPurchase successful! Spent {count} points

# ---- AFK Detection ----
afk_set_enabled=§aAFK detection enabled
afk_set_disabled=§cAFK detection disabled
afk_kick=§cKicked for being AFK too long

# ---- Email Related ----
email_set=§aEmail set successfully: {email}
email_format_error=§cEmail format incorrect

# ---- Invite Related ----
invite_code_generated=§aInvite code: {code}
invite_used=§aInvite successful, inviter: {player}
invite_self=§cCannot use your own invite code
invite_not_found=§cInvite code not found
invite_referral_bonus=§aInvite bonus! Earned {points} points

# ---- New Player Gift ----
gift_not_ready=§cGift conditions not met
gift_claimed=§aGift claimed successfully!

# ---- Approval & IP Limit ----
need_approval=§eRegistration requires approval (Ticket #{id})
ip_max_accounts=§cIP registration limit reached

# ---- Admin Operations ----
admin_delete_confirm=§cPlease type player name in chat to confirm deletion

# ---- Chat Filter ----
chat_muted=§c§l[Sdf1_chat] §fYou are muted, cannot chat
chat_url_blocked=§c§l[Sdf1_chat] §fDetected illegal third-party link: §e{url}
chat_url_violation=§7Current violations: §e{count}
chat_url_admin_notify=§e§l[Sdf1_chat] §f{player} sent illegal link: {url} (count: {count})
chat_url_broadcast=§c§l[Sdf1_chat] §e{player} §fpunished for sending illegal links

# ---- General ----
Unknown parameter=§cUnknown parameter or insufficient permissions
```

### 2.2.2 afk_whitelist.txt
Players under this configuration will not be affected by AFK kick when AFK kick is enabled.

### 2.2.3 SMTP Settings.txt
This file controls the SMTP mail server configuration, used for sending emails when recovering passwords. Can be configured via GUI, or manually edit this config file.

```txt
# ===== SMTP Mail Server Config =====
smtp_address=smtp.example.com
smtp_port=465
smtp_username=mail@example.com
smtp_password=example.com
from_name=Sdf1_login
smtp_encrypt=true
verify_email=example@example.com
```

### 2.2.4 Plugin Settings.txt
This config controls the entire plugin, including how many accounts players can register, default admin panel password, what tags are considered admin, etc.

```txt
# ===== Sdf1_login Plugin Settings =====
admin_tag=admin
admin_password=qweasd
max_accounts_per_ip=3
approval_mode=manual
auto_approval_delay_minutes=30
login_timeout_seconds=60
afk_kick=false
afk_timeout_seconds=300
provider_points_multiplier=2.0
base_economy_reward=100
economy_reward_per_score=50.0
reporter_points_reward=10
```

### 2.2.5 chat.txt
This file controls the chat filter. When players send illegal links, they will be processed automatically according to the config rules.
- warn: Warn
- mute: Mute
- kick: Kick
- ban: Ban
- banip: Ban IP

```txt
# Sdf1_chat Chat Filter Config
notify_admin: true
broadcast: true
mute_duration: 300

whitelist:
*.minecraft.net
baidu.com
github.com

penalty_rules:
1:warn:0
2:warn:0
3:mute:300
5:mute:600
8:kick:0
10:ban:0

whitelist_players:
```

## 3. Commands

- Sdf1_login - main command
- Sdf1_login reload - reload config
- reg - register account
- login - login to account
- l - login to account

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