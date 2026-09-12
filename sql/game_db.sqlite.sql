-- SQLite 版建表脚本(与 sql/game_db.sql 的 MySQL 版结构一致)
--
-- 使用方式:设置 DB_URL=jdbc:sqlite:./data/game.db 后由应用启动时自动建表;
-- 也可手动执行: sqlite3 game.db < game_db.sqlite.sql
-- 说明:类型统一映射为 INTEGER / TEXT / NUMERIC;索引用独立 CREATE INDEX;
--       全部带 IF NOT EXISTS,重复执行不会破坏已有数据。

--
-- Table structure for table `t_competitor`
--

CREATE TABLE IF NOT EXISTS `t_competitor` (
  `id` INTEGER NOT NULL,
  `tenant_id` INTEGER NOT NULL,
  `tournament_id` INTEGER NOT NULL,
  `stage_id` INTEGER NOT NULL,
  `source_competitor_id` INTEGER,
  `source_stage_id` INTEGER,
  `from_roster` INTEGER NOT NULL DEFAULT 0,
  `entry_tag` TEXT,
  `type` INTEGER DEFAULT 0,
  `name` TEXT,
  `number` TEXT,
  `seed_rank` INTEGER,
  `final_rank` INTEGER,
  `outcome_status` TEXT,
  `create_by` INTEGER,
  `create_time` TEXT,
  `update_by` INTEGER,
  `update_time` TEXT,
  `remark` TEXT,
  PRIMARY KEY (`id`)
);

--
-- Table structure for table `t_competitor_member`
--

CREATE TABLE IF NOT EXISTS `t_competitor_member` (
  `id` INTEGER NOT NULL,
  `tenant_id` INTEGER NOT NULL,
  `tournament_id` INTEGER NOT NULL,
  `competitor_id` INTEGER NOT NULL,
  `player_id` INTEGER NOT NULL,
  `role` TEXT DEFAULT 'MEMBER',
  `create_by` INTEGER,
  `create_time` TEXT,
  `update_by` INTEGER,
  `update_time` TEXT,
  `remark` TEXT,
  PRIMARY KEY (`id`)
);

CREATE INDEX IF NOT EXISTS `idx_comp` ON `t_competitor_member` (`competitor_id`);
CREATE INDEX IF NOT EXISTS `idx_player` ON `t_competitor_member` (`player_id`);
-- 同一参赛方内同一选手唯一(防并发签到/重复 JOIN 产生重复成员)
CREATE UNIQUE INDEX IF NOT EXISTS `uk_competitor_member` ON `t_competitor_member` (`competitor_id`, `player_id`);

--
-- Table structure for table `t_match`
--

CREATE TABLE IF NOT EXISTS `t_match` (
  `id` INTEGER NOT NULL,
  `tenant_id` INTEGER NOT NULL,
  `tournament_id` INTEGER NOT NULL,
  `stage_id` INTEGER NOT NULL,
  `name` TEXT,
  `display_zone` TEXT,
  `display_row` INTEGER,
  `display_col` INTEGER,
  `status` TEXT NOT NULL DEFAULT 'PENDING',
  `match_mode` TEXT DEFAULT 'STANDARD',
  `promotion_rule` TEXT,
  `result_json` TEXT,
  `create_by` INTEGER,
  `create_time` TEXT,
  `update_by` INTEGER,
  `update_time` TEXT,
  `remark` TEXT,
  PRIMARY KEY (`id`)
);

--
-- Table structure for table `t_match_participant`
--

CREATE TABLE IF NOT EXISTS `t_match_participant` (
  `id` INTEGER NOT NULL,
  `tenant_id` INTEGER NOT NULL,
  `tournament_id` INTEGER NOT NULL,
  `match_id` INTEGER NOT NULL,
  `competitor_id` INTEGER NOT NULL,
  `display_slot_index` INTEGER,
  `score_value` NUMERIC,
  `rank_in_match` INTEGER,
  `outcome_status` TEXT,
  `create_by` INTEGER,
  `create_time` TEXT,
  `update_by` INTEGER,
  `update_time` TEXT,
  `remark` TEXT,
  PRIMARY KEY (`id`)
);

CREATE INDEX IF NOT EXISTS `idx_match_score` ON `t_match_participant` (`match_id`, `score_value`);

--
-- Table structure for table `t_match_round`
--

CREATE TABLE IF NOT EXISTS `t_match_round` (
  `id` INTEGER NOT NULL,
  `tenant_id` INTEGER NOT NULL,
  `tournament_id` INTEGER NOT NULL,
  `match_id` INTEGER NOT NULL,
  `round_sequence` INTEGER NOT NULL,
  `competitor_id` INTEGER,
  `status` TEXT NOT NULL DEFAULT 'PENDING',
  `create_by` INTEGER,
  `create_time` TEXT,
  `update_by` INTEGER,
  `update_time` TEXT,
  `remark` TEXT,
  PRIMARY KEY (`id`)
);

--
-- Table structure for table `t_player`
--

CREATE TABLE IF NOT EXISTS `t_player` (
  `id` INTEGER NOT NULL,
  `tenant_id` INTEGER NOT NULL,
  `tournament_id` INTEGER NOT NULL,
  `name` TEXT NOT NULL,
  `avatar` TEXT,
  `id_card` TEXT,
  `competitor_id` INTEGER,
  `tags` TEXT,
  `create_by` INTEGER,
  `create_time` TEXT,
  `update_by` INTEGER,
  `update_time` TEXT,
  `remark` TEXT,
  PRIMARY KEY (`id`)
);

--
-- Table structure for table `t_referee`
--

CREATE TABLE IF NOT EXISTS `t_referee` (
  `id` INTEGER NOT NULL,
  `tenant_id` INTEGER NOT NULL,
  `tournament_id` INTEGER NOT NULL,
  `name` TEXT NOT NULL,
  `avatar` TEXT,
  `auth_key` TEXT,
  `permissions` TEXT,
  `create_by` INTEGER,
  `create_time` TEXT,
  `update_by` INTEGER,
  `update_time` TEXT,
  `remark` TEXT,
  PRIMARY KEY (`id`)
);

--
-- Table structure for table `t_referee_stage`
--

CREATE TABLE IF NOT EXISTS `t_referee_stage` (
  `id` INTEGER NOT NULL,
  `tenant_id` INTEGER NOT NULL,
  `referee_id` INTEGER NOT NULL,
  `tournament_id` INTEGER NOT NULL,
  `stage_id` INTEGER NOT NULL,
  `create_by` INTEGER,
  `create_time` TEXT,
  `update_by` INTEGER,
  `update_time` TEXT,
  `remark` TEXT,
  PRIMARY KEY (`id`)
);

CREATE UNIQUE INDEX IF NOT EXISTS `uk_referee_stage` ON `t_referee_stage` (`referee_id`, `stage_id`);
CREATE INDEX IF NOT EXISTS `idx_referee_id` ON `t_referee_stage` (`referee_id`);
CREATE INDEX IF NOT EXISTS `idx_stage_id` ON `t_referee_stage` (`stage_id`);
CREATE INDEX IF NOT EXISTS `idx_tournament_id` ON `t_referee_stage` (`tournament_id`);

--
-- Table structure for table `t_match_referee`
--

CREATE TABLE IF NOT EXISTS `t_match_referee` (
  `id` INTEGER NOT NULL,
  `tenant_id` INTEGER NOT NULL,
  `match_id` INTEGER NOT NULL,
  `referee_id` INTEGER NOT NULL,
  `tournament_id` INTEGER NOT NULL,
  `create_by` INTEGER,
  `create_time` TEXT,
  `update_by` INTEGER,
  `update_time` TEXT,
  `remark` TEXT,
  PRIMARY KEY (`id`)
);

CREATE UNIQUE INDEX IF NOT EXISTS `uk_match_referee` ON `t_match_referee` (`match_id`, `referee_id`);
CREATE INDEX IF NOT EXISTS `idx_match_referee_match_id` ON `t_match_referee` (`match_id`);
CREATE INDEX IF NOT EXISTS `idx_match_referee_referee_id` ON `t_match_referee` (`referee_id`);
CREATE INDEX IF NOT EXISTS `idx_match_referee_tournament_id` ON `t_match_referee` (`tournament_id`);

--
-- Table structure for table `t_round_score`
--

CREATE TABLE IF NOT EXISTS `t_round_score` (
  `id` INTEGER NOT NULL,
  `tenant_id` INTEGER NOT NULL,
  `tournament_id` INTEGER NOT NULL,
  `round_id` INTEGER NOT NULL,
  `competitor_id` INTEGER NOT NULL,
  `action` TEXT,
  `referee_id` INTEGER,
  `score` NUMERIC,
  `dimension` TEXT DEFAULT 'MAIN',
  `create_by` INTEGER,
  `create_time` TEXT,
  `update_by` INTEGER,
  `update_time` TEXT,
  `remark` TEXT,
  PRIMARY KEY (`id`)
);
-- 同一轮次同一裁判同一选手同一维度唯一(防并发重复提交/重复投票)
CREATE UNIQUE INDEX IF NOT EXISTS `uk_round_score` ON `t_round_score` (`round_id`, `referee_id`, `competitor_id`, `dimension`);

--
-- Table structure for table `t_stage`
--

