-- 入口试运行计数（FR-N05，Task 28）：试运行 = 限额内真实执行一次，每次提交 +1
--（MVP 决策：只计尝试次数，合格率列暂留，不计 pass）。
ALTER TABLE entry ADD COLUMN test_total INT NOT NULL DEFAULT 0;
