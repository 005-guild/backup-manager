UPDATE backup_rule
SET note = '每月1日备份；OceanProtect副本接口'
WHERE kind = 'monthly';

UPDATE backup_rule
SET note = '每年12月31日备份；永久保留；OceanProtect副本接口'
WHERE kind = 'yearly';
