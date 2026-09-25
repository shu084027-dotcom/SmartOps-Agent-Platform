package com.sky.controller.user;

import com.sky.result.Result;
import com.sky.service.SeckillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController("userSeckillController")
@RequestMapping("/user/seckill")
@Tag(name = "秒杀接口")
public class SeckillController {

    @Autowired
    private SeckillService seckillService;

    /**
     * 预热库存（演示用，正式场景是活动开始时由定时任务/后台触发）
     */
    @PostMapping("/init/{dishId}")
    @Operation(summary = "预热库存")
    public Result<String> init(@PathVariable Long dishId, @RequestParam int stock) {
        seckillService.initStock(dishId, stock);
        return Result.success("库存预热成功");
    }

    /**
     * 抢购扣减库存
     */
    @PostMapping("/deduct/{dishId}")
    @Operation(summary = "抢购扣减库存")
    public Result<String> deduct(@PathVariable Long dishId, @RequestParam(defaultValue = "1") int num) {
        long result = seckillService.deductStock(dishId, num);
        if (result == -2) {
            return Result.error("库存不足");
        }
        if (result == -1) {
            return Result.error("活动未开始");
        }
        return Result.success("抢购成功，剩余库存：" + result);
    }
}
