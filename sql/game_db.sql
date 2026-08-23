-- MySQL dump 10.13  Distrib 8.0.45, for macos26.2 (arm64)
--
-- Host: 127.0.0.1    Database: game_db
-- ------------------------------------------------------
-- Server version	8.0.45

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `gen_table`
--

DROP TABLE IF EXISTS `t_competitor`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_competitor` (
  `id` bigint NOT NULL,
  `tenant_id` bigint NOT NULL,
  `tournament_id` bigint NOT NULL,
  `stage_id` bigint NOT NULL,
  `source_competitor_id` bigint DEFAULT NULL COMMENT '上一阶段的CompetitorID',
  `type` tinyint DEFAULT '0' COMMENT '0:个人, 1:队伍',
  `name` varchar(50) DEFAULT NULL COMMENT '展示名称',
  `number` varchar(20) DEFAULT NULL COMMENT '参赛号',
  `seed_rank` int DEFAULT NULL COMMENT '本赛段初始种子顺位',
  `final_rank` int DEFAULT NULL COMMENT '本赛段最终排名',
  `outcome_status` varchar(20) DEFAULT NULL COMMENT '本赛段结果: ADVANCE/ELIMINATED/PENDING/WITHDRAWN',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='参赛单位表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `t_competitor_member`
--

DROP TABLE IF EXISTS `t_competitor_member`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_competitor_member` (
  `id` bigint NOT NULL,
  `tenant_id` bigint NOT NULL,
  `tournament_id` bigint NOT NULL,
  `competitor_id` bigint NOT NULL,
  `player_id` bigint NOT NULL,
  `role` varchar(20) DEFAULT 'MEMBER' COMMENT 'CAPTAIN, MEMBER',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  KEY `idx_comp` (`competitor_id`),
  KEY `idx_player` (`player_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='参赛成员关联表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `t_match`
--

DROP TABLE IF EXISTS `t_match`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_match` (
  `id` bigint NOT NULL,
  `tenant_id` bigint NOT NULL,
  `tournament_id` bigint NOT NULL,
  `stage_id` bigint NOT NULL,
  `name` varchar(100) DEFAULT NULL,
  `display_zone` varchar(10) DEFAULT NULL COMMENT 'LEFT, RIGHT, CENTER',
  `display_row` int DEFAULT NULL COMMENT 'Y轴排序',
  `display_col` int DEFAULT NULL,
  `status` enum('PENDING','GAMING','SETTLED') NOT NULL DEFAULT 'PENDING',
  `match_mode` varchar(20) DEFAULT 'STANDARD' COMMENT 'STANDARD, VOTING, RANKING',
  `promotion_rule` json DEFAULT NULL,
  `result_json` varchar(1000) DEFAULT NULL COMMENT '手动公布模式待公布结果(competitorId->WIN/LOSS/DRAW)',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='比赛场次';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `t_match_participant`
--

DROP TABLE IF EXISTS `t_match_participant`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_match_participant` (
  `id` bigint NOT NULL,
  `tenant_id` bigint NOT NULL,
  `tournament_id` bigint NOT NULL,
  `match_id` bigint NOT NULL,
  `competitor_id` bigint NOT NULL,
  `display_slot_index` int DEFAULT NULL,
  `score_value` decimal(10,2) DEFAULT NULL COMMENT '总分/票数',
  `rank_in_match` int DEFAULT NULL COMMENT '本场排名',
  `outcome_status` varchar(20) DEFAULT NULL COMMENT '选手结果: WIN/LOSS/DRAW/PENDING',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  KEY `idx_match_score` (`match_id`,`score_value` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='场次参赛人员记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `t_match_round`
--

DROP TABLE IF EXISTS `t_match_round`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_match_round` (
  `id` bigint NOT NULL,
  `tenant_id` bigint NOT NULL,
  `tournament_id` bigint NOT NULL,
  `match_id` bigint NOT NULL,
  `round_sequence` int NOT NULL,
  `competitor_id` bigint DEFAULT NULL COMMENT '参赛方ID(海选赛每人一轮)',
  `status` enum('PENDING','GAMING','SETTLED') NOT NULL DEFAULT 'PENDING',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='比赛轮次';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `t_player`
--

DROP TABLE IF EXISTS `t_player`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_player` (
  `id` bigint NOT NULL,
  `tenant_id` bigint NOT NULL,
  `tournament_id` bigint NOT NULL,
  `name` varchar(50) NOT NULL,
  `avatar` varchar(255) DEFAULT NULL,
  `id_card` varchar(50) DEFAULT NULL COMMENT '身份唯一标识',
  `competitor_id` bigint DEFAULT NULL COMMENT '首赛段参赛者',
  `tags` json DEFAULT NULL COMMENT '标签: ["种子", "外卡"]',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='选手自然人表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `t_referee`
--

DROP TABLE IF EXISTS `t_referee`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_referee` (
  `id` bigint NOT NULL,
  `tenant_id` bigint NOT NULL,
  `tournament_id` bigint NOT NULL,
  `name` varchar(50) NOT NULL,
  `avatar` varchar(255) DEFAULT NULL COMMENT '头像URL',
  `auth_key` varchar(128) DEFAULT NULL COMMENT '登录凭证',
  `permissions` json DEFAULT NULL COMMENT '权限: ["STAGE_1_GROUP_A"]',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='裁判表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `t_referee_stage`
--

DROP TABLE IF EXISTS `t_referee_stage`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_referee_stage` (
  `id` bigint NOT NULL COMMENT '主键ID',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `referee_id` bigint NOT NULL COMMENT '裁判ID',
  `tournament_id` bigint NOT NULL COMMENT '赛事ID(冗余,方便查询)',
  `stage_id` bigint NOT NULL COMMENT '赛段ID',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_referee_stage` (`referee_id`,`stage_id`),
  KEY `idx_referee_id` (`referee_id`),
  KEY `idx_stage_id` (`stage_id`),
  KEY `idx_tournament_id` (`tournament_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='裁判-赛段关联表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `t_round_score`
--

DROP TABLE IF EXISTS `t_round_score`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_round_score` (
  `id` bigint NOT NULL,
  `tenant_id` bigint NOT NULL,
  `tournament_id` bigint NOT NULL,
  `round_id` bigint NOT NULL,
  `competitor_id` bigint NOT NULL,
  `action` enum('SCORE','VOTE') DEFAULT NULL,
  `referee_id` bigint DEFAULT NULL,
  `score` decimal(10,2) DEFAULT NULL,
  `dimension` varchar(20) DEFAULT 'MAIN',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='轮次打分结果';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `t_stage`
--

DROP TABLE IF EXISTS `t_stage`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_stage` (
  `id` bigint NOT NULL,
  `tenant_id` bigint NOT NULL,
  `tournament_id` bigint NOT NULL,
  `prev_stage_id` bigint DEFAULT NULL COMMENT '上一赛段ID',
  `next_stage_id` bigint DEFAULT NULL COMMENT '下一赛段ID (可修改以实现途中变轨)',
  `parent_stage_id` bigint DEFAULT NULL COMMENT '父ID (用于同分加赛)',
  `name` varchar(50) DEFAULT NULL COMMENT '32进16 / 复活赛',
  `stage_mode` varchar(20) NOT NULL COMMENT 'AUDITION, KNOCKOUT, GROUP, ARENA, RANK',
  `members` int DEFAULT '1' COMMENT '每队选手数量',
  `visual_col_index` int DEFAULT NULL COMMENT '在大图中处于第几列 (X轴)',
  `rule_config` json DEFAULT NULL,
  `status` enum('DRAFT','PENDING','GAMING','SETTLED','DISCARD') DEFAULT 'DRAFT' COMMENT '状态',
  `team_count_start` int DEFAULT '0' COMMENT '起始队伍数量',
  `team_count_end` int DEFAULT '0' COMMENT '晋级队伍数量',
  `is_initialized` tinyint DEFAULT '0' COMMENT '是否完成初始化配置：0-否 1-是',
  `visual_config` json DEFAULT NULL COMMENT '视觉配置：{"color": "#f59e0b", "icon": "trophy"}',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='赛段流程';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `t_tournament`
--

DROP TABLE IF EXISTS `t_tournament`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_tournament` (
  `id` bigint NOT NULL,
  `tenant_id` bigint NOT NULL,
  `name` varchar(100) NOT NULL COMMENT '赛事名称',
  `auth_key` varchar(128) DEFAULT NULL,
  `cover_image` varchar(500) DEFAULT NULL COMMENT '封面图片URL',
  `description` varchar(500) DEFAULT NULL COMMENT '详情',
  `status` tinyint DEFAULT '0' COMMENT '0:筹备 1:进行中 2:结束',
  `logical_width` int DEFAULT '1920' COMMENT '设计稿宽度',
  `logical_height` int DEFAULT '1080' COMMENT '设计稿高度',
  `theme_config` json DEFAULT NULL COMMENT '{"bgColor": "#000", "fontFamily": "Roboto"}',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='赛事主表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `t_vis_scene`
--

DROP TABLE IF EXISTS `t_vis_scene`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_vis_scene` (
  `id` bigint NOT NULL,
  `tenant_id` bigint NOT NULL,
  `tournament_id` bigint NOT NULL,
  `name` varchar(50) NOT NULL COMMENT '场景名: 总决赛KV / 竖屏比分',
  `design_width` int DEFAULT '1920',
  `design_height` int DEFAULT '1080',
  `format` varchar(20) DEFAULT 'DEFAULT' COMMENT '场景格式：DEFAULT / VERTICAL / CUSTOM',
  `bg_color` varchar(20) DEFAULT '#000000' COMMENT '背景颜色',
  `sort_order` int DEFAULT '0' COMMENT '场景排序',
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='可视化场景配置';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `t_vis_widget`
--

DROP TABLE IF EXISTS `t_vis_widget`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_vis_widget` (
  `id` bigint NOT NULL,
  `tenant_id` bigint NOT NULL,
  `tournament_id` bigint NOT NULL,
  `scene_id` bigint NOT NULL,
  `name` varchar(50) DEFAULT NULL COMMENT '控件备注',
  `type` varchar(20) NOT NULL COMMENT 'BRACKET, SCOREBOARD, PLAYER_CARD, IMAGE',
  `layout_config` json NOT NULL,
  `x` int DEFAULT '0' COMMENT 'X坐标',
  `y` int DEFAULT '0' COMMENT 'Y坐标',
  `w` int DEFAULT '400' COMMENT '宽度',
  `h` int DEFAULT '200' COMMENT '高度',
  `z_index` int DEFAULT '1' COMMENT 'Z轴层级',
  `visible` tinyint DEFAULT '1' COMMENT '是否可见：0-隐藏 1-显示',
  `locked` tinyint DEFAULT '0' COMMENT '是否锁定：0-否 1-是（锁定后不可编辑）',
  `data_config` json DEFAULT NULL,
  `render_config` json DEFAULT NULL,
  `create_by` bigint DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` bigint DEFAULT NULL COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  KEY `idx_scene` (`scene_id`),
  KEY `idx_type` (`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='场景控件元素表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `t_login_account`
--

DROP TABLE IF EXISTS `t_login_account`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_login_account` (
  `id` bigint NOT NULL,
  `username` varchar(50) NOT NULL COMMENT '登录账号',
  `password` varchar(100) NOT NULL COMMENT 'BCrypt 加密后的登录密码',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统登录账号表(修改密码后持久化)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `test_demo`
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-08-10 10:16:42
