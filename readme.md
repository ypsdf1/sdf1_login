# Sdf1_login Web 端（PHP 后端）

> 本目录是 Minecraft PaperMC 插件 **`Sdf1_login`**（即本仓库的 `../Sdf1_login`）配套的 PHP Web 后端。
> 两个工程是**一对**：Java 插件负责游戏内逻辑（登录注册、债券经济、商城、领地、工单、PVP…），
> 本目录负责 Web 侧的界面与数据（玩家面板、管理后台、收银台、在线充值、正版登录）。

## 1. 两个工程的关系（一图流）

```
游戏内（Java 插件 Sdf1_login）                    Web 侧（本目录）
─────────────────────────────                  ───────────────────────────
/reg /login /cypay /shop /web /mslogin    ◄──►  login.php / player.php  玩家面板
/sdf1_login 管理面板                          ◄──►  admin.php           管理后台（19 模块）
商城/CDK/债券流水 权威数据在 Java              ◄──►  cashier.php         独立收银台
                                                     api/pay.php         彩虹易支付在线充值
                                                     
数据通道：① Java 调 PHP 的 api/sync.php（HTTP，带 secretKey，60+ action 推/拉）
          ② 共享 SQLite 队列（web.db 里的 pending 表，Java 每 5 秒轮询消费）
          ③ 领地/用户组等变更走 web_admin_changes 队列双向同步
```

- 对应文档：Java 端 `../Sdf1_login/README.md`（使用手册）、`../Sdf1_login/代码关联关系.md`（类级关联）
- 本目录的 `代码关联关系.md`（PHP 侧，含全部 sync action 清单与通信模型，本文档的"下钻"手册）

## 2. 目录结构速览

| 路径 | 说明 |
|---|---|
| `config.php` | **全部配置入口**：SECRET_KEY（必须与 Java 端一字不差）、管理员账号、SMTP、游戏库路径、人机验证厂商选择。仓库里是 `REPLACE_ME_*` 占位符，真实值只在服务器 |
| `core.php` | 公共引擎（约 80KB）：SQLite 单例（WAL + 锁竞争调优）、建表/迁移、Token 双轨体系、管理员/收银员会话、SMTP、IP 风控黑名单入口拦截。**几乎所有文件都 require 它** |
| `security.php` | 2026-09-29 入侵事件后的三层加固：TOTP 二次验证 + IP 白名单 + 后台访问令牌 + 会话纪元 |
| 页面层 | `login.php` → `player.php`（玩家 SPA，242KB）；`admin_login.php` → `admin.php`（管理 SPA，230KB，内嵌 `cashier.js` 收银模块）；`cashier.php` 独立收银台；`service.php` 服务商面板；`freeze.php` / `reset_password.php` 邮件自助；`active_players.php` / `online_curve.php` / `today_bonds.php` 统计页（兼 JSON API）；`admin_2fa_setup.php` 安全配置引导页 |
| `api/` | 接口层，核心是 **`sync.php`**（Java↔PHP 唯一同步入口，60+ action）；其余：`admin.php` 后台 API、`shop.php` 商城、`cdk.php`、`pay.php` + `poller_online.php` + `pay_poller.php` 支付与补单、`minecraft_auth.php` MS 正版 OAuth 代理、`land_api.php` 领地/用户组、`ticket.php` 工单、`security_alert.php` 异地登录回调、`sn.php` 物品 SN 防刷等 |
| `db/` | **`web.db`**（约 40 张表，PHP 读写、Java 经接口读写，绝不直开文件）+ **`orders.db`**（纯 PHP 独占：pay_orders / cashier_orders / shop_configs / mc_auth_sessions / cashiers，拆独立库是为了消除 SQLite 文件锁竞争） |
| 文档 | `pay.md`（订单号格式配置） |

## 3. 数据：三个 SQLite 库的分工

| 库 | 谁读写 | 内容 |
|---|---|---|
| `db/web.db` | PHP + Java（Java 经 `api/sync.php` 等 HTTP 接口，不直开文件） | users、tokens、weblogin_*、shop_items、cdk、web_transactions、bond_cache、online_players、web_area_lands/permissions、web_user_groups、web_tickets、SN 系列、各 pending 队列 |
| `db/orders.db` | **纯 PHP** | 在线充值订单、收银台订单、充值档位配置、MS OAuth 会话、收银员账号 |
| 游戏侧 `login.db` / `bond.db` | **Java 独占** | 插件账号与债券账本。PHP 唯一例外：`api/land_api.php` 验证玩家时可直读 `GAME_LOGIN_DB` 兜底 |

