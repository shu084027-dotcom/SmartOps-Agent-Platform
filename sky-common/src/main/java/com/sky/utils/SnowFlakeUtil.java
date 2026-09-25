package com.sky.utils;

/**
 * 雪花算法：生成全局唯一 ID（用于订单号等）
 * <p>
 * 结构（共 64 位）：
 * 1 位符号位 | 41 位时间戳 | 5 位数据中心 ID | 5 位机器 ID | 12 位序列号
 * <p>
 * 唯一性保证：单机内不同毫秒靠时间戳区分、同一毫秒靠序列号自增区分；
 * 多机部署靠数据中心 ID + 机器 ID 区分。
 */
public class SnowFlakeUtil {

    // 起始时间戳（2024-01-01 00:00:00），可自行调整
    private static final long START_TIMESTAMP = 1704067200000L;

    // 各部分占用的位数
    private static final long SEQUENCE_BITS = 12L;        // 序列号 12 位
    private static final long WORKER_ID_BITS = 5L;        // 机器 ID 5 位
    private static final long DATA_CENTER_ID_BITS = 5L;   // 数据中心 ID 5 位

    // 各部分向左的位移量
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long DATA_CENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATA_CENTER_ID_BITS;

    // 序列号最大值（12 位全 1 = 4095）
    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);

    // 单机部署时固定使用 1 号机器、1 号数据中心
    private static final long WORKER_ID = 1L;
    private static final long DATA_CENTER_ID = 1L;

    // 当前毫秒内的序列号
    private static long sequence = 0L;
    // 上一次生成 ID 的时间戳
    private static long lastTimestamp = -1L;

    /**
     * 生成下一个全局唯一 ID
     */
    public static synchronized long nextId() {
        long timestamp = System.currentTimeMillis();

        // 时钟回拨检测：时间戳比上一次还小，说明系统时间被回拨，直接抛异常避免生成重复 ID
        if (timestamp < lastTimestamp) {
            throw new RuntimeException("时钟回拨，拒绝生成 ID");
        }

        if (timestamp == lastTimestamp) {
            // 同一毫秒内，序列号自增
            sequence = (sequence + 1) & MAX_SEQUENCE;
            // 序列号溢出（本毫秒 4096 个用完），等待下一毫秒
            if (sequence == 0) {
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            // 进入新的一毫秒，序列号归零
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        // 拼接：时间戳 | 数据中心 | 机器 | 序列号
        return ((timestamp - START_TIMESTAMP) << TIMESTAMP_SHIFT)
                | (DATA_CENTER_ID << DATA_CENTER_ID_SHIFT)
                | (WORKER_ID << WORKER_ID_SHIFT)
                | sequence;
    }

    /**
     * 忙等到下一毫秒
     */
    private static long tilNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }
}
