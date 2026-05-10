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
This file controls all prompt messages in the plugin. You can modify it to your needs, and then execute `/sdf1_login reload` to reload the plugin. Supports [color codes](https://zh.minecraft.wiki/w/%E6%A0%BC%E5%BC%8F%E5%8C%96%E4%BB%A3%E7%A0%81)<br>
Also compatible with `<b> </b>` tags for bolding paragraphs, and `\n` or `<br>` for line breaks.

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
# Supports <b>bold</b> tags
# Supports \n or <br> for line breaks
# Execute /sdf1_login reload after editing
# ==========================================

# ---- Login/Registration ----
# login_timeout: Kick message on login timeout
login_timeout=§cLogin timeout, please login again
# not_registered: Message for unregistered players
not_registered=§eYou are not registered, please use /reg <password>
# not_logged_in: Message for registered but not logged in players
not_logged_in=§eYou are not logged in, please use /login <password>
# already_registered: Message when registered player registers again
already_registered=§cYou have already registered
# already_logged_in: Message when logged in player logs in again
already_logged_in=§cYou are already logged in
# reg_success: Registration success message
reg_success=§aRegistration successful! Welcome to the server
# login_success: Login success message
login_success=§aLogin successful!
# login_failed: Wrong password message
login_failed=§cWrong password, please try again

# ---- Password Related ----
# password_wrong: Old password verification failed
password_wrong=§cWrong password
# password_changed: Password change success
password_changed=§aPassword changed successfully!
# password_format_error: Password format does not meet requirements
password_format_error=§cPassword must be 6+ characters with uppercase, lowercase, and numbers
# password_same: New password same as old password
password_same=§cNew password cannot be the same as old password
# reset_no_email: No email bound when recovering password
reset_no_email=§cYou have not bound an email, cannot recover password
# reset_sent: Temporary password sent to email
reset_sent=§aTemporary password sent to your email, please check

# ---- Check-in Points ----
# checkin_already: Already checked in today
checkin_already=§cYou have already checked in today
# checkin_success: Check-in success, {points}=points earned {streak}=consecutive days {multi}=multiplier
checkin_success=§aCheck-in successful! Earned {points} points, {streak} consecutive days, multiplier x{multi}
# points_insufficient: Insufficient points
points_insufficient=§cInsufficient points, current: {points}
# points_purchase_success: Points purchase success
points_purchase_success=§aPurchase successful! Spent {count} points

# ---- AFK Detection ----
# afk_set_enabled: AFK detection enabled
afk_set_enabled=§aAFK detection enabled
# afk_set_disabled: AFK detection disabled
afk_set_disabled=§cAFK detection disabled
# afk_kick: Kicked for AFK
afk_kick=§cKicked for being AFK too long

# ---- Email Related ----
# email_set: Email set successfully
email_set=§aEmail set successfully: {email}
# email_format_error: Email format error
email_format_error=§cEmail format incorrect

# ---- Invite Related ----
# invite_code_generated: Invite code generated
invite_code_generated=§aInvite code: {code}
# invite_used: Invite code used successfully
invite_used=§aInvite successful, inviter: {player}
# invite_self: Using own invite code
invite_self=§cCannot use your own invite code
# invite_not_found: Invite code not found
invite_not_found=§cInvite code not found
# invite_referral_bonus: Inviter gets bonus when invitee checks in
invite_referral_bonus=§aInvite bonus! Earned {points} points

# ---- New Player Gift ----
# gift_not_ready: Gift conditions not met
gift_not_ready=§cGift conditions not met
# gift_claimed: Gift claimed successfully
gift_claimed=§aGift claimed successfully!

# ---- Approval & IP Limit ----
# need_approval: Registration requires approval
need_approval=§eRegistration requires approval (Ticket #{id})
# ip_max_accounts: IP registration limit reached
ip_max_accounts=§cIP registration limit reached

# ---- Admin Operations ----
# admin_delete_confirm: Admin delete account confirmation
admin_delete_confirm=§cPlease type player name in chat to confirm deletion

# ---- Chat Filter ----
# chat_muted: Muted player tries to chat
chat_muted=§c§l[Sdf1_chat] §fYou are muted, cannot chat
# chat_url_blocked: Blocked sending illegal link
# {url}=detected link address
chat_url_blocked=§c§l[Sdf1_chat] §fDetected illegal third-party link: §e{url}
# chat_url_violation: Current violation count
# {count}=current violation count
chat_url_violation=§7Current violations: §e{count}
# chat_url_admin_notify: Notify admin, {player}=player name {url}=link {count}=count
chat_url_admin_notify=§e§l[Sdf1_chat] §f{player} sent illegal link: {url} (count: {count})
# chat_url_broadcast: Server broadcast, {player}=player name
chat_url_broadcast=§c§l[Sdf1_chat] §e{player} §fpunished for sending illegal links

# ---- General ----
# Unknown parameter: Unknown command or insufficient permissions
Unknown parameter=§cUnknown parameter or insufficient permissions
# only_player: Command only for players
# only_player=§cThis command is only for players

```

### 2.2.2 afk_whitelist.txt
Players under this configuration will not be affected by AFK kick when AFK kick is enabled.

### 2.2.3 SMTP Settings.txt

This file controls the SMTP mail server configuration, used for sending emails when recovering passwords.<br>
Can be configured via GUI, or manually edit this config file. Reload the plugin after modifying.

```txt
# ===== SMTP Mail Server Config =====
smtp地址=smtp.example.com
smtp端口=465
smtp账号=mail@example.com
smtp密码=example.com
发件人名称=Sdf1_login
smtp加密=true
验证码接收邮箱=example@example.com

```

### 2.2.4 Plugin Settings.txt

This config controls the entire plugin, including how many accounts players can register, default admin panel password, what tags are considered admin, etc.

```txt
# ===== Sdf1_login Plugin Settings =====
管理标签=admin
管理密码=qweasd 
每IP最大账号数=3
审批模式=manual
自动审批延迟分钟=30
登录超时秒数=60
挂机踢出=false
挂机超时秒数=300
服务商积分倍率=2.0
基础经济奖励=100
经济奖励每评分=50.0
报单人积分奖励=10

```

### 2.2.5 chat.txt
This file controls the chat filter. When players send illegal links, they will be processed automatically according to the config rules.<br>
Available penalty rules:
- warn: Warn
- mute: Mute
- kick: Kick
- ban: Ban
- banip: Ban IP

```txt
# Sdf1_chat Chat Filter Config
通知管理员: true
全服通报: true
禁言时长: 300

白名单:
*.minecraft.net
baidu.com
github.com

处罚规则:
1:warn:0
2:warn:0
3:mute:300
5:mute:600
8:kick:0
10:ban:0

白名单玩家:

```

## 3. Commands

- Sdf1_login main command<br>
- Sdf1_login reload reload config<br>
- reg register account<br>
- login login to account<br>
- l login to account<br>

## 4. Screenshots

Main Interface
![](../img/login.png)

Invite Data
![](../img/login2.png)

Points Shop
![](../img/login3.png)

Task Center
![](../img/login4.png)

Trash Can
![](../img/login5.png)

Admin Panel
![](../img/login6.png)

Admin Panel - User Management
![](../img/login7.png)

Ticket Center
![](../img/login8.png)

Ticket Management
![](../img/login9.png)

My Tickets
![](../img/login10.png)

Submit Ticket
![](../img/login11.png)

Provider List
![](../img/login12.png)

Provider Grab Hall
![](../img/login13.png)

Provider - Order Interface
![](../img/login14.png)

Admin - Ticket Detail Page
![](../img/login15.png)

## 5. Download Links
- GitHub: [https://github.com/ypsdf1/sdf1_login](https://github.com/ypsdf1/sdf1_login)
- Gitee: [https://gitee.com/nihaoshidifu/sdf1_login](https://gitee.com/nihaoshidifu/sdf1_login)