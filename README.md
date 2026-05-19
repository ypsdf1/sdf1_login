# Sdf1系列登录插件使用说明

# 特别说明：本插件仅有简中语言版本，如需除简中以外的任何版本，请下载插件源码，自行修改！

## 1. 功能介绍

| 名字 | 描述/作用 | 施工状态
| :--- | :--- | :---
| 登录 | 用户登录/注册 | 已完成
| 世界垃圾箱 | 清理服务器凋落物 | 已完成
| 工单系统 | 用户提交工单 | 已完成
| 签到系统 | 签到积分换奖励 | 已完成
| 新人礼包 | 新玩家7日礼包 | 已完成
| 挂机检测 | 挂机检测踢出 | 已完成
| 自定义菜单 | 可以自己diy指令 | 已完成
| 自定义菜单图标 | 可以用自己喜欢的物品当图标 | 待施工

## 2.安装说明：

| 前置插件 | 选装类型 |
| :--- | :---
| [CY_beibao](https://pan.ypshidifu.cn/s/Ygc0) | 可选
| EssentialsX | 可选

### 2.1 下载
您可以从GitHub和Gitee下载到插件，将其丢入服务器plugin目录，重启服务器即可。

### 2.2.2 配置文件

#### 2.2.2.1 消息.txt
此文件控制着整个插件的所有提示消息，您可以根据自己的需求修改，修改后需要执行 /sdf1_login reload 重载插件。支持[颜色代码](https://zh.minecraft.wiki/w/%E6%A0%BC%E5%BC%8F%E5%8C%96%E4%BB%A3%E7%A0%81)<br>
同时兼容`<b> </b>`标签，对段落加粗，`\n 和 <br>`进行段落换行


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
# 支持 <b>粗体</b> 标签
# 支持 \n 或 <br> 换行
# 修改后执行 /sdf1_login reload 重载
# ==========================================

# ---- 登录注册相关 ----
# login_timeout: 登录超时被踢出时的提示
login_timeout=§c登录超时，请重新登录
# not_registered: 未注册玩家收到的提示
not_registered=§e您尚未注册，请使用 /reg <密码>
# not_logged_in: 已注册但未登录的提示
not_logged_in=§e您尚未登录，请使用 /login <密码>
# already_registered: 已注册玩家再次注册时的提示
already_registered=§c您已注册过账号
# already_logged_in: 已登录玩家再次登录时的提示
already_logged_in=§c您已登录
# reg_success: 注册成功提示
reg_success=§a注册成功！欢迎来到服务器
# login_success: 登录成功提示
login_success=§a登录成功！
# login_failed: 密码错误提示
login_failed=§c密码错误，请重试

# ---- 密码相关 ----
# password_wrong: 旧密码验证失败
password_wrong=§c密码错误
# password_changed: 密码修改成功
password_changed=§a密码修改成功！
# password_format_error: 密码格式不符合要求
password_format_error=§c密码需6位以上，含大小写字母和数字
# password_same: 新密码与旧密码相同
password_same=§c新密码不能与旧密码相同
# reset_no_email: 找回密码时未绑定邮箱
reset_no_email=§c您未绑定邮箱，无法找回密码
# reset_sent: 临时密码已发送到邮箱
reset_sent=§a临时密码已发送到您的邮箱，请查收

# ---- 签到积分相关 ----
# checkin_already: 今日已签到
checkin_already=§c您今日已签到
# checkin_success: 签到成功，{points}=获得积分 {streak}=连续天数 {multi}=倍率
checkin_success=§a签到成功！获得 {points} 积分，连续 {streak} 天，倍率 x{multi}
# points_insufficient: 积分不足
points_insufficient=§c积分不足，当前: {points}
# points_purchase_success: 积分购买成功
points_purchase_success=§a购买成功！消耗 {count} 积分

# ---- 挂机检测相关 ----
# afk_set_enabled: 挂机检测开启
afk_set_enabled=§a挂机检测已开启
# afk_set_disabled: 挂机检测关闭
afk_set_disabled=§c挂机检测已关闭
# afk_kick: 因挂机被踢出
afk_kick=§c因挂机过久被踢出

# ---- 邮箱相关 ----
# email_set: 邮箱设置成功
email_set=§a邮箱设置成功：{email}
# email_format_error: 邮箱格式错误
email_format_error=§c邮箱格式不正确

# ---- 邀请相关 ----
# invite_code_generated: 邀请码生成
invite_code_generated=§a邀请码：{code}
# invite_used: 使用邀请码成功
invite_used=§a邀请成功，邀请人：{player}
# invite_self: 使用自己的邀请码
invite_self=§c不能使用自己的邀请码
# invite_not_found: 邀请码不存在
invite_not_found=§c邀请码不存在
# invite_referral_bonus: 被邀请人签到后邀请人获得奖励
invite_referral_bonus=§a邀请奖励！获得 {points} 积分

# ---- 新人礼包相关 ----
# gift_not_ready: 礼包条件未满足
gift_not_ready=§c礼包条件未满足
# gift_claimed: 礼包领取成功
gift_claimed=§a礼包领取成功！

# ---- 审批与IP限制 ----
# need_approval: 注册需审批
need_approval=§e注册需审批 (工单#{id})
# ip_max_accounts: IP注册已达上限
ip_max_accounts=§cIP注册已达上限

# ---- 管理员操作 ----
# admin_delete_confirm: 管理员删除账号确认提示
admin_delete_confirm=§c请在聊天中输入玩家名确认删除

# ---- 聊天过滤 ----
# chat_muted: 禁言玩家尝试聊天
chat_muted=§c§l[Sdf1_chat] §f你已被禁言，无法聊天
# chat_url_blocked: 发送违规链接被拦截
# {url}=检测到的链接地址
chat_url_blocked=§c§l[Sdf1_chat] §f检测到违规第三方链接: §e{url}
# chat_url_violation: 当前违规次数
# {count}=当前违规次数
chat_url_violation=§7当前违规次数: §e{count}
# chat_url_admin_notify: 通知管理员，{player}=玩家名 {url}=链接 {count}=次数
chat_url_admin_notify=§e§l[Sdf1_chat] §f{player} 发送违规链接: {url} (第{count}次)
# chat_url_broadcast: 全服通报，{player}=玩家名
chat_url_broadcast=§c§l[Sdf1_chat] §e{player} §f因发送违规链接被处罚

# ---- 通用 ----
# 未知参数: 未知命令或权限不足时的提示
未知参数=§c未知参数或权限不足
# only_player: 仅玩家可用的命令
# only_player=§c此命令仅玩家可用

```

### 2.2.2 挂机白名单.txt
当挂机踢出开启时，该配置下的玩家不受挂机提出影响

### 2.2.3 SMTP设置.txt

此文件控制着SMTP邮件服务器的配置，用于找回密码时发送邮件。<br>可通过GUI进行配置，也可以手动修改此配置文件。修改后，重载插件即可生效

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

### 2.2.4 插件设置.txt

此配置管控这整个插件，包括玩家能注册多少个账号、默认的管理员面板密码多少、tag包含什么视为管理员等

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
# ---- 签到盲盒奖励 ----
签到固定金额=0
签到最小金额=10
签到最大金额=100
签到给积分=true
签到积分数量=10


```

### 2.2.5 chat.txt
该文件控制着聊天过滤，当玩家发送违规链接时，会自动根据配置规则进行处理。<br>
可用的处罚规则：
- warn: 警告
- mute: 禁言
- kick: 踢出
- ban: 封禁
- banip：封禁IP

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
### 2.2.6 radio_config.txt
```txt
# ============ Sdf1 Radio Config ============
#
# resource-pack-url
#   填外部地址（HTTP或HTTPS均可）
#   例: https://example.com/radiopack.zip
#   留空 = 使用内置HTTP服务
#
# http-port
#   内置HTTP端口（仅resource-pack-url为空时生效）
#
# ============ Settings ===============

resource-pack-url=https://*.ypshidifu.cn/|*.mcserver.ccwu.cc
http-port=443
```
- radio_config这里可以使用通配符域名，当使用通配符时，系统随机生成url，拒绝过资源包的玩家，每次都会收到提示。并且自1.2版本起，拒绝资源包=放弃游玩服务器。如果您无需广播功能，可以不放ogg文件进入`./plugin/radio/ogg`。这样服务端不会自动生成资源包，也不会强制下载了

### 2.2.7 菜单.txt
- 自定义菜单配置文件
```json
# 服务器菜单
# 使用类json格式
// 支持使用：//Java单行注释、#yml单行注释，/* */和<!--- --->块注释

{
  标题：主城传送
  指令：/dom tp 主城区 # 这里如果是xxx.txt则表示进入二级目录
  权限类型：玩家
  图标：COMPASS
}

{
  标题：随机传送
  指令：/rtp
  权限类型：玩家
  图标：ENDER_PEARL
}

{
  标题：擂台比武
  指令：/dom tp PVP
  权限类型：玩家
  图标：netherite_spear
}

{
  标题：领地操作
  指令：/dom
  权限类型：玩家
  图标：paper
}
{
  标题：逛商店
  指令：/dom tp shop
  权限类型：玩家
  图标：honeycomb
}

{
  标题：传送开关
  指令：/tptoggle
  权限类型：玩家
  图标：redstone_torch
}

{
  标题：返回上一个位置
  指令：/back
  权限类型：玩家
  图标：red_bed
}
{
  标题：前往小黑塔
  指令：/dom tp 小黑塔
  权限类型：玩家
  图标：dragon_head
}
{
  标题：云背包
  指令：/cy
  权限类型：玩家
  图标：potato
}
{
  标题：自杀
  指令：/suicide
  权限类型：玩家
  图标：wooden_axe
}
{
  标题：游戏商人
  指令：/shop
  权限类型：玩家
  图标：shield
  格子：28
}
```

## 3. 指令参数及介绍

- Sdf1_login 主指令<br>
- Sdf1_login reload 重载配置文件<br>
- reg 注册账号<br>
- login 登录账号<br>
- l 登录账号<br>
- sdf1_login del 删除玩家账号<br>
- sdf1_login radio 停止播放广播<br>
- sdf1_login radio reload 重载广播并重新打包资源包

## 运行截图

主界面
![](../img/login.png)

邀请数据
![](../img/login2.png)

积分商店
![](../img/login3.png)

任务中心
![](../img/login4.png)

垃圾箱
![](../img/login5.png)

管理员面板
![](../img/login6.png)

管理面板-用户管理
![](../img/login7.png)


工单中心
![](../img/login8.png)

工单管理
![](../img/login9.png)

我的工单
![](../img/login10.png)


提交工单
![](../img/login11.png)

服务商列表
![](../img/login12.png)

服务商抢单大厅
![](../img/login13.png)

服务商-接单界面
![](../img/login14.png)

管理员-工单详情页面
![](../img/login15.png)

## 4. 下载地址
- GitHub：[https://github.com/ypsdf1/sdf1_login](https://github.com/ypsdf1/sdf1_login)
- Gitee：[https://gitee.com/nihaoshidifu/sdf1_login](https://gitee.com/nihaoshidifu/sdf1_login)
