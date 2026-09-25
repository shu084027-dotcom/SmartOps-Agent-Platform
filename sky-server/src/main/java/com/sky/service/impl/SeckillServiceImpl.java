package com.sky.service.impl;

import com.sky.constant.RedisKeyConstant;
import com.sky.mapper.DishMapper;
import com.sky.service.SeckillService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
public class SeckillServiceImpl implements SeckillService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private DishMapper dishMapper;

    // 静态加载一次 Lua 脚本（避免每次请求都重新读文件）
    // 声明一个 静态常量 。 static 表示它属于类、不属于某个实例， 整个 JVM 只有一份 。 
    // DefaultRedisScript<Long> 是 Spring 提供的「Lua 脚本的 Java 封装对象」， <Long> 表示脚本返回值会被反序列化成 Long 。
    private static final DefaultRedisScript<Long> DEDUCT_SCRIPT;

    static {
        DEDUCT_SCRIPT = new DefaultRedisScript<>();
        DEDUCT_SCRIPT.setLocation(new ClassPathResource("lua/deductStock.lua"));
        DEDUCT_SCRIPT.setResultType(Long.class);
    }

    @Override
    public void initStock(Long dishId, int stock) {
        // 预热：把初始库存以纯字符串写入 Redis（Lua 中需 tonumber 解析，故用 StringRedisTemplate 而非 JDK 序列化）
        stringRedisTemplate.opsForValue()
                .set(RedisKeyConstant.SECKILL_STOCK_PREFIX + dishId, String.valueOf(stock));
    }

    @Override
    public long deductStock(Long dishId, int buyNum) {
        // 执行 Lua 脚本：判断库存是否充足并原子扣减，Redis 单线程执行脚本保证并发安全
        Long result = stringRedisTemplate.execute(
                DEDUCT_SCRIPT, //要执行的脚本
                Collections.singletonList(RedisKeyConstant.SECKILL_STOCK_PREFIX + dishId),//要操作的 key 列表
                String.valueOf(buyNum)//脚本参数列表ARGV
        );
        return result == null ? -1 : result;
    }

    @Override
    public void addStock(Long dishId, int num) {
        // 回补：Redis 单线程保证 INCRBY 原子，key 一定已存在（前面扣减成功才会走到回补）
        stringRedisTemplate.opsForValue()
                .increment(RedisKeyConstant.SECKILL_STOCK_PREFIX + dishId, num);
    }

    @Override
    public boolean deductStockWithDb(Long dishId, int num) {
        // 1. Redis Lua 原子扣减，先扛住并发
        long result = deductStock(dishId, num);
        if (result < 0) {
            return false; // -1 未预热 / -2 库存不足
        }

        // 2. MySQL 乐观锁扣减兜底（stock >= num 才扣减，防止最终超卖）
        int rows = dishMapper.deductStock(dishId, num);
        if (rows == 0) {
            // MySQL 库存不足（极端情况下 Redis 与 MySQL 不一致），回补 Redis 已扣的库存
            addStock(dishId, num);
            return false;
        }
        return true;
    }
}
