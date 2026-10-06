-- oads 集中更换核验行 —— schema（chen-026）
-- 区局核收这一档第三回：头两回都是手工绕开顶一阵，这一版把口径在账上定死。
-- 链路：集中更换核验行（本表）收讫立批；核验完成后可经 /oadsSpotBook/bringOut 带出成名册行
--       （点位名册五检查点那套本体不动）。两边共用同一批次码，核验行是"核验与收费"这一侧的准账。
--
-- 定死的口径（改口径必须连同本注释、CheckRowConsts、裁决处一起改）：
-- 1) 没有行数上限这回事，多多益善；HARD_ROW_LIMIT=100000 只是防呆硬界，越过才整批退回返 -1。
-- 2) 批次码是批的唯一身份：同批交两回（不管区级报上来还是市局自核、新建还是修改入口）幂等——
--    不另立批、行不重复入，dup_count 加一、落 RESEND 流水，回执照原批行账重出。
-- 3) 版本取舍：批次身份、来路(put_kind)、收费年度(fee_year)一律以"首回收讫版"为准冻结；
--    修改入口交的修改版只改行内容（落 MODIFY 流水），不重定来路、不重算年度。
-- 4) 收费年度：整批一个年度，按首回收讫那一刻所在年度冻结在批账（fee_year），
--    冗余到每行；整册核收、逐行落去向、收费册归集都只读这一个值，
--    同一处点位不会在整册核收里算本年度、到逐行落去向又算上一年度。
-- 5) 行落地情形只有 1已收下 / 2已退回 两值（0未勾是异常存量），列表/详情/收费册/回执四处
--    对状态的判断同一个谓词（CheckRowConsts.isReturned），不各写各的。
-- 6) 每行结论带 judge_source：指得出依"新建/校验第N条"还是"修改/校验第N条"判的，
--    核查时说得出是哪一段、依哪一套标准。
-- 7) 批内同一点位编码的重复明细先合并成一条再裁决（merged_row_nos 记下并进来的原报行次），
--    不允许同批两条都入库；合并发生在判定之前，一条有错只退这一条，不连坐别行。
-- 列名与基线实体契约（@TableName/@TableField）逐列对齐，改列必须同步实体。
-- 库：chen_026