CREATE TABLE IF NOT EXISTS `t_stage` (
  `id` INTEGER NOT NULL,
  `tenant_id` INTEGER NOT NULL,
  `tournament_id` INTEGER NOT NULL,
  `prev_stage_id` INTEGER,
  `next_stage_id` INTEGER,
  `parent_stage_id` INTEGER,
  `name` TEXT,
  `stage_mode` TEXT NOT NULL,
  `members` INTEGER DEFAULT 1,
  `visual_col_index` INTEGER,
  `rule_config` TEXT,
  `roster_config_json` TEXT,
  `roster_applied` INTEGER DEFAULT 0,
  `roster_skipped` INTEGER DEFAULT 0,
  `status` TEXT DEFAULT 'DRAFT',
  `team_count_start` INTEGER DEFAULT 0,
  `team_count_end` INTEGER DEFAULT 0,
  `is_initialized` INTEGER DEFAULT 0,
  `visual_config` TEXT,
  `create_by` INTEGER,
  `create_time` TEXT,
  `update_by` INTEGER,
  `update_time` TEXT,
  `remark` TEXT,
  PRIMARY KEY (`id`)
);

--
-- Table structure for table `t_tournament`
--

CREATE TABLE IF NOT EXISTS `t_tournament` (
  `id` INTEGER NOT NULL,
  `tenant_id` INTEGER NOT NULL,
  `name` TEXT NOT NULL,
  `auth_key` TEXT,
  `cover_image` TEXT,
  `description` TEXT,
  `status` INTEGER DEFAULT 0,
  `logical_width` INTEGER DEFAULT 1920,
  `logical_height` INTEGER DEFAULT 1080,
  `theme_config` TEXT,
  `create_by` INTEGER,
  `create_time` TEXT,
  `update_by` INTEGER,
  `update_time` TEXT,
  `remark` TEXT,
  PRIMARY KEY (`id`)
);

--
-- Table structure for table `t_vis_scene`
--

CREATE TABLE IF NOT EXISTS `t_vis_scene` (
  `id` INTEGER NOT NULL,
  `tenant_id` INTEGER NOT NULL,
  `tournament_id` INTEGER NOT NULL,
  `name` TEXT NOT NULL,
  `design_width` INTEGER DEFAULT 1920,
  `design_height` INTEGER DEFAULT 1080,
  `format` TEXT DEFAULT 'DEFAULT',
  `bg_color` TEXT DEFAULT '#000000',
  `sort_order` INTEGER DEFAULT 0,
  `create_by` INTEGER,
  `create_time` TEXT,
  `update_by` INTEGER,
  `update_time` TEXT,
  `remark` TEXT,
  PRIMARY KEY (`id`)
);

--
-- Table structure for table `t_vis_widget`
--

CREATE TABLE IF NOT EXISTS `t_vis_widget` (
  `id` INTEGER NOT NULL,
  `tenant_id` INTEGER NOT NULL,
  `tournament_id` INTEGER NOT NULL,
  `scene_id` INTEGER NOT NULL,
  `name` TEXT,
  `type` TEXT NOT NULL,
  `layout_config` TEXT NOT NULL,
  `x` INTEGER DEFAULT 0,
  `y` INTEGER DEFAULT 0,
  `w` INTEGER DEFAULT 400,
  `h` INTEGER DEFAULT 200,
  `z_index` INTEGER DEFAULT 1,
  `visible` INTEGER DEFAULT 1,
  `locked` INTEGER DEFAULT 0,
  `data_config` TEXT,
  `render_config` TEXT,
  `create_by` INTEGER,
  `create_time` TEXT,
  `update_by` INTEGER,
  `update_time` TEXT,
  `remark` TEXT,
  PRIMARY KEY (`id`)
);

CREATE INDEX IF NOT EXISTS `idx_scene` ON `t_vis_widget` (`scene_id`);
CREATE INDEX IF NOT EXISTS `idx_type` ON `t_vis_widget` (`type`);

--
-- Table structure for table `t_login_account`
--

CREATE TABLE IF NOT EXISTS `t_login_account` (
  `id` INTEGER NOT NULL,
  `username` TEXT NOT NULL,
  `password` TEXT NOT NULL,
  `create_time` TEXT,
  `update_time` TEXT,
  PRIMARY KEY (`id`)
);

CREATE UNIQUE INDEX IF NOT EXISTS `uk_username` ON `t_login_account` (`username`);

--
-- Table structure for table `t_stage_roster_override`
--

CREATE TABLE IF NOT EXISTS `t_stage_roster_override` (
  `id` INTEGER NOT NULL,
  `tenant_id` INTEGER NOT NULL,
  `tournament_id` INTEGER NOT NULL,
  `target_stage_id` INTEGER NOT NULL,
  `op` TEXT NOT NULL,
  `source_competitor_id` INTEGER,
  `player_id` INTEGER,
  `guest_name` TEXT,
  `guest_type` INTEGER NOT NULL DEFAULT 0,
  `guest_number` TEXT,
  `seed_rank` INTEGER,
  `create_by` INTEGER,
  `create_time` TEXT,
  `update_by` INTEGER,
  `update_time` TEXT,
  `remark` TEXT,
  PRIMARY KEY (`id`)
);

CREATE INDEX IF NOT EXISTS `idx_override_target` ON `t_stage_roster_override` (`target_stage_id`);
CREATE INDEX IF NOT EXISTS `idx_override_tournament` ON `t_stage_roster_override` (`tournament_id`);