## 4. 与 Java 插件的通信模型（重点）

1. **没有 PHP → Java 的 HTTP 调用**（`config.php` 里的 `CALLBACK_PORT` / `GAME_SERVER_HOST` 是历史死配置）。
   方向只有 Java → PHP（`sync.php` 等），以及 PHP 写队列表、Java 轮询消费。
2. **四条共享队列**（都在 web.db）：
   - `web_transactions`（pending）：发货/加债券/CDK/充值到账 → Java `pull_pending_transactions` 拉取执行，`confirmed_transactions` 防重
   - `sync_requests`："立即来拉"标记（购物/改价后触发 Java 快速同步）
   - `web_admin_changes`：PHP 侧领地删改/用户组变更 → Java 消费后回推最终状态（**Java 是领地权威源**）
   - `web_login_requests` / `web_register_requests` / `cdk_validate_requests` / `pending_player_validations`：密码由 **Java 用 login.db 真实凭证校验**，PHP 禁止自验
3. **玩家 Web 登录链**：游戏内 `/web` → Java 生成 weblogin_token 推给 PHP → 浏览器 `login.php?token=` → player.php；
   无 token 时走密码 → 写 pending → Java 校验 → 前端轮询 `check_web_login_result`。
4. **在线充值链**：`pay.php` 建单（orders.db）→ 彩虹易支付网关 → notify 验签幂等 + CF WAF 拦截 notify 时的 `poller_online.php` 直扫平台 MySQL 补单双保险 → 写 pending 流水 → Java 轮询加债券到账。
   多服共用一张 `pay_order` 表时靠 `PAY_ORDER_PREFIX` 隔离（生产 `RE`，测试改如 `RT`）。
5. 每个 action 的完整清单见 `代码关联关系.md` §5/§6。

## 5. 四套鉴权

| 角色 | 方式 |
|---|---|
| 玩家 | 游戏内 `/web` 签发的 weblogin_token；或账密（Java 异步校验）；3 分钟同 IP 免密快速重连 |
| 管理员 | PHP session + 密码，叠加 `security.php` 三层加固（TOTP / IP 白名单 / 后台访问令牌），被锁外时用 boot 令牌进 `admin_2fa_setup.php` 逃生 |
| 收银员 | `cashiers` 表 bcrypt + 折扣上限 + 现金收款权限 |
| 服务商 | 游戏账号 + `web_service_providers` 授权表 |

## 6. 首次部署清单

1. **改 `config.php` 占位符**：`SECRET_KEY`（与 Java 端 `插件设置` 保持一致，否则同步接口全 401）、`ADMIN_PASS`、`SMTP_*`（不发邮件可留）、`GAME_*_DB/DIR`、`PAY_ORDER_PREFIX`。
2. **首次访问 `https://域名/plugin/admin_2fa_setup.php`** 保存任意一项 → 自动把缺失的 `SEC_*` / `ADMIN_IP_WHITELIST` / 2FA 密钥随机生成并写回 config.php 的 `>>>SEC-UPDATE` 区（写前自动备份 `db/config_bak/`），并转入强制模式。
3. **Java 侧**：WebManager 的 webBaseUrl 指向本站；启用 web 通信后开始 5 秒轮询队列。
4. 部署走 `deploy_php_only.py`（FTP）——脚本**永远排除** `config.php`、`pay_secrets.php`、`captcha_keys.php`、`lsky_keys.php`、`db/`：这些文件仓库里是空模板/占位符，覆盖一次线上真值 = 同步 401 + 后台锁死 + 支付补单全挂（均有事故复盘注释）。
5. 支付商户密钥（汇付/RSA/平台 MySQL/MS OAuth）在 `api/pay_secrets.php`（仓库为范文，真值手工上传服务器）与 `密钥.md`（**仅本地存储，严禁入库**）。

## 7. 安全注意（已知项）

- `api/diag_db_content.php` **无鉴权**可 dump 两库表结构与样例，正式站应删除或加门禁。
- `api/get_online_players.php` 是损坏文件（依赖不存在的 `../inc/function.php`）。
- `sync.php` 里 `web_login_requests` 存的是明文密码（由 Java 消费校验）——web.db 文件泄露 ≈ 全服账号泄露，`db/` 已 git-ignore。
- 仓库版 `config.php` 严禁出现真实口令/密钥（2026-09-29 用户指令，历史事故复盘见文件头注释）。

---
