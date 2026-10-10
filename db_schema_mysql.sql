-- SDF1 双库表结构快照（灾备自愈用，由生产 SHOW CREATE TABLE 生成）
-- 用法：db_guard.php 恢复模式据此补建缺失表（CREATE TABLE IF NOT EXISTS）

-- ================= aoyuanjava =================

-- aoyuanjava.ond_cache
CREATE TABLE IF NOT EXISTS `bond_cache` (
  `player_name` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `amount` bigint(20) DEFAULT '0',
  `updated_at` bigint(20) DEFAULT '0',
  PRIMARY KEY (`player_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.dk
CREATE TABLE IF NOT EXISTS `cdk` (
  `code` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `amount` bigint(20) DEFAULT '0',
  `type` varchar(500) COLLATE utf8mb4_bin DEFAULT 'bond',
  `used` bigint(20) DEFAULT '0',
  `used_by` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `created_at` bigint(20) DEFAULT '0',
  `used_at` bigint(20) DEFAULT '0',
  PRIMARY KEY (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.dk_validate_requests
CREATE TABLE IF NOT EXISTS `cdk_validate_requests` (
  `request_id` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `code` text COLLATE utf8mb4_bin NOT NULL,
  `player_name` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `status` varchar(500) COLLATE utf8mb4_bin DEFAULT 'pending',
  `created_at` bigint(20) NOT NULL,
  PRIMARY KEY (`request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.dk_validate_results
CREATE TABLE IF NOT EXISTS `cdk_validate_results` (
  `request_id` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `code` text COLLATE utf8mb4_bin NOT NULL,
  `status` text COLLATE utf8mb4_bin NOT NULL,
  `amount` bigint(20) DEFAULT '0',
  `message` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `used` bigint(20) DEFAULT '0',
  `used_by` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `created_at` bigint(20) NOT NULL,
  PRIMARY KEY (`request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.onfirmed_transactions
CREATE TABLE IF NOT EXISTS `confirmed_transactions` (
  `tx_id` varchar(325) COLLATE utf8mb4_bin NOT NULL,
  `tx_type` varchar(325) COLLATE utf8mb4_bin NOT NULL,
  `confirmed_at` bigint(20) NOT NULL,
  PRIMARY KEY (`tx_id`,`tx_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.mail_codes
CREATE TABLE IF NOT EXISTS `email_codes` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `player_name` text COLLATE utf8mb4_bin NOT NULL,
  `email` text COLLATE utf8mb4_bin NOT NULL,
  `code` text COLLATE utf8mb4_bin NOT NULL,
  `expire_at` bigint(20) NOT NULL,
  `used` bigint(20) DEFAULT '0',
  `created_at` bigint(20) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.ame_transactions
CREATE TABLE IF NOT EXISTS `game_transactions` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `java_id` bigint(20) NOT NULL,
  `player_name` text COLLATE utf8mb4_bin NOT NULL,
  `type` text COLLATE utf8mb4_bin NOT NULL,
  `amount` bigint(20) NOT NULL,
  `target_player` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `operator` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `reason` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `balance_before` bigint(20) DEFAULT '0',
  `balance_after` bigint(20) DEFAULT '0',
  `tx_time` bigint(20) NOT NULL,
  `synced_at` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `java_id` (`java_id`),
  KEY `idx_gt_player` (`player_name`(191),`tx_time`),
  KEY `idx_gt_type` (`type`(191))
) ENGINE=InnoDB AUTO_INCREMENT=61640 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.ourly_active_players
CREATE TABLE IF NOT EXISTS `hourly_active_players` (
  `player_name` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `first_seen` bigint(20) NOT NULL,
  `last_seen` bigint(20) NOT NULL,
  `hour_key` text COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (`player_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.nline_player_hb
CREATE TABLE IF NOT EXISTS `online_player_hb` (
  `id` bigint(20) NOT NULL DEFAULT '1',
  `last_seen` bigint(20) DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.nline_players
CREATE TABLE IF NOT EXISTS `online_players` (
  `player_name` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `login_time` bigint(20) DEFAULT '0',
  PRIMARY KEY (`player_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.assword_reset_requests
CREATE TABLE IF NOT EXISTS `password_reset_requests` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `player_name` text COLLATE utf8mb4_bin NOT NULL,
  `requested_email` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `status` varchar(500) COLLATE utf8mb4_bin DEFAULT 'pending',
  `admin_approved` bigint(20) DEFAULT '0',
  `admin_email` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `created_at` bigint(20) NOT NULL,
  `processed_at` bigint(20) DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.assword_reset_tokens
CREATE TABLE IF NOT EXISTS `password_reset_tokens` (
  `token` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `player_name` text COLLATE utf8mb4_bin NOT NULL,
  `expire_at` bigint(20) NOT NULL,
  PRIMARY KEY (`token`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.layer_checkins
CREATE TABLE IF NOT EXISTS `player_checkins` (
  `player_name` varchar(325) COLLATE utf8mb4_bin NOT NULL,
  `checkin_date` varchar(325) COLLATE utf8mb4_bin NOT NULL,
  `checkin_time` bigint(20) DEFAULT '0',
  PRIMARY KEY (`player_name`,`checkin_date`),
  KEY `idx_player_checkins_player` (`player_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.layer_daily_logins
CREATE TABLE IF NOT EXISTS `player_daily_logins` (
  `player_name` varchar(325) COLLATE utf8mb4_bin NOT NULL,
  `login_date` varchar(325) COLLATE utf8mb4_bin NOT NULL,
  `first_login` bigint(20) DEFAULT '0',
  `last_login` bigint(20) DEFAULT '0',
  PRIMARY KEY (`player_name`,`login_date`),
  KEY `idx_player_daily_logins_player` (`player_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.layer_ip_changes
CREATE TABLE IF NOT EXISTS `player_ip_changes` (
  `player_name` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `old_ip` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `new_ip` text COLLATE utf8mb4_bin NOT NULL,
  `changed_at` bigint(20) NOT NULL,
  `synced_at` bigint(20) DEFAULT '0',
  PRIMARY KEY (`player_name`),
  KEY `idx_player_ip_changes_player` (`player_name`),
  KEY `idx_player_ip_changes_time` (`changed_at`),
  KEY `idx_player_ip_changes_newip` (`new_ip`(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.layer_ip_locations
CREATE TABLE IF NOT EXISTS `player_ip_locations` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `ip_address` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `location` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `updated_at` bigint(20) DEFAULT '0',
  `player_name` varchar(500) COLLATE utf8mb4_bin DEFAULT 'global',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_player_ip_locations_975887f3` (`ip_address`(191),`player_name`(191)),
  KEY `idx_player_ip_locations_ip` (`ip_address`),
  KEY `idx_player_ip_locations_loc` (`location`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.hop_config
CREATE TABLE IF NOT EXISTS `shop_config` (
  `cfg_key` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `cfg_value` text COLLATE utf8mb4_bin NOT NULL,
  PRIMARY KEY (`cfg_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.hop_items
CREATE TABLE IF NOT EXISTS `shop_items` (
  `id` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `category` text COLLATE utf8mb4_bin NOT NULL,
  `display_name` text COLLATE utf8mb4_bin NOT NULL,
  `material` text COLLATE utf8mb4_bin NOT NULL,
  `buy_price` bigint(20) DEFAULT '0',
  `sell_price` bigint(20) DEFAULT '-1',
  `stock` bigint(20) DEFAULT '-1',
  `hourly_sales` bigint(20) DEFAULT '0',
  `total_sales` bigint(20) DEFAULT '0',
  `last_sync` bigint(20) DEFAULT '0',
  `admin_stock` bigint(20) DEFAULT NULL,
  `admin_buy_price` bigint(20) DEFAULT NULL,
  `admin_sell_price` bigint(20) DEFAULT NULL,
  `last_modified` bigint(20) DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.ync_requests
CREATE TABLE IF NOT EXISTS `sync_requests` (
  `player_name` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `created_at` bigint(20) NOT NULL,
  PRIMARY KEY (`player_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.okens
CREATE TABLE IF NOT EXISTS `tokens` (
  `token` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `player_name` text COLLATE utf8mb4_bin NOT NULL,
  `purpose` varchar(500) COLLATE utf8mb4_bin DEFAULT 'general',
  `created_at` bigint(20) NOT NULL,
  `expires_at` bigint(20) NOT NULL,
  `used` bigint(20) DEFAULT '0',
  `used_at` bigint(20) DEFAULT '0',
  PRIMARY KEY (`token`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.sers
CREATE TABLE IF NOT EXISTS `users` (
  `player_name` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `register_time` bigint(20) DEFAULT '0',
  `last_login_time` bigint(20) DEFAULT '0',
  `email` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `points` bigint(20) DEFAULT '0',
  `gift_stage` bigint(20) DEFAULT '0',
  `total_online_time` bigint(20) DEFAULT '0',
  `last_login_location` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `last_login_ip` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `frozen` bigint(20) DEFAULT '0',
  `freeze_token` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  PRIMARY KEY (`player_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.eb_admin_changes
CREATE TABLE IF NOT EXISTS `web_admin_changes` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `change_type` text COLLATE utf8mb4_bin NOT NULL,
  `target_id` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `target_name` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `change_data` varchar(500) COLLATE utf8mb4_bin DEFAULT '{}',
  `created_at` bigint(20) DEFAULT '0',
  `acknowledged` bigint(20) DEFAULT '0',
  `acked_at` bigint(20) DEFAULT '0',
  `status` varchar(500) COLLATE utf8mb4_bin DEFAULT 'pending',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.eb_area_config
CREATE TABLE IF NOT EXISTS `web_area_config` (
  `key` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `value` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  PRIMARY KEY (`key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.eb_area_lands
CREATE TABLE IF NOT EXISTS `web_area_lands` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `name` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `owner` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `world` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `x1` bigint(20) DEFAULT '0',
  `z1` bigint(20) DEFAULT '0',
  `x2` bigint(20) DEFAULT '0',
  `z2` bigint(20) DEFAULT '0',
  `y1` bigint(20) DEFAULT '0',
  `y2` bigint(20) DEFAULT '0',
  `area_size` bigint(20) DEFAULT '0',
  `created_at` bigint(20) DEFAULT '0',
  `admin_changed` bigint(20) DEFAULT '0',
  `deny_pvp` bigint(20) DEFAULT '0',
  `deny_fall_damage` bigint(20) DEFAULT '0',
  `deny_hunger` bigint(20) DEFAULT '0',
  `deny_all_damage` bigint(20) DEFAULT '0',
  `deny_fire_spread` bigint(20) DEFAULT '0',
  `deny_explosion` bigint(20) DEFAULT '0',
  `deny_mob_grief` bigint(20) DEFAULT '0',
  `deny_block_place` bigint(20) DEFAULT '0',
  `deny_block_break` bigint(20) DEFAULT '0',
  `y_min` bigint(20) DEFAULT '0',
  `y_max` bigint(20) DEFAULT '255',
  `synced_at` bigint(20) DEFAULT '0',
  `clear_effects` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `give_effects` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `clear_all_bad_effects` bigint(20) DEFAULT '0',
  `deny_all_effects` bigint(20) DEFAULT '0',
  `confiscate_items` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `deny_use_items` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `punish_commands` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `deny_drop` bigint(20) DEFAULT '0',
  `deny_mount` bigint(20) DEFAULT '0',
  `deny_ender_pearl` bigint(20) DEFAULT '0',
  `deny_bow` bigint(20) DEFAULT '0',
  `deny_potion` bigint(20) DEFAULT '0',
  `deny_raid` bigint(20) DEFAULT '0',
  `deny_item_frame` bigint(20) DEFAULT '0',
  `deny_move` bigint(20) DEFAULT '0',
  `deny_pickup` bigint(20) DEFAULT '0',
  `deny_fire` bigint(20) DEFAULT '0',
  `deny_thrown_projectiles` bigint(20) DEFAULT '0',
  `deny_glowing` bigint(20) DEFAULT '0',
  `deny_redstone_interaction` bigint(20) DEFAULT '0',
  `deny_door_interaction` bigint(20) DEFAULT '0',
  `deny_noteblock_jukebox` bigint(20) DEFAULT '0',
  `deny_lead` bigint(20) DEFAULT '0',
  `deny_crop_harvest` bigint(20) DEFAULT '0',
  `deny_wool_shear` bigint(20) DEFAULT '0',
  `deny_animal_feeding` bigint(20) DEFAULT '0',
  `deny_mob_attack` bigint(20) DEFAULT '0',
  `deny_container` bigint(20) DEFAULT '0',
  `peace_mode` bigint(20) DEFAULT '0',
  `peace_mode_duration` bigint(20) DEFAULT '0',
  `peace_whitelist` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `enforce_game_mode` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `mode_exempt` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `enter_msg` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `leave_msg` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `confiscate_msg` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `warp_x` double DEFAULT '0',
  `warp_y` double DEFAULT '0',
  `warp_z` double DEFAULT '0',
  `warp_yaw` double DEFAULT '0',
  `warp_pitch` double DEFAULT '0',
  `warp_world` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `enable_announce` bigint(20) DEFAULT '0',
  `announce_template` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `txt_content` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `deny_fluid` bigint(20) DEFAULT '0',
  `clear_all_bad` bigint(20) DEFAULT '0',
  `is_public_building` bigint(20) DEFAULT '0',
  `deny_entity_interact` bigint(20) DEFAULT '0',
  `allow_visitor_teleport` bigint(20) DEFAULT '0',
  `deny_sign_edit` bigint(20) DEFAULT '0',
  `deny_spawn_egg` bigint(20) DEFAULT '0',
  `deny_wax` bigint(20) DEFAULT '0',
  `bad_effects` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=7072 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.eb_area_permissions
CREATE TABLE IF NOT EXISTS `web_area_permissions` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `land_id` bigint(20) NOT NULL,
  `land_name` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `player_name` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `role` varchar(500) COLLATE utf8mb4_bin NOT NULL DEFAULT 'visitor',
  `permissions` varchar(1000) COLLATE utf8mb4_bin DEFAULT '',
  `granted_at` bigint(20) DEFAULT '0',
  `expires_at` bigint(20) DEFAULT '0',
  `synced_at` bigint(20) DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `land_id` (`land_id`,`player_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.eb_area_shop
CREATE TABLE IF NOT EXISTS `web_area_shop` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `land_id` bigint(20) NOT NULL,
  `land_name` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `seller` text COLLATE utf8mb4_bin NOT NULL,
  `permission` varchar(500) COLLATE utf8mb4_bin DEFAULT 'visitor',
  `price` bigint(20) DEFAULT '0',
  `duration` bigint(20) DEFAULT '86400',
  `status` varchar(500) COLLATE utf8mb4_bin DEFAULT 'active',
  `buyer` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `bought_at` bigint(20) DEFAULT '0',
  `created_at` bigint(20) DEFAULT '0',
  `synced_at` bigint(20) DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.eb_ip_blacklist
CREATE TABLE IF NOT EXISTS `web_ip_blacklist` (
  `ip` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `reason` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `source` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `expire` bigint(20) DEFAULT '0',
  `banned_at` bigint(20) DEFAULT '0',
  PRIMARY KEY (`ip`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.eb_login_confirmations
CREATE TABLE IF NOT EXISTS `web_login_confirmations` (
  `player_name` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `confirmed_at` bigint(20) NOT NULL,
  `consumed` bigint(20) DEFAULT '0',
  PRIMARY KEY (`player_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.eb_login_requests
CREATE TABLE IF NOT EXISTS `web_login_requests` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `player_name` text COLLATE utf8mb4_bin NOT NULL,
  `password` text COLLATE utf8mb4_bin NOT NULL,
  `request_time` bigint(20) NOT NULL,
  `status` varchar(500) COLLATE utf8mb4_bin DEFAULT 'pending',
  `result` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `result_time` bigint(20) DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.eb_login_verified
CREATE TABLE IF NOT EXISTS `web_login_verified` (
  `player_name` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `verified_at` bigint(20) NOT NULL,
  PRIMARY KEY (`player_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.eb_player_bans
CREATE TABLE IF NOT EXISTS `web_player_bans` (
  `target` varchar(325) COLLATE utf8mb4_bin NOT NULL,
  `ban_type` varchar(325) COLLATE utf8mb4_bin NOT NULL DEFAULT 'name',
  `reason` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `source` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `expire_time` bigint(20) DEFAULT '0',
  `synced_at` bigint(20) DEFAULT '0',
  PRIMARY KEY (`target`,`ban_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.eb_plugin_admins
CREATE TABLE IF NOT EXISTS `web_plugin_admins` (
  `player_name` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `synced_at` bigint(20) DEFAULT '0',
  PRIMARY KEY (`player_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.eb_register_requests
CREATE TABLE IF NOT EXISTS `web_register_requests` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `player_name` text COLLATE utf8mb4_bin NOT NULL,
  `password_hash` text COLLATE utf8mb4_bin NOT NULL,
  `salt` text COLLATE utf8mb4_bin NOT NULL,
  `email` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `ip_address` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `created_at` bigint(20) NOT NULL,
  `status` varchar(500) COLLATE utf8mb4_bin DEFAULT 'pending',
  `processed_at` bigint(20) DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.eb_service_providers
CREATE TABLE IF NOT EXISTS `web_service_providers` (
  `player_name` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `role` varchar(500) COLLATE utf8mb4_bin DEFAULT 'waiter',
  `active` bigint(20) DEFAULT '1',
  `join_time` bigint(20) DEFAULT '0',
  PRIMARY KEY (`player_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.eb_session_log
CREATE TABLE IF NOT EXISTS `web_session_log` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `player_name` text COLLATE utf8mb4_bin NOT NULL,
  `ip_address` text COLLATE utf8mb4_bin NOT NULL,
  `login_time` bigint(20) NOT NULL,
  `session_token` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  PRIMARY KEY (`id`),
  KEY `idx_web_session_log_player` (`player_name`(191)),
  KEY `idx_web_session_log_time` (`login_time`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.eb_transactions
CREATE TABLE IF NOT EXISTS `web_transactions` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `player_name` text COLLATE utf8mb4_bin NOT NULL,
  `type` text COLLATE utf8mb4_bin NOT NULL,
  `amount` bigint(20) NOT NULL,
  `operator` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `reason` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `detail` varchar(1059) COLLATE utf8mb4_bin DEFAULT '',
  `status` varchar(500) COLLATE utf8mb4_bin DEFAULT 'pending',
  `created_at` bigint(20) DEFAULT '0',
  `processed_at` bigint(20) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=341 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.eb_user_group_members
CREATE TABLE IF NOT EXISTS `web_user_group_members` (
  `player_name` varchar(325) COLLATE utf8mb4_bin NOT NULL,
  `group_name` varchar(325) COLLATE utf8mb4_bin NOT NULL,
  `added_by` varchar(500) COLLATE utf8mb4_bin DEFAULT 'system',
  `added_time` bigint(20) DEFAULT '0',
  `expiry_time` bigint(20) DEFAULT '0',
  PRIMARY KEY (`player_name`,`group_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.eb_user_groups
CREATE TABLE IF NOT EXISTS `web_user_groups` (
  `group_name` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `display_name` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `display_color` varchar(500) COLLATE utf8mb4_bin DEFAULT '�f',
  `display_emoji` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `priority` bigint(20) DEFAULT '0',
  `land_price_per_sqm` bigint(20) DEFAULT '-1',
  `max_lands` bigint(20) DEFAULT '-1',
  `max_effects` bigint(20) DEFAULT '5',
  `max_effect_level` bigint(20) DEFAULT '256',
  `home_limit` bigint(20) DEFAULT '0',
  `join_price` bigint(20) DEFAULT '0',
  `auto_renew` bigint(20) DEFAULT '0',
  `renew_price` bigint(20) DEFAULT '0',
  `duration_minutes` bigint(20) DEFAULT '0',
  `default_perms` varchar(500) COLLATE utf8mb4_bin DEFAULT '{}',
  `synced_at` bigint(20) DEFAULT '0',
  PRIMARY KEY (`group_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.eblogin_credentials
CREATE TABLE IF NOT EXISTS `weblogin_credentials` (
  `player_name` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `password_hash` text COLLATE utf8mb4_bin NOT NULL,
  `salt` text COLLATE utf8mb4_bin NOT NULL,
  `temp_password_hash` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `temp_pw_expire` bigint(20) DEFAULT '0',
  `pushed_at` bigint(20) NOT NULL,
  PRIMARY KEY (`player_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanjava.eblogin_tokens
CREATE TABLE IF NOT EXISTS `weblogin_tokens` (
  `player_name` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `web_token` text COLLATE utf8mb4_bin NOT NULL,
  `created_at` bigint(20) NOT NULL,
  `expire_seconds` bigint(20) DEFAULT '600',
  `registered` bigint(20) DEFAULT '1',
  PRIMARY KEY (`player_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- ================= aoyuanphp =================

-- aoyuanphp.install_lock
CREATE TABLE IF NOT EXISTS `_install_lock` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `installed_at` varchar(32) NOT NULL,
  `ip` varchar(45) NOT NULL DEFAULT '',
  `mode` varchar(16) NOT NULL DEFAULT 'mysql',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4;

-- aoyuanphp.dmin_sessions
CREATE TABLE IF NOT EXISTS `admin_sessions` (
  `session_id` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `created_at` bigint(20) NOT NULL,
  `expires_at` bigint(20) NOT NULL,
  PRIMARY KEY (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanphp.ashier_orders
CREATE TABLE IF NOT EXISTS `cashier_orders` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `order_no` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `operator_type` varchar(500) COLLATE utf8mb4_bin DEFAULT 'cashier',
  `operator_name` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `player_name` text COLLATE utf8mb4_bin NOT NULL,
  `items_detail` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `subtotal` bigint(20) DEFAULT '0',
  `total_price` bigint(20) DEFAULT '0',
  `discount_percent` bigint(20) DEFAULT '0',
  `discount_amount` bigint(20) DEFAULT '0',
  `settlement` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `payment_method` varchar(500) COLLATE utf8mb4_bin DEFAULT 'bond',
  `status` varchar(500) COLLATE utf8mb4_bin DEFAULT 'completed',
  `created_at` bigint(20) DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanphp.ashiers
CREATE TABLE IF NOT EXISTS `cashiers` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `username` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `password_hash` text COLLATE utf8mb4_bin NOT NULL,
  `salt` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `discount_limit_percent` bigint(20) DEFAULT '0',
  `created_at` bigint(20) DEFAULT '0',
  `created_by` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `can_cash` bigint(20) DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanphp.nline_snapshots
CREATE TABLE IF NOT EXISTS `online_snapshots` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `snapshot_time` bigint(20) NOT NULL,
  `online_count` bigint(20) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_snap_time` (`snapshot_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanphp.ay_orders
CREATE TABLE IF NOT EXISTS `pay_orders` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `out_trade_no` varchar(500) COLLATE utf8mb4_bin DEFAULT NULL,
  `trade_no` text COLLATE utf8mb4_bin,
  `player_name` text COLLATE utf8mb4_bin NOT NULL,
  `tier_id` bigint(20) DEFAULT '0',
  `money` text COLLATE utf8mb4_bin,
  `bond_amount` bigint(20) DEFAULT '0',
  `status` varchar(500) COLLATE utf8mb4_bin DEFAULT 'created',
  `name` text COLLATE utf8mb4_bin,
  `platform_sign` text COLLATE utf8mb4_bin,
  `created_at` bigint(20) DEFAULT NULL,
  `paid_at` bigint(20) DEFAULT NULL,
  `submit_params` text COLLATE utf8mb4_bin,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanphp.ending_player_validations
CREATE TABLE IF NOT EXISTS `pending_player_validations` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `player_name` text COLLATE utf8mb4_bin NOT NULL,
  `request_type` varchar(500) COLLATE utf8mb4_bin DEFAULT 'general',
  `request_data` varchar(500) COLLATE utf8mb4_bin DEFAULT '{}',
  `status` varchar(500) COLLATE utf8mb4_bin DEFAULT 'pending',
  `created_at` bigint(20) DEFAULT '0',
  `validated_at` bigint(20) DEFAULT '0',
  `result` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanphp.eb_bond_records
CREATE TABLE IF NOT EXISTS `web_bond_records` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `player_name` text COLLATE utf8mb4_bin NOT NULL,
  `amount` bigint(20) DEFAULT '0',
  `record_type` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `recorded_by` varchar(500) COLLATE utf8mb4_bin DEFAULT 'system',
  `description` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `created_at` bigint(20) DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanphp.eb_group_renew
CREATE TABLE IF NOT EXISTS `web_group_renew` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `player_name` text COLLATE utf8mb4_bin NOT NULL,
  `group_name` text COLLATE utf8mb4_bin NOT NULL,
  `renew_price` bigint(20) DEFAULT '0',
  `duration_minutes` bigint(20) DEFAULT '0',
  `req_id` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `status` varchar(500) COLLATE utf8mb4_bin DEFAULT 'pending',
  `created_at` bigint(20) DEFAULT '0',
  `processed_at` bigint(20) DEFAULT '0',
  `remark` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanphp.eb_item_sn
CREATE TABLE IF NOT EXISTS `web_item_sn` (
  `sn` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `item_type` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `owner` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `status` varchar(500) COLLATE utf8mb4_bin DEFAULT 'active',
  `issue_time` bigint(20) DEFAULT '0',
  `loc_type` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `loc_player` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `loc_world` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `loc_x` bigint(20) DEFAULT '0',
  `loc_y` bigint(20) DEFAULT '0',
  `loc_z` bigint(20) DEFAULT '0',
  `container_type` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `in_land` bigint(20) DEFAULT '0',
  `land_name` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `last_seen` bigint(20) DEFAULT '0',
  `cancel_time` bigint(20) DEFAULT '0',
  `lost_count` bigint(20) DEFAULT '0',
  `lost_state` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `remark` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `bind_reason` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `bind_time` bigint(20) DEFAULT '0',
  `updated_at` bigint(20) DEFAULT '0',
  `detached_at` bigint(20) DEFAULT '0',
  `own_chest` bigint(20) DEFAULT '0',
  PRIMARY KEY (`sn`),
  KEY `idx_web_item_sn_owner` (`owner`(191),`item_type`(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanphp.eb_land_owner_changes
CREATE TABLE IF NOT EXISTS `web_land_owner_changes` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `land_id` bigint(20) NOT NULL,
  `land_name` text COLLATE utf8mb4_bin NOT NULL,
  `old_owner` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `new_owner` text COLLATE utf8mb4_bin NOT NULL,
  `created_at` bigint(20) DEFAULT '0',
  `synced` bigint(20) DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanphp.eb_land_transfers
CREATE TABLE IF NOT EXISTS `web_land_transfers` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `land_name` text COLLATE utf8mb4_bin NOT NULL,
  `old_owner` text COLLATE utf8mb4_bin NOT NULL,
  `new_owner` text COLLATE utf8mb4_bin NOT NULL,
  `status` varchar(500) COLLATE utf8mb4_bin DEFAULT 'pending',
  `created_at` bigint(20) DEFAULT '0',
  `completed_at` bigint(20) DEFAULT '0',
  `expires_at` bigint(20) DEFAULT '0',
  `cooldown_until` bigint(20) DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanphp.eb_sn_commands
CREATE TABLE IF NOT EXISTS `web_sn_commands` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `cmd` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `sn` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `player` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `item_type` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `reason` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `is_force` bigint(20) DEFAULT '0',
  `link_type` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `link_id` bigint(20) DEFAULT '0',
  `status` varchar(500) COLLATE utf8mb4_bin DEFAULT 'pending',
  `result` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `created_at` bigint(20) DEFAULT '0',
  `sent_at` bigint(20) DEFAULT '0',
  `done_at` bigint(20) DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_web_sn_cmd_status` (`status`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanphp.eb_sn_cooldown
CREATE TABLE IF NOT EXISTS `web_sn_cooldown` (
  `player` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `until` bigint(20) DEFAULT '0',
  PRIMARY KEY (`player`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanphp.eb_sn_event
CREATE TABLE IF NOT EXISTS `web_sn_event` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `action` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `sn` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `player` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `detail` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `created_at` bigint(20) DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanphp.eb_sn_held
CREATE TABLE IF NOT EXISTS `web_sn_held` (
  `sn` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `player` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `holder` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `owner` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `item_type` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `held` bigint(20) DEFAULT '0',
  `time` bigint(20) DEFAULT '0',
  PRIMARY KEY (`sn`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanphp.eb_sn_log
CREATE TABLE IF NOT EXISTS `web_sn_log` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `sn` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `item_type` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `action` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `player` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `detail` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `time` bigint(20) DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=372 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanphp.eb_sn_lost
CREATE TABLE IF NOT EXISTS `web_sn_lost` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `sn` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `player` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `reason` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `status` varchar(500) COLLATE utf8mb4_bin DEFAULT 'checking',
  `result` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `report_count` bigint(20) DEFAULT '1',
  `created_at` bigint(20) DEFAULT '0',
  `handled_at` bigint(20) DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_web_sn_lost_sn` (`sn`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanphp.eb_sn_stock
CREATE TABLE IF NOT EXISTS `web_sn_stock` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `sn` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `item_type` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `action` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `world` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `x` bigint(20) DEFAULT '0',
  `y` bigint(20) DEFAULT '0',
  `z` bigint(20) DEFAULT '0',
  `container_type` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `in_land` bigint(20) DEFAULT '0',
  `land_name` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `player` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `time` bigint(20) DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_web_sn_stock_sn` (`sn`,`time`)
) ENGINE=InnoDB AUTO_INCREMENT=41 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanphp.eb_ticket_replies
CREATE TABLE IF NOT EXISTS `web_ticket_replies` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `ticket_id` bigint(20) NOT NULL,
  `sender` text COLLATE utf8mb4_bin NOT NULL,
  `role` varchar(500) COLLATE utf8mb4_bin DEFAULT 'user',
  `message` text COLLATE utf8mb4_bin NOT NULL,
  `created_at` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_web_ticket_replies_ticket` (`ticket_id`),
  KEY `idx_wtr_ticket` (`ticket_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanphp.eb_tickets
CREATE TABLE IF NOT EXISTS `web_tickets` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `type` text COLLATE utf8mb4_bin NOT NULL,
  `status` varchar(500) COLLATE utf8mb4_bin DEFAULT 'submitted',
  `requester` text COLLATE utf8mb4_bin NOT NULL,
  `assigned_to` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `title` text COLLATE utf8mb4_bin NOT NULL,
  `description` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `images` varchar(500) COLLATE utf8mb4_bin DEFAULT '[]',
  `reject_reason` varchar(500) COLLATE utf8mb4_bin DEFAULT '',
  `created_at` bigint(20) NOT NULL,
  `updated_at` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_web_tickets_requester` (`requester`(191)),
  KEY `idx_web_tickets_status` (`status`),
  KEY `idx_web_tickets_assigned` (`assigned_to`),
  KEY `idx_wt_requester` (`requester`(191)),
  KEY `idx_wt_status` (`status`),
  KEY `idx_wt_assigned` (`assigned_to`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- aoyuanphp.eb_twofa_qr
CREATE TABLE IF NOT EXISTS `web_twofa_qr` (
  `token` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `player` text COLLATE utf8mb4_bin NOT NULL,
  `otpauth` text COLLATE utf8mb4_bin NOT NULL,
  `expires_at` bigint(20) NOT NULL,
  PRIMARY KEY (`token`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
