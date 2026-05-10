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
您可以从GitHub和Gitee下载插件，将其放入服务器plugin目录，重启服务器即可。

### 2.2 配置文件

#### 2.2.1 消息.txt
此文件控制整个插件的所有提示消息，修改后执行 /sdf1_login reload 重载插件。

#### 2.2.2 挂机白名单.txt
挂机踢出开启时，该配置下的玩家不受影响。

#### 2.2.3 SMTP设置.txt
控制SMTP邮件服务器配置，用于找回密码时发送邮件。

#### 2.2.4 插件设置.txt
管控整个插件，包括账号注册限制、管理员密码等。

#### 2.2.5 chat.txt
控制聊天过滤，处理违规链接。

## 3. 指令

- Sdf1_login 主指令
- Sdf1_login reload 重载配置
- reg 注册账号
- login 登录账号
- l 登录账号

## 4. 运行截图

主界面

![主界面](img/login.png)

邀请数据

![邀请数据](img/login2.png)

积分商店

![积分商店](img/login3.png)

任务中心

![任务中心](img/login4.png)

垃圾箱

![垃圾箱](img/login5.png)

管理员面板

![管理员面板](img/login6.png)

管理面板-用户管理

![用户管理](img/login7.png)

工单中心

![工单中心](img/login8.png)

工单管理

![工单管理](img/login9.png)

我的工单

![我的工单](img/login10.png)

提交工单

![提交工单](img/login11.png)

服务商列表

![服务商列表](img/login12.png)

服务商抢单大厅

![抢单大厅](img/login13.png)

服务商-接单界面

![接单界面](img/login14.png)

管理员-工单详情

![工单详情](img/login15.png)

## 5. 下载地址

- GitHub: https://github.com/ypsdf1/sdf1_login
- Gitee: https://gitee.com/nihaoshidifu/sdf1_login
