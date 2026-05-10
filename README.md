# Sdf1系列登录插件使用说明

## 1. 功能介绍

| 名字 | 描述/作用 | 施工状态 |
| :--- | :--- | :--- |
| 登录 | 用户登录/注册 | 已完成 |
| 世界垃圾箱 | 清理服务器凋落物 | 半成品 |
| 工单系统 | 用户提交工单 | 已完成 |
| 签到系统 | 签到积分换奖励 | 已完成 |

## 2. 安装说明

| 前置插件 | 选装类型 |
| :--- | :--- |
| CY_beibao | 可选 |
| EssentialsX | 可选 |

### 2.1 下载
您可以从GitHub和Gitee下载到插件，将其丢入服务器plugin目录，重启服务器即可。

### 2.2 配置文件

#### 2.2.1 消息.txt
此文件控制着整个插件的所有提示消息，您可以根据自己的需求修改，修改后需要执行 /sdf1_login reload 重载插件。

```txt
# ==========================================
#      Sdf1_login 消息配置文件
# ==========================================
# 颜色代码使用 § 符号
#   §0黑色 §1深蓝 §2深绿 §3深青
#   §4深红 §5深紫 §6金色 §7灰色
#   §8深灰 §9蓝色 §a绿色 §b浅蓝
#   §c红色 §d粉色 §e黄色 §f白色
#   §l粗体 §n下划线 §o斜体 §r重置
#
# 占位符用 {xxx} 表示，运行时自动替换
# 支持 \n 或 <br> 换行
# 修改后执行 /sdf1_login reload 重载
# ==========================================

# ---- 登录注册相关 ----
login_timeout=§c登录超时，请重新登录
not_registered=§e您尚未注册，请使用 /reg <密码>
not_logged_in=§e您尚未登录，请使用 /login <密码>
already_registered=§c您已注册过账号
already_logged_in=§c您已登录
reg_success=§a注册成功！欢迎来到服务器
login_success=§a登录成功！
login_failed=§c密码错误，请重试

# ---- 密码相关 ----
password_wrong=§c密码错误
password_changed=§a密码修改成功！
password_format_error=§c密码需6位以上，含大小写字母和数字
password_same=§c新密码不能与旧密码相同
reset_no_email=§c您未绑定邮箱，无法找回密码
reset_sent=§a临时密码已发送到您的邮箱，请查收

# ---- 签到积分相关 ----
checkin_already=§c您今日已签到
checkin_success=§a签到成功！获得 {points} 积分，连续 {streak} 天，倍率 x{multi}
points_insufficient=§c积分不足，当前: {points}
points_purchase_success=§a购买成功！消耗 {count} 积分

# ---- 挂机检测相关 ----
afk_set_enabled=§a挂机检测已开启
afk_set_disabled=§c挂机检测已关闭
afk_kick=§c因挂机过久被踢出

# ---- 邮箱相关 ----
email_set=§a邮箱设置成功：{email}
email_format_error=§c邮箱格式不正确

# ---- 邀请相关 ----
invite_code_generated=§a邀请码：{code}
invite_used=§a邀请成功，邀请人：{player}
invite_self=§c不能使用自己的邀请码
invite_not_found=§c邀请码不存在
invite_referral_bonus=§a邀请奖励！获得 {points} 积分

# ---- 新人礼包相关 ----
gift_not_ready=§c礼包条件未满足
gift_claimed=§a礼包领取成功！

# ---- 审批与IP限制 ----
need_approval=§e注册需审批 (工单#{id})
ip_max_accounts=§cIP注册已达上限

# ---- 管理员操作 ----
admin_delete_confirm=§c请在聊天中输入玩家名确认删除

# ---- 聊天过滤 ----
chat_muted=§c§l[Sdf1_chat] §f你已被禁言，无法聊天
chat_url_blocked=§c§l[Sdf1_chat] §f检测到违规第三方链接: §e{url}
chat_url_violation=§7当前违规次数: §e{count}
chat_url_admin_notify=§e§l[Sdf1_chat] §f{player} 发送违规链接: {url} (第{count}次)
chat_url_broadcast=§c§l[Sdf1_chat] §e{player} §f因发送违规链接被处罚

# ---- 通用 ----
未知参数=§c未知参数或权限不足
```

#### 2.2.2 挂��白名单.txt
当挂机踢出开启时，该配置下的玩家不受挂机踢出影响。

#### 2.2.3 SMTP设置.txt
此文件控制着SMTP邮件服务器的配置，用于找回密码时发送邮件。可通过GUI进行配置，也可以手动修改此配置文件。

```txt
# ===== SMTP 邮件服务器配置 =====
smtp地址=smtp.example.com
smtp端口=465
smtp账号=mail@example.com
smtp密码=example.com
发件人名称=Sdf1_login
smtp加密=true
验证码接收邮箱=example@example.com
```

#### 2.2.4 插件设置.txt
此配置管控整个插件，包括玩家能注册多少个账号、默认的管理员面板密码、tag包含什么视为管理员等。

```txt
# ===== Sdf1_login 插件设置 =====
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

#### 2.2.5 chat.txt
该文件控制着聊天过滤，当玩家发送违规链接时，会自动根据配置规则进行处理。

```txt
# Sdf1_chat 聊天过滤配置
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

## 3. 指令

- Sdf1_login - 主指令
- Sdf1_login reload - 重载配置文件
- reg - 注册账号
- login - 登录账号
- l - 登录账号

## 4. 运行截图

主界面
![img/login.png]

邀请数据
![img/login2.png]

积分商店
![img/login3.png]

任务中心
![img/login4.png]

垃圾箱
![img/login5.png]

管理员面板
![img/login6.png]

管理面板-用户管理
![img/login7.png]

工单中心
![img/login8.png]

工单管理
![img/login9.png]

我的工单
![img/login10.png]

提交工单
![img/login11.png]

服务商列表
![img/login12.png]

服务商抢单大厅
![img/login13.png]

服务商-接单界面
![img/login14.png]

管理员-工单详情页面
![img/login15.png]

## 5. 下载地址
- GitHub: https://github.com/ypsdf1/sdf1_login
- Gitee: https://gitee.com/nihaoshidifu/sdf1_login