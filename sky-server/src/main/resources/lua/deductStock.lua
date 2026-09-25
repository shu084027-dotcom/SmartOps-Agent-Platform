-- KEYS[1]：库存 key（如 seckill:stock:1）
-- ARGV[1]：本次扣减数量
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil then
    return -1        -- 库存未初始化（没有预热）
end
if stock < tonumber(ARGV[1]) then
    return -2        -- 库存不足
end
return redis.call('DECRBY', KEYS[1], ARGV[1])   -- 原子扣减，返回剩余库存
