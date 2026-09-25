# 智营云商家运营平台 · SmartOps-Agent

> 一款融合 **大模型 AI 智能体（AI Agent）** 的商家运营管理平台，让管理者通过自然语言对话即可完成日常运营与数据查询。

## 项目结构

```
.
├── sky-take-out/              # 后端服务 (Spring Boot 3，Java 17)
└── project-sky-admin-vue-ts/  # 管理端前端 (Vue 2 + TypeScript)
```

> **快速导航：**
>
> - **[进入后端项目](sky-take-out)**
> - **[进入前端项目](project-sky-admin-vue-ts)**

## 1. 项目概述

智营云商家运营平台（SmartOps-Agent）是一套面向商家日常经营场景的一体化管理平台，覆盖商品、订单、会员、员工、数据报表等核心运营链路。

区别于传统的「菜单 + 按钮」式后台，本项目的核心竞争力在于内置了一个 **AI 智能助手**：平台将业务能力封装为大模型可调用的工具，管理者无需记忆复杂的操作路径，直接通过自然语言下达指令，AI 即可自动理解意图、调用对应业务接口并返回结果，真正实现「**用对话替代操作**」的智能运营体验。

这不仅仅是一个业务系统，更是一次 **Java 后端与前沿 AI 技术深度融合的工程实践**。

---

## 2. 核心亮点：AI 智能助手

平台在管理端引入了一个 AI 智能助手聊天窗口，将复杂的后台操作转变为与 AI 对话的简单过程。

### 演示效果

如果看不到演示效果，动画地址：./sky-take-out/动画.gif

<p align="center">
  <img src="sky-take-out/动画.gif" alt="AI 助手演示" width="80%">
</p>

### 实现亮点

1. **自然语言驱动的运营管理**：管理者不再需要点击繁琐的菜单与按钮，可直接通过自然语言向 AI 助手下达指令。例如：

   * “帮我查一下今天有多少订单？”
   * “给我看一下 ID 为 1 的员工详细信息。”
   * “今天从上午 10 点到下午 3 点的营业额是多少？”

2. **RAG 私有知识库增强**：除了执行业务操作，AI 助手还能回答平台自身或企业内部的制度类问题。通过检索增强生成（RAG）技术，将位于 `resources/knowledge` 目录下的私有文档（如员工手册、运营规范、常见问题等）加载并向量化。用户提问时，系统先检索最相关的知识片段，再结合大模型生成能力给出精准、有依据的回答。

3. **上下文感知的多轮对话**：AI 助手能够理解对话上下文。在查询完员工列表后，可直接追问“那第二个员工是谁？”，AI 能准确理解并继续执行。

4. **智能工具调用（Function Calling）**：这是整个 AI 助手的核心技术。平台将 Service 层方法封装为可供大模型调用的工具（Tools），AI 理解用户意图后，自动选择并调用对应的 Java 方法（如 `getOrderSummary`、`getEmployeeById` 等），并将查询结果以自然语言反馈给用户。

5. **流式响应（Streaming）**：AI 回复采用流式输出，文字如打字机般逐字显示，显著降低等待焦虑，提升交互体验。

### 技术挑战与解决方案

在将 AI 集成进传统 Spring Boot 项目时，攻克了若干关键技术难点：

* **`ThreadLocal` 上下文丢失问题**：`LangChain4j` 的异步特性会导致工具调用运行在独立线程中，无法访问主请求线程里存储的用户登录信息（`ThreadLocal`）。

  * **解决方案**：发起 AI 请求时，动态地将当前管理员 ID 作为上下文注入到 Prompt 中，并重构工具（Tools）的调用方式，让大模型从上下文解析 ID 并作为参数传入，彻底解耦 AI 执行逻辑与 Web 线程上下文的绑定。

* **同步阻塞与异步流式处理的融合**：将后端同步的 Service 方法调用，与前端要求的异步流式聊天体验相结合。

  * **解决方案**：基于 `Project Reactor` 的 `Flux` 响应式编程模型，将后端数据处理与流式输出解耦，实现流畅的打字机效果。

---

## 3. 平台功能

### 管理端

- 员工管理
- 分类管理
- 菜品 / 商品管理
- 套餐管理
- 订单管理
- 工作台数据统计
- AI 智能助手

### 用户端

- 微信授权登录
- 浏览商品、菜品
- 购物车操作
- 地址管理
- 下单与支付
- 历史订单查看

---

## 4. 技术栈

### 后端

* **基础框架**：Spring Boot 3.2.2（Java 17）、Spring MVC、MyBatis、Spring Task
* **AI 集成**：LangChain4j 1.0.0-beta3、DashScope（通义千问 qwen-plus / text-embedding-v3）
* **流式/响应式**：Project Reactor、Spring WebFlux（`Flux<String>` 流式输出）
* **数据库**：MySQL 8、Druid 连接池、PageHelper 分页、MongoDB（聊天记忆存储）
* **缓存/分布式锁**：Redis、Redisson
* **API 文档**：Knife4j / Springdoc OpenAPI 3
* **其他**：JWT 认证、WebSocket（来单/催单提醒）、阿里云 OSS、微信支付 V3、POI（Excel 报表）、百度地图

### 前端

* **框架**：Vue 2.6 + TypeScript + vue-property-decorator
* **UI 组件库**：Element UI
* **状态管理 / 路由**：Vuex + Vue Router
* **图表**：ECharts
* **构建工具**：Vue CLI 3（webpack）

---

## 5. 秒杀功能：Redis + Lua 原子扣减防超卖

平台内置了一套秒杀库存扣减能力，用于演示高并发场景下如何避免库存超卖。

### 实现要点

