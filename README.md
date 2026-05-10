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

### 2.2.2 配置文件

#### 2.2.2.1 消息.txt
此文件控制着整个插件的所有提示消息，您可以根据自己的需求修改，修改后需要执行 /sdf1_login reload 重载插件。

### 2.2.2 挂机白名单.txt
当挂机踢出开启时，该配置下的玩家不受挂机踢出影响。

### 2.2.3 SMTP设置.txt
此文件控制着SMTP邮件服务器的配置，用于找回密码时发送邮件。

### 2.2.4 插件设置.txt
此配置管控整个插件，包括玩家能注册多少个账号、默认的管理员面板密码、tag包含什么视为管理员等。

### 2.2.5 chat.txt
该文件控制着聊天过滤，当玩家发送违规链接时，会自动根据配置规则进行处理。

## 3. 指令

- Sdf1_login 主指令
- Sdf1_login reload 重载配置文件
- reg 注册账号
- login 登录账号
- l 登录账号

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
