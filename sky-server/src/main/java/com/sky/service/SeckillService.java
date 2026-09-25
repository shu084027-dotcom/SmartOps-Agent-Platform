package com.sky.service;

public interface SeckillService {

    /**
     * 预热库存：把某个菜品的库存写入 Redis
     *
     * @param dishId 菜品id
     * @param stock  初始库存数量
     */
    void initStock(Long dishId, int stock);

    /**
     * 抢购扣减库存（Lua 原子扣减）
     *
     * @param dishId 菜品id
     * @param buyNum 购买数量
     * @return 剩余库存；-1 库存未初始化；-2 库存不足
     */
    long deductStock(Long dishId, int buyNum);

    /**
     * 回补库存（下单失败时把 Redis 中已扣减的库存加回去）
     *
     * @param dishId 菜品id
     * @param num    回补数量
     */
    void addStock(Long dishId, int num);

    /**
     * 正式下单扣减：Redis Lua 原子扣减 + MySQL 乐观锁兜底 + 失败回补
     *
     * @param dishId 菜品id
     * @param num    购买数量
     * @return true 扣减成功；false 库存不足
     */
    boolean deductStockWithDb(Long dishId, int num);
}
