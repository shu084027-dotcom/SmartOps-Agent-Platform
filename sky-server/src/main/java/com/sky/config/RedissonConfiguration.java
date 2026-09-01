package com.sky.config;

import com.sky.properties.RedisProperties;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;



@Configuration
public class RedissonConfiguration {

    //Spring 会做对象本身的销毁（调用 bean 生命周期的销毁回调），但是不会自动帮你关闭第三方资源（网络连接、连接池）。RedissonClient里面维护了一套Redis TCP 连接池，大量长连接占着系统资源。
    /*RedisTemplate底层连接管理交给了RedisConnectionFactory，而 Spring 官方的RedisConnectionFactory本身实现了 Spring 的DisposableBean接口。
      Spring 容器关闭会自动调用它的destroy()，自动关闭连接池，所以我们不用额外配置。
      RedissonClient 是第三方组件，没有实现 Spring 的 DisposableBean 接口，Spring 不知道该调用哪个方法释放资源，因此需要手动指定 destroyMethod = "shutdown"。*/
    @Bean(destroyMethod = "shutdown")//Spring 容器关闭的时候（项目停止、IDEA 停止运行），Spring 会调用这个 Bean 对象的 shutdown()方法。
    public RedissonClient redissonClient(RedisProperties redisProperties){
        //1.创建redisson的config对象
        Config config = new Config();

        //2.配置单机redis的连接信息（给config的成员变量singleServerConfig设置访问地址、数据库、密码）
        SingleServerConfig singleServerConfig = config.useSingleServer()//在外层的Config对象内部，new一个SingleServerConfig，作为config的成员变量
                .setAddress("redis://" + redisProperties.getHost() + ":" + redisProperties.getPort())
                .setDatabase(redisProperties.getDatabase());
        //密码可能没有（null）也可能是空字符串（isBlank）
        if (redisProperties.getPassword() != null && !redisProperties.getPassword().isBlank()){
            singleServerConfig.setPassword(redisProperties.getPassword());
        }

        //3.返回redisson客户端实例，返回的是RedissonClient类型
        return Redisson.create(config);
    }
}
