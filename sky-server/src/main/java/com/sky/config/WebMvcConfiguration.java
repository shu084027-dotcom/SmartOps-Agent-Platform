package com.sky.config;

import com.sky.interceptor.JwtTokenAdminInterceptor;
import com.sky.interceptor.JwtTokenUserInterceptor;
import com.sky.json.JacksonObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.*;

import java.util.List;

/**
 * 配置类，注册web层相关组件
 */
@Configuration
@Slf4j
public class WebMvcConfiguration extends WebMvcConfigurationSupport {//继承`WebMvcConfigurationSupport`，**会禁用 SpringBoot 的 WebMvc 自动配置**，所有 MVC 相关组件需要我们自己注册。
    //对比实现接口`WebMvcConfigurer`：实现接口是**扩展，不关闭自动配置**；继承类是完全接管。苍穹外卖这里选择继承。

    @Autowired
    private JwtTokenAdminInterceptor jwtTokenAdminInterceptor;
    @Autowired
    private JwtTokenUserInterceptor jwtTokenUserInterceptor;

    /**
     * 注册自定义拦截器
     *
     * - 参数`InterceptorRegistry registry`：**拦截器注册器对象，Spring 自动传入，不用自己 new**，作用：把我们写好的拦截器登记到 SpringMVC 链路。
     * - `addInterceptor(拦截器实例)`：把拦截器加入 MVC 执行链。
     * - `addPathPatterns("/admin/**")`：**哪些路径要走拦截器**，所有`/admin/`开头请求。
     * - `excludePathPatterns()`：排除路径，不走拦截器
     *   1. `/admin/employee/login`：管理员登录接口，登录还没有 token，放行
     *   2. Knife4j/Swagger 相关文档全部放行：`doc.html`、`webjars/**`、`swagger-ui/**`、`v3/api-docs/**`，否则访问接口文档会被 jwt 拦截，拿不到文档。
     *
     * >
     * > 执行顺序：先注册的拦截器优先执行。
     * > 注意：拦截器只能拦截 controller 接口，**不能拦截静态资源；不能拦截 404；拿不到 requestBody（body 已经被解析消费）**
     * @param registry
     */
    protected void addInterceptors(InterceptorRegistry registry) {
        log.info("开始注册自定义拦截器...");
        registry.addInterceptor(jwtTokenAdminInterceptor)
                .addPathPatterns("/admin/**")
                .excludePathPatterns("/admin/employee/login")
                .excludePathPatterns("/doc.html")
                .excludePathPatterns("/webjars/**")
                .excludePathPatterns("/swagger-ui/**")
                .excludePathPatterns("/v3/api-docs/**");
        registry.addInterceptor(jwtTokenUserInterceptor)
                .addPathPatterns("/user/**")
                .excludePathPatterns("/user/user/login")
                .excludePathPatterns("/user/shop/status")
                .excludePathPatterns("/doc.html")
                .excludePathPatterns("/webjars/**")
                .excludePathPatterns("/swagger-ui/**")
                .excludePathPatterns("/v3/api-docs/**");
    }

    /**
     * 配置CORS跨域
     *
     * - `CorsRegistry registry`：跨域注册器，Spring 自动传入。
     * - `addMapping("/**")`：对全部接口生效。
     * - `allowedOriginPatterns("*")`：允许所有来源域名访问；`allowedOriginPatterns`支持通配符，比旧版`allowedOrigins`更强大。
     * - `allowedMethods`：允许的请求方式，OPTIONS 是浏览器预检请求。
     * - `allowedHeaders("*")`：允许携带任意请求头（jwt 的 token 放在请求头）。
     * - `allowCredentials(true)`：允许携带 cookie、凭证。
     * - `maxAge(3600)`：浏览器预检 OPTIONS 请求缓存 1 小时，不用每次都发预检。
     *
     * >
     * > 作用：后端返回 CORS 响应头，解决浏览器同源策略跨域拦截。
     * > ⚠注意：**如果 Nginx 也配置跨域，会出现重复跨域响应头报错，二者选其一即可。**
     * @param registry
     */
    @Override
    protected void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    /**
     * 通过OpenAPI 3生成接口文档，配置Knife4j
     *
     * `@Bean`：把返回的`OpenAPI`对象注册进 IOC 容器；Knife4j（OpenAPI3）读取这个 Bean，生成接口文档页面信息。
     *
     * - title：文档标题
     * - version：接口版本
     * - description：描述
     * - contact：联系人信息
     *
     * 访问地址：`http://localhost:8080/doc.html`
     *
     * @return
     */
    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("苍穹外卖项目接口文档")
                        .version("2.0")
                        .description("苍穹外卖项目接口文档")
                        .contact(new Contact()
                                .name("开发团队")
                                .email("dev@example.com")));
    }

    /**
     * 设置静态资源映射
     * - `ResourceHandlerRegistry registry`：静态资源注册器，Spring 传入。
     * - `addResourceHandler("/doc.html**")`：浏览器访问的 url 路径。
     * - `addResourceLocations("classpath:/META‑INF/resources/")`：**资源真实所在位置，在 jar 包里面的 META‑INF 资源目录**。
     *
     * >
     * > 核心作用：
     * > Knife4j 的 doc.html 页面、js、css 都在 knife4j 依赖 jar 包内部，不在我们自己项目 resources 下。
     * > 告诉 SpringMVC：当浏览器请求`/doc.html`，去 jar 包`META‑INF/resources`下面找这个静态文件返回。
     * > 如果不写这个配置，访问 doc.html 直接 404！
     *
     * @param registry
     */
    protected void addResourceHandlers(ResourceHandlerRegistry registry) {
        log.info("开始设置静态资源映射...");
        // Knife4j相关资源
        registry.addResourceHandler("/doc.html**")
                .addResourceLocations("classpath:/META-INF/resources/");
        registry.addResourceHandler("/webjars/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/");
        // Swagger UI 相关资源
        registry.addResourceHandler("/swagger-ui/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/swagger-ui/");
        registry.addResourceHandler("/v3/api-docs/**")
                .addResourceLocations("classpath:/META-INF/resources/");
        // 静态文件资源
        registry.addResourceHandler("/favicon.ico")
                .addResourceLocations("classpath:/META-INF/resources/");
    }

    /**
     * - `List<HttpMessageConverter<?>> converters`：SpringMVC 已经有的消息转换器集合。
     *
     * >
     * > 消息转换器作用：Controller 返回对象，把 Java 对象序列化为 JSON 字符串返回给前端；接收请求时把 JSON 反序列为 Java 实体。
     *
     * 1. 手动新建`MappingJackson2HttpMessageConverter`，底层 Jackson 序列化工具。
     * 2. `setObjectMapper(new JacksonObjectMapper())`，传入自定义的`JacksonObjectMapper`。
     *
     * >
     * > 自定义 JacksonObjectMapper 做的事情：
     * > 日期格式化、时间转 Long 时间戳、枚举序列化处理、null 值处理等。解决前后端时间格式对不上的问题。
     *
     * 3. `converters.add(0,xxx)`：**加到集合索引 0，最高优先级**。SpringMVC 遍历转换器，
     * 匹配到第一个就直接使用，保证优先使用我们自定义转换器，而不是默认 Jackson 转换器。
     *
     * @param converters
     */
    @Override
    protected void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        log.info("扩展消息转换器...");
        MappingJackson2HttpMessageConverter mappingJackson2MessageConverter = new MappingJackson2HttpMessageConverter();
        mappingJackson2MessageConverter.setObjectMapper(new JacksonObjectMapper());
        converters.add(0,mappingJackson2MessageConverter);
    }
}
