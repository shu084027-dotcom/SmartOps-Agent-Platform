package com.sky.task;

import com.sky.constant.StatusConstant;
import com.sky.entity.Dish;
import com.sky.mapper.DishMapper;
import com.sky.service.SeckillService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀库存预热定时任务
 */
@Component
@Slf4j
public class SeckillTask {

    @Autowired
    private DishMapper dishMapper;

    @Autowired
    private SeckillService seckillService;

    /**
     * 预热库存：每天凌晨把起售菜品的库存同步到 Redis
     * <p>
     * 正式秒杀场景应在「活动开始前」预热，这里用定时任务演示；
     * 如需快速验证，可临时把 cron 改为短间隔，例如 "0/30 * * * * ?"（每 30 秒）。
     */
    @Scheduled(cron = "0 0 0 * * ?")
    public void preheatStock() {
        log.info("开始预热秒杀库存: {}", LocalDateTime.now());

        // 查询所有起售中的菜品
        Dish query = new Dish();
        query.setStatus(StatusConstant.ENABLE);
        List<Dish> dishes = dishMapper.list(query);

        if (dishes == null || dishes.isEmpty()) {
            log.info("没有需要预热的菜品");
            return;
        }

        int count = 0;
        for (Dish dish : dishes) {
            Integer stock = dish.getStock();
            if (stock == null || stock <= 0) {
                log.warn("菜品 {} 库存未配置，跳过预热", dish.getId());
                continue;
            }
            seckillService.initStock(dish.getId(), stock);
            count++;
        }
        log.info("预热完成，共预热 {} 个菜品", count);
    }
}