1. **Redis + Lua 原子扣减**：将「判断库存是否充足 + 扣减」封装为单个 Lua 脚本，利用 Redis 单线程执行脚本的特性保证原子性，从根源杜绝超卖。
2. **定时任务预热库存**：`SeckillTask` 在活动开始前将 MySQL 中的菜品库存预热到 Redis，抢购时全程只读 Redis，避免瞬时流量冲击数据库。
3. **MySQL 乐观锁兜底**：扣减成功后，再用条件更新 `UPDATE dish SET stock = stock - n WHERE id = ? AND stock >= n` 做最终一致性校验；扣减失败则回补 Redis 库存，两层都不允许超卖。
4. **雪花算法全局唯一订单号**：`SnowFlakeUtil` 生成全局唯一 ID 替代原有的毫秒时间戳，避免高并发下订单号冲突。

### 核心代码位置

- Lua 脚本：`sky-server/src/main/resources/lua/deductStock.lua`
- 秒杀服务：`SeckillService` / `SeckillServiceImpl`
- 预热任务：`sky-server/src/main/java/com/sky/task/SeckillTask.java`
- 唯一 ID：`sky-common/src/main/java/com/sky/utils/SnowFlakeUtil.java`


---

## 6. 准备工作

* 安装 MySQL
* 安装 Redis
* 安装 MongoDB
* 配置微信小程序：[微信公众平台](https://mp.weixin.qq.com/)
* 配置阿里云 OSS 对象存储：[阿里云 OSS](https://www.aliyun.com/product/oss)
* 配置阿里百炼平台大模型：[阿里百炼](https://bailian.console.aliyun.com/?tab=model#/model-market)

---

## 7. 环境变量配置

后端通过 `sky-server/src/main/resources/application-dev.yml` 以 `${...}` 占位符读取环境变量，**启动前必须配置**（可在 IDE 运行配置、系统环境变量或启动脚本中注入）。

### 后端环境变量

| 变量名 | 说明 | 示例 |
|--------|------|------|
| `API_KEY` | 阿里百炼 DashScope 的 API Key（通义千问） | `sk-xxx` |
| `SKY_DB_HOST` / `SKY_DB_PORT` / `SKY_DB_NAME` / `SKY_DB_USERNAME` / `SKY_DB_PASSWORD` | MySQL 连接信息 | `localhost` / `3306` / `smartops` / `root` / `123456` |
| `SKY_REDIS_HOST` / `SKY_REDIS_PORT` / `SKY_REDIS_PASSWORD` / `SKY_REDIS_DB` | 业务 Redis 连接信息 | `localhost` / `6379` / 空 / `0` |
| `SKY_MONGO_HOST` / `SKY_MONGO_PORT` / `SKY_MONGO_DB` | MongoDB（聊天记忆）连接信息 | `localhost` / `27017` / `smartops` |
| `LANGCHAIN_REDIS_HOST` / `LANGCHAIN_REDIS_PORT` / `LANGCHAIN_REDIS_USER` / `LANGCHAIN_REDIS_PASSWORD` | LangChain4j 向量库 Redis 连接信息 | `localhost` / `6379` / 空 / 空 |
| `SKY_OSS_ACCESS_KEY_ID` / `SKY_OSS_ACCESS_KEY_SECRET` / `SKY_OSS_ENDPOINT` / `SKY_OSS_BUCKET_NAME` | 阿里云 OSS 对象存储 | `xxx` / `xxx` / `oss-cn-xxx.aliyuncs.com` / `smartops-bucket` |
| `SKY_WX_*` | 微信小程序 / 微信支付 V3 相关配置 | 见微信公众平台 |
| `SKY_SHOP_ADDRESS` / `SKY_BAIDU_AK` | 店铺地址 / 百度地图 AK | `xxx` / `xxx` |

> 说明：Redis 在本项目中承担三类职责——业务缓存、Redisson 分布式锁、LangChain4j 向量存储，若使用不同实例可分别配置。

### 前端环境变量

前端通过 `project-sky-admin-vue-ts/.env.development` 配置后端地址：

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| `VUE_APP_URL` | 后端服务地址（`/api` 代理目标） | `http://localhost:8080/admin` |
| `VUE_APP_SOCKET_URL` | WebSocket 地址 | `ws://localhost:8080/ws/` |

---

## 8. 快速开始

### 8.1 准备环境

* JDK 17+
* Maven 3.8+
* Node.js + npm（或 yarn）
* MySQL 8、Redis、MongoDB

### 8.2 初始化数据库

执行建库建表脚本，导入项目所需的表结构（可参考 `sky-pojo` 模块 `entity` 包下的实体类自行建表）。

### 8.3 启动后端

```bash
cd sky-take-out
mvn clean install -DskipTests
mvn -pl sky-server spring-boot:run
```

或在 IDE 中配置好环境变量后直接运行 `SkyApplication`。

启动成功后：

* 后端接口：`http://localhost:8080`
* API 文档（Knife4j）：`http://localhost:8080/doc.html`

### 8.4 启动前端

```bash
cd project-sky-admin-vue-ts
npm install
npm run serve
```

浏览器访问 `http://localhost:8888`。

### 8.5 使用 AI 智能助手

1. 在[阿里百炼平台](https://bailian.console.aliyun.com/)开通 DashScope，获取 `API_KEY` 并配置到环境变量；
2. 启动后进入管理端「智能助手」页面，即可通过自然语言与 AI 对话，例如查询订单、员工、营业额等；
3. RAG 私有知识库文档位于 `sky-server/src/main/resources/knowledge/`，可自行扩充。

---

## 9. 本地服务启动备忘

启动 Redis（默认端口 6379）：

```powershell
redis-server.exe redis.windows.conf
```

MongoDB 默认端口为 27017。
