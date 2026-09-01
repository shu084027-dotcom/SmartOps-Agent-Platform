package com.sky.controller.user;

import com.sky.constant.RedisKeyConstant;
import com.sky.constant.StatusConstant;
import com.sky.entity.Dish;
import com.sky.result.Result;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@RestController("userDishController")
@RequestMapping("/user/dish")
@Slf4j
@Tag(name = "菜品浏览接口")
public class DishController {
    @Autowired
    private DishService dishService;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private RedissonClient redissonClient;

    /**
     * 根据分类id查询菜品
     *
     * @param categoryId
     * @return
     */
    @GetMapping("/list")
    @Operation(summary = "根据分类id查询菜品")
    public Result<List<DishVO>> list(Long categoryId) {
        //1.参数校验
        if (categoryId == null || categoryId <= 0) {
            return Result.error("参数id不合法");
        }

        //2.查询redis
        String dishKey = RedisKeyConstant.MENU_DISH_CATEGORY_PREFIX + categoryId;
        List<DishVO> redisList = (List<DishVO>) redisTemplate.opsForValue().get(dishKey);
        if (redisList != null) {
            return Result.success(redisList);
        }

        //3.没查到--使用分布式锁
                /*
            getLock(lockKey):
            仅仅Java内存创建RLock操作对象，【不会访问Redis】，没有真正加锁；
            只是把key锁名称lock:menu:dish:category::1和redisson客户端连接保存到RLock对象中。
            lock只是 Java 内存里的操作手柄（代理对象），真正锁状态不在 JVM 内存，保存在Redis 服务器上！！
         */
        String lockKey = "lock:" + dishKey;
        RLock lock = redissonClient.getLock(lockKey);//在本地java内存创建rlock锁对象，此时没有执行加锁，得到的rlock对象是对应这个lockkey的锁对象
        //循环5次抢锁
        for (int i = 0; i < 5; i++) {
            //这里查redis，是为了防止有些进程上一次循环没有抢到锁，那么这次循环的时候可能其他线程已经完成了缓存重建，那么就再次查缓存，缓存没有再去抢锁进行缓存重建
            redisList = (List<DishVO>) redisTemplate.opsForValue().get(dishKey);
            if (redisList != null) {
                return Result.success(redisList);
            }

            boolean locked = false;//每次进入循环抢锁之前，先设置locked表示是否抢到锁，初始化为false，即默认进来循环的时候还没有抢到锁


            try {
                //如果抢锁成功：Redis 里面就会生成 lock:xxx 这个 key（hash 结构，存客户端UUID+线程Id、重入计数），锁状态正式保存在 Redis 服务端。
                //如果抢锁失败：Redis 不会新增任何 key。
                //Lua 脚本内部逻辑简化理解
                //如果锁 key 不存在 → 创建 hash，设置当前线程标识，重入计数 = 1 → 返回成功。
                //如果锁 key已经存在：
                //判断持有锁的是不是当前同一个线程：计数 +1，返回成功（可重入）。
                //如果是别的线程占有锁：直接返回抢锁失败。
                locked = lock.tryLock(0, 10, TimeUnit.SECONDS);//加锁，发送lua脚本到redis中执行加锁逻辑，成功加锁locked为true
                //抢到锁
                if (locked) {
                    //3.1 先查redis，其他之前抢到锁的线程可能已经完成了缓存重建----双重检查
                    redisList = (List<DishVO>) redisTemplate.opsForValue().get(dishKey);

                    //3.2 redis有，直接返回
                    if (redisList != null) {
                        return Result.success(redisList);
                    }

                    //3.3 redis没有，再查数据库，然后存入redis缓存中
                    Dish dish = new Dish();
                    dish.setCategoryId(categoryId);
                    dish.setStatus(StatusConstant.ENABLE);
                    List<DishVO> mysqlList = dishService.listWithFlavor(dish);

                    //3.4 数据库没有查询到内容或者查询到的是空列表，也要进行缓存，缓存时间为2-5分钟----避免缓存穿透
                    if (mysqlList == null || mysqlList.isEmpty()) {
                        int expire = ThreadLocalRandom.current().nextInt(4) + 2;
                        redisTemplate.opsForValue().set(dishKey, Collections.emptyList(), expire, TimeUnit.MINUTES);
                        return Result.success(Collections.emptyList());
                    }

                    //3.5 数据库查询到了，按随机时间作为生命周期存入redis----避免缓存雪崩
                    int expire = ThreadLocalRandom.current().nextInt(10) + 30;
                    redisTemplate.opsForValue().set(dishKey, mysqlList, expire, TimeUnit.MINUTES);
                    return Result.success(mysqlList);
                }
                //没抢到锁，等100ms再尝试
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Result.error("系统繁忙，请稍后再试");
            } finally {
                /*
                    locked：本地变量标记本次循环是否抢到锁；
                    lock.isHeldByCurrentThread()：远程访问Redis校验，锁是否还属于当前线程；
                    双重判断目的：
                    1、本地locked=false直接跳过，减少Redis网络请求；
                    2、防止锁租期超时自动释放之后，当前线程误释放其他线程持有的锁。
                 */
                if (locked && lock.isHeldByCurrentThread()){//isHeldByCurrentThread() 会向 Redis 发送网络请求，读取 Redis 上的锁数据，做判断
                    lock.unlock();
                }
            }
        }

        //循环5次都没抢到锁
        return Result.error("系统繁忙，请稍后再试");
    }

}