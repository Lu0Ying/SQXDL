-- ============================================================
-- SQXDL 答辩演示脚本（顺序即演示顺序，每段前注释为讲解词提示）
--
-- 用法一（GUI 演示，推荐）：在 SwingDemo 输入框中逐段粘贴执行，中文显示正常
-- 用法二（CLI 交互）：run-cli.bat 启动后逐条粘贴
-- 用法三（脚本文件模式，仅自测）：整跑本文件，命令见根目录 README.md「快速开始」
--       （mvn -pl executor exec:java -Dexec.mainClass=com.sqxdl.executor.Main
--         -Dexec.args="-f 演示脚本.sql"；管道模式下控制台中文可能乱码，程序本身无问题）
--
-- 前提：演示前先清空数据目录（把 C:\SQXDL\data\ 下的 catalog.json 和
--       storage.db 改名移走），保证从全新库开始，本脚本不使用 DROP
--       （DROP 后立刻重建同名表存在目录同步问题，勿在演示中使用）。
-- 说明：存储核心启动时会自动创建 4 张内置示例表（course/score/student/
--       teacher，其中内置 student 为 4 列含 grade），因此本脚本使用
--       student_info / score_info 两个独立表名演示，show tables 会看到 5 张表。
-- ============================================================

-- 【第 1 段】建表 —— 指导书示例语句
CREATE TABLE student_info(id INT, name VARCHAR, age INT);

-- 【第 2 段】重复建表 → 演示错误处理（表已存在）
CREATE TABLE student_info(id INT, name VARCHAR, age INT);

-- 【第 3 段】插入数据（建 4 行，覆盖 WHERE 条件的真假两侧）
INSERT INTO student_info(id,name,age) VALUES (1,'Alice',20);
INSERT INTO student_info(id,name,age) VALUES (2,'Bob',19);
INSERT INTO student_info(id,name,age) VALUES (3,'Carol',22);
INSERT INTO student_info(id,name,age) VALUES (4,'Dave',17);

-- 【第 4 段】条件查询 —— 指导书示例语句（应返回 3 行，Dave 17 岁被过滤）
SELECT id,name FROM student_info WHERE age > 18;
SELECT * FROM student_info;

-- 【第 5 段】删除 + 再查 —— 验证删除生效（Alice 消失，剩 3 行）
DELETE FROM student_info WHERE id = 1;
SELECT * FROM student_info;

-- 【第 6 段】错误场景演示 —— 指导书规定的错误测试点，逐条展示报错信息
SELECT nosuchcol FROM student_info;                           -- 列不存在（若带纠错提示，重点展示）
INSERT INTO student_info(id,name,age) VALUES (5,'Eve');       -- 值个数与列数不一致
INSERT INTO student_info(id,name,age) VALUES (5,'Eve','abc'); -- 类型不匹配
SELECT * FROM student_info WHERE age > 18                     -- 缺分号
SELECT * FROM student_info WHERE name = 'Alice;               -- 未闭合字符串

-- 【第 7 段】扩展能力 —— 指导书可选扩展全部落地
SELECT * FROM student_info ORDER BY age DESC;                 -- ORDER BY
SELECT age, COUNT(*) FROM student_info GROUP BY age;          -- GROUP BY + 聚合
CREATE TABLE score_info(sid INT, course VARCHAR, grade INT);
INSERT INTO score_info(sid,course,grade) VALUES (2,'DB',85);
INSERT INTO score_info(sid,course,grade) VALUES (3,'OS',90);
SELECT student_info.name, score_info.course, score_info.grade FROM student_info JOIN score_info ON student_info.id = score_info.sid;

-- 【第 8 段】debug 元命令（仅 GUI/CLI 交互模式演示，勿放进脚本）
-- 输入: debug   → 开启调试输出（Token 流 → AST → 语义结果 → Plan 树）
-- 再执行一条 SELECT 观察各阶段产物，最后再输 debug 关闭

-- 【第 9 段】持久化验证 —— 重启程序（GUI 关掉重开 / CLI 重新启动）后执行：
-- SELECT * FROM student_info;
-- show tables;