-- ---------------------------------------------------------------- 批账：一批一行，批次码唯一
CREATE TABLE IF NOT EXISTS t_oads_check_batch (
  id bigint NOT NULL COMMENT '主键',
  batch_no varchar(64) NOT NULL COMMENT '集中更换批次码（批的唯一身份）',
  put_kind varchar(16) NOT NULL COMMENT '来路 DISTRICT区级报上来 CITY市局自核（只记头一回那一条，冻结）',
  counted_rows int NOT NULL COMMENT '进门点物理行数（报文几行记几行，合并前原报条数）',
  landed_rows int NOT NULL COMMENT '合并后入账行数（行账条数）',
  merged_rows int NOT NULL DEFAULT 0 COMMENT '批内同码合并掉的明细条数（counted-landed）',
  accepted_rows int NOT NULL DEFAULT 0 COMMENT '收下行数（回库逐行数，非退回即收下；不抄页面临值）',
  returned_rows int NOT NULL DEFAULT 0 COMMENT '退回行数（回库逐行数）；收下+退回=入账行数',
  fee_year int NOT NULL COMMENT '收费年度（按首回收讫时刻所在年度冻结，整批一个年度）',
  fee_year_basis varchar(255) DEFAULT NULL COMMENT '年度口径说法原文（凭哪一刻、怎么定的）',
  dup_count int NOT NULL DEFAULT 0 COMMENT '同一批次码重复交回数（第二回起每回加一，行不重复入）',
  received_at datetime(3) NOT NULL COMMENT '首回收讫那一刻（冻结字段的版本基准）',
  status int DEFAULT 0 COMMENT '批情形 0已收讫',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id),
  UNIQUE KEY uk_check_batch_batch (batch_no),
  KEY idx_check_batch_year (fee_year, del_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='集中更换核验批账（一批一行，批次码唯一）';

-- ---------------------------------------------------------------- 核验行（旧表补列：CREATE IF NOT EXISTS 不补列，已建过的库手跑下面 ALTER）
-- 旧表 t_oads_check_row 原本就有；这里给出全量表定义（新库直接建）与老库升级语句。
CREATE TABLE IF NOT EXISTS t_oads_check_row (
  id bigint NOT NULL COMMENT '主键',
  batch_no varchar(64) DEFAULT NULL COMMENT '集中更换批次码',
  row_no int DEFAULT NULL COMMENT '原报行次（合并组取该组最小原报行次，按它定位失败行，不按循环下标）',
  item_code varchar(64) DEFAULT NULL COMMENT '被对上的点位码（批内同码明细合并成一条）',
  qty decimal(12,2) DEFAULT NULL COMMENT '本行应更换处数',
  chk_num decimal(12,2) DEFAULT NULL COMMENT '本行核验合格处数',
  put_kind varchar(32) DEFAULT NULL COMMENT '来路（区级报上来／市局自核；冻结自批账头一回）',
  status int DEFAULT NULL COMMENT '行落地情形 0未勾 1已收下 2已退回（只此一套，四处同谓词）',
  fee_year int DEFAULT NULL COMMENT '收费年度（冗余自批账，整批同一年度；落去向直接读本列）',
  judge_source varchar(64) DEFAULT NULL COMMENT '判定来源 新建/校验第N条 或 修改/校验第N条',
  reason varchar(500) DEFAULT NULL COMMENT '逐句原因原文（多条分号隔，哪行哪条一句句回查）',
  merged_row_nos varchar(255) DEFAULT NULL COMMENT '本批内同码并入本行的其余原报行次（逗号隔）',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id),
  UNIQUE KEY uk_check_row_pos (batch_no, row_no),
  KEY idx_check_row_status (batch_no, status),
  KEY idx_check_row_code (item_code),
  KEY idx_check_row_year (fee_year, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='集中更换核验行';

-- 老库升级（已建过 t_oads_check_row 的库手跑这五句；逐句可重复执行前先确认列不存在）：
-- ALTER TABLE t_oads_check_row ADD COLUMN fee_year int DEFAULT NULL COMMENT '收费年度（冗余自批账）' AFTER status;
-- ALTER TABLE t_oads_check_row ADD COLUMN judge_source varchar(64) DEFAULT NULL COMMENT '判定来源' AFTER fee_year;
-- ALTER TABLE t_oads_check_row ADD COLUMN reason varchar(500) DEFAULT NULL COMMENT '逐句原因原文' AFTER judge_source;
-- ALTER TABLE t_oads_check_row ADD COLUMN merged_row_nos varchar(255) DEFAULT NULL COMMENT '批内同码并进来的其余原报行次' AFTER reason;
-- ALTER TABLE t_oads_check_row ADD UNIQUE KEY uk_check_row_pos (batch_no, row_no);

-- ---------------------------------------------------------------- 核验流水（append-only）
CREATE TABLE IF NOT EXISTS t_oads_check_flow (
  id bigint NOT NULL COMMENT '主键',
  batch_no varchar(64) NOT NULL COMMENT '哪一批（批次码）',
  action varchar(16) NOT NULL COMMENT '动作 CREATE新建收讫 MODIFY修改落版 RESEND重复交 RECEIPT出回执 MERGE同码合并',
  entry_kind varchar(16) DEFAULT NULL COMMENT '入口 CREATE新建 MODIFY修改（RESEND 记第二回走的入口）',
  put_kind varchar(16) DEFAULT NULL COMMENT '来路 DISTRICT/CITY（RESEND 记第二回走的来路；首回冻结在批账）',
  total_rows int DEFAULT NULL COMMENT '本笔物理行数（合并前）',
  landed_rows int DEFAULT NULL COMMENT '本笔入账行数（合并后）',
  accepted_rows int DEFAULT NULL COMMENT '本笔收下行数',
  returned_rows int DEFAULT NULL COMMENT '本笔退回行数',
  content varchar(500) DEFAULT NULL COMMENT '这一笔的交代（哪几条合并、重送不重复入、以哪版为准，原文）',
  operator varchar(64) DEFAULT NULL COMMENT '经办岗',
  acted_at datetime(3) NOT NULL COMMENT '落账时刻（毫秒）',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_check_flow_batch (batch_no, acted_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='集中更换核验流水（append-only）';

-- ---------------------------------------------------------------- 菜单与权限（幂等，已存在同 id 则跳过）
-- 权限码与 OadsCheckRowController 上的 @RequiresPermissions 逐字对齐。
INSERT INTO t_sys_permission (id, name, descripion, url, is_blank, pid, perms, type, icon, order_num, visible, create_by, create_time)
SELECT 9400, '集中更换核验行核收', '区局核收：新建/修改两入口、回执、收费册归集', '/oadsCheckRow/view', 0, 0, 'oads:oadsCheckRow:view', 1, 'layui-icon-form', 94, 0, 'seed', NOW()
WHERE NOT EXISTS (SELECT 1 FROM t_sys_permission WHERE id = 9400);
INSERT INTO t_sys_permission (id, name, descripion, url, is_blank, pid, perms, type, icon, order_num, visible, create_by, create_time)
SELECT 9401, '新建批核收', '新建入口自有的一套校验；一批一次，幂等重送', '/oadsCheckRow/submitNew', 0, 9400, 'oads:oadsCheckRow:submit', 2, 'fa fa-inbox', NULL, 0, 'seed', NOW()
WHERE NOT EXISTS (SELECT 1 FROM t_sys_permission WHERE id = 9401);
INSERT INTO t_sys_permission (id, name, descripion, url, is_blank, pid, perms, type, icon, order_num, visible, create_by, create_time)
SELECT 9402, '修改批核收', '修改入口另写的一套校验；只改行内容，来路/年度以首回为准', '/oadsCheckRow/submitModify', 0, 9400, 'oads:oadsCheckRow:submit', 2, 'fa fa-edit', NULL, 0, 'seed', NOW()
WHERE NOT EXISTS (SELECT 1 FROM t_sys_permission WHERE id = 9402);
INSERT INTO t_sys_permission (id, name, descripion, url, is_blank, pid, perms, type, icon, order_num, visible, create_by, create_time)
SELECT 9403, '核验批回执', '物理行/入账/收下/退回回库逐行数，逐行带判定来源', '/oadsCheckRow/receipt', 0, 9400, 'oads:oadsCheckRow:view', 2, 'fa fa-file-text-o', NULL, 0, 'seed', NOW()
WHERE NOT EXISTS (SELECT 1 FROM t_sys_permission WHERE id = 9403);
INSERT INTO t_sys_permission (id, name, descripion, url, is_blank, pid, perms, type, icon, order_num, visible, create_by, create_time)
SELECT 9404, '退回行明细', '只列退回行（与详情/收费册同一状态谓词）', '/oadsCheckRow/listErrors', 0, 9400, 'oads:oadsCheckRow:list', 2, 'layui-icon-cols', NULL, 0, 'seed', NOW()
WHERE NOT EXISTS (SELECT 1 FROM t_sys_permission WHERE id = 9404);
INSERT INTO t_sys_permission (id, name, descripion, url, is_blank, pid, perms, type, icon, order_num, visible, create_by, create_time)
SELECT 9405, '核验行详情', '单行详情：结论/原因/判定来源/收费年度与列表同口径', '/oadsCheckRow/detail', 0, 9400, 'oads:oadsCheckRow:list', 2, 'fa fa-search', NULL, 0, 'seed', NOW()
WHERE NOT EXISTS (SELECT 1 FROM t_sys_permission WHERE id = 9405);
INSERT INTO t_sys_permission (id, name, descripion, url, is_blank, pid, perms, type, icon, order_num, visible, create_by, create_time)
SELECT 9406, '收费册年度归集', '按冻结收费年度归集落去向，与整册核收同一个年度', '/oadsCheckRow/feeBook', 0, 9400, 'oads:oadsCheckRow:list', 2, 'fa fa-book', NULL, 0, 'seed', NOW()
WHERE NOT EXISTS (SELECT 1 FROM t_sys_permission WHERE id = 9406);
