package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.github.xiaoymin.knife4j.core.util.CollectionUtils;
import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.dto.*;
import com.sky.entity.*;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.*;
import com.sky.result.PageResult;
import com.sky.service.OrderService;
import com.sky.utils.BaiDuMapUtil;
import com.sky.utils.HttpClientUtil;
import com.sky.utils.WeChatPayUtil;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import com.sky.websocket.WebSocketServer;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class OrderServiceImpl implements OrderService {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderDetailMapper orderDetailMapper;

    @Autowired
    private ShoppingCartMapper shoppingCartMapper;

    @Autowired
    private AddressBookMapper addressBookMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private WeChatPayUtil weChatPayUtil;

    @Autowired
    private WebSocketServer webSocketServer;

    @Value("${sky.shop.address}")
    private String shopAddress;

    @Value("${sky.baidu.ak}")
    private String ak;

    @Autowired
    private BaiDuMapUtil baiDuMapUtil;

    /**
     * 用户下单
     * @param ordersSubmitDTO
     * @return
     */
    @Override
    @Transactional
    public OrderSubmitVO submitOrder(OrdersSubmitDTO ordersSubmitDTO) {

        // 处理各种异常（地址薄为空，购物车为空）
        AddressBook book = addressBookMapper.getById(ordersSubmitDTO.getAddressBookId());
        if (book == null) {
            // 地址薄为空
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }

        //校验用户地址是否超出配送范围---超出的时候会跑出异常
        checkOutOfRange1(book.getProvinceName() + book.getCityName() + book.getDetail());

        ShoppingCart shoppingCart = new ShoppingCart();
        shoppingCart.setUserId(BaseContext.getCurrentId());
        List<ShoppingCart> list = shoppingCartMapper.list(shoppingCart);
        if (list == null || list.size() == 0) {
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        }

        // 向订单表插入1条数据
        Orders orders  = new Orders();
        BeanUtils.copyProperties(ordersSubmitDTO, orders);
        orders.setOrderTime(LocalDateTime.now());
        orders.setPayStatus(Orders.UN_PAID);
        orders.setStatus(Orders.PENDING_PAYMENT);
        orders.setNumber(String.valueOf(System.currentTimeMillis()));
        orders.setAddress(book.getDetail());
        orders.setPhone(book.getPhone());
        orders.setConsignee(book.getConsignee());
        orders.setUserId(BaseContext.getCurrentId());

        orderMapper.insert(orders);

        // 向订单明细表插入n条数据
        List<OrderDetail> orderDetails = new ArrayList<>();
        for (ShoppingCart cart : list) {
            OrderDetail orderDetail = new OrderDetail();
            BeanUtils.copyProperties(cart, orderDetail);
            orderDetail.setOrderId(orders.getId());
            orderDetails.add(orderDetail);
        }
        orderDetailMapper.inserBatch(orderDetails);

        // 清空当前用户的购物车
        shoppingCartMapper.deleteByUserId(BaseContext.getCurrentId());

        // 封装VO返回结果
        OrderSubmitVO orderSubmitVO = OrderSubmitVO.builder()
                .id(orders.getId())
                .orderNumber(orders.getNumber())
                .orderAmount(orders.getAmount())
                .orderTime(orders.getOrderTime())
                .build();

        return orderSubmitVO;
    }

    @Override
    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        // 当前登录用户id
        Long userId = BaseContext.getCurrentId();
        User user = userMapper.getById(userId);

        //调用微信支付接口，生成预支付交易单
//        JSONObject jsonObject = weChatPayUtil.pay(
//                ordersPaymentDTO.getOrderNumber(), //商户订单号
//                new BigDecimal(0.01), //支付金额，单位 元
//                "苍穹外卖订单", //商品描述
//                user.getOpenid() //微信用户的openid
//        );

        JSONObject jsonObject = new JSONObject();

        if (jsonObject.getString("code") != null && jsonObject.getString("code").equals("ORDERPAID")) {
            throw new OrderBusinessException("该订单已支付");
        }

        OrderPaymentVO vo = jsonObject.toJavaObject(OrderPaymentVO.class);
        vo.setPackageStr(jsonObject.getString("package"));

        paySuccess(ordersPaymentDTO.getOrderNumber());

        return vo;
    }

    @Override
    public void paySuccess(String outTradeNo) {
        // 根据订单号查询订单
        Orders ordersDB = orderMapper.getByNumber(outTradeNo);

        // 根据订单id更新订单的状态、支付方式、支付状态、结账时间
        Orders orders = Orders.builder()
                .id(ordersDB.getId())
                .status(Orders.TO_BE_CONFIRMED)
                .payStatus(Orders.PAID)
                .checkoutTime(LocalDateTime.now())
                .build();

        orderMapper.update(orders);

        Map map = new HashMap<>();

        map.put("type", 1);
        map.put("orderId", ordersDB.getId());
        map.put("content", "订单号：" + outTradeNo);

        String json = JSON.toJSONString(map);
        webSocketServer.sendToAllClient(json);
    }

    @Override
    public PageResult pageQuery4User(int pageNum, int pageSize, Integer status) {
        // 设置分页
        PageHelper.startPage(pageNum, pageSize);

        OrdersPageQueryDTO ordersPageQueryDTO = new OrdersPageQueryDTO();
        ordersPageQueryDTO.setUserId(BaseContext.getCurrentId());
        ordersPageQueryDTO.setStatus(status);

        // 分页条件查询
        Page<Orders> page = orderMapper.pageQuery(ordersPageQueryDTO);

        List<OrderVO> list = new ArrayList();

        // 查询出订单明细，并封装入OrderVO进行响应
        if (page != null && page.getTotal() > 0) {
            for (Orders orders : page) {
                Long orderId = orders.getId();// 订单id

                // 查询订单明细
                List<OrderDetail> orderDetails = orderDetailMapper.getByOrderId(orderId);

                OrderVO orderVO = new OrderVO();
                BeanUtils.copyProperties(orders, orderVO);
                orderVO.setOrderDetailList(orderDetails);

                list.add(orderVO);
            }
        }
        return new PageResult(page.getTotal(), list);
    }

    @Override
    public OrderVO details(Long id) {
        // 根据id查询订单
        Orders orders = orderMapper.getById(id);

        // 查询该订单对应的菜品/套餐明细
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orders.getId());

        // 将该订单及其详情封装到OrderVO并返回
        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(orders, orderVO);
        orderVO.setOrderDetailList(orderDetailList);

        return orderVO;
    }

    @Override
    public void userCancelById(Long id) throws Exception {
        // 根据id查询订单
        Orders ordersDB = orderMapper.getById(id);

        // 校验订单是否存在
        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        //订单状态 1待付款 2待接单 3已接单 4派送中 5已完成 6已取消
        if (ordersDB.getStatus() > 2) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders orders = new Orders();
        orders.setId(ordersDB.getId());

        // 订单处于待接单状态下取消，需要进行退款
//        if (ordersDB.getStatus().equals(Orders.TO_BE_CONFIRMED)) {
//            //调用微信支付退款接口
//            weChatPayUtil.refund(
//                    ordersDB.getNumber(), //商户订单号
//                    ordersDB.getNumber(), //商户退款单号
//                    new BigDecimal(0.01),//退款金额，单位 元
//                    new BigDecimal(0.01));//原订单金额
//
//            //支付状态修改为 退款
//            orders.setPayStatus(Orders.REFUND);
//        }

        // 更新订单状态、取消原因、取消时间
        orders.setStatus(Orders.CANCELLED);
        orders.setCancelReason("用户取消");
        orders.setCancelTime(LocalDateTime.now());
        orderMapper.update(orders);
    }

    @Override
    public void repetition(Long id) {
        // 查询当前用户id
        Long userId = BaseContext.getCurrentId();

        // 根据订单id查询当前订单详情
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(id);

        // 将订单详情对象转换为购物车对象
        List<ShoppingCart> shoppingCartList = orderDetailList.stream().map(x -> {
            ShoppingCart shoppingCart = new ShoppingCart();

            // 将原订单详情里面的菜品信息重新复制到购物车对象中
            BeanUtils.copyProperties(x, shoppingCart, "id");
            shoppingCart.setUserId(userId);
            shoppingCart.setCreateTime(LocalDateTime.now());

            return shoppingCart;
        }).collect(Collectors.toList());

        // 将购物车对象批量添加到数据库
        shoppingCartMapper.insertBatch(shoppingCartList);
    }

    /**
     * 订单搜索
     *
     * @param ordersPageQueryDTO
     * @return
     */
    public PageResult conditionSearch(OrdersPageQueryDTO ordersPageQueryDTO) {
        PageHelper.startPage(ordersPageQueryDTO.getPage(), ordersPageQueryDTO.getPageSize());

        Page<Orders> page = orderMapper.pageQuery(ordersPageQueryDTO);

        // 部分订单状态，需要额外返回订单菜品信息，将Orders转化为OrderVO
        List<OrderVO> orderVOList = getOrderVOList(page);

        return new PageResult(page.getTotal(), orderVOList);
    }

    @Override
    public OrderStatisticsVO statistics() {
        // 根据状态，分别查询出待接单、待派送、派送中的订单数量
        Integer toBeConfirmed = orderMapper.countStatus(Orders.TO_BE_CONFIRMED);
        Integer confirmed = orderMapper.countStatus(Orders.CONFIRMED);
        Integer deliveryInProgress = orderMapper.countStatus(Orders.DELIVERY_IN_PROGRESS);

        // 将查询出的数据封装到orderStatisticsVO中响应
        OrderStatisticsVO orderStatisticsVO = new OrderStatisticsVO();
        orderStatisticsVO.setToBeConfirmed(toBeConfirmed);
        orderStatisticsVO.setConfirmed(confirmed);
        orderStatisticsVO.setDeliveryInProgress(deliveryInProgress);
        return orderStatisticsVO;
    }

    @Override
    public void confirm(OrdersConfirmDTO ordersConfirmDTO) {
        Orders orders = Orders.builder()
                .id(ordersConfirmDTO.getId())
                .status(Orders.CONFIRMED)
                .build();

        orderMapper.update(orders);
    }

    @Override
    public void rejection(OrdersRejectionDTO ordersRejectionDTO) {
        // 根据id查询订单
        Orders ordersDB = orderMapper.getById(ordersRejectionDTO.getId());

        // 订单只有存在且状态为2（待接单）才可以拒单
        if (ordersDB == null || !ordersDB.getStatus().equals(Orders.TO_BE_CONFIRMED)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        //支付状态
//        Integer payStatus = ordersDB.getPayStatus();
//        if (payStatus == Orders.PAID) {
//            //用户已支付，需要退款
//            String refund = weChatPayUtil.refund(
//                    ordersDB.getNumber(),
//                    ordersDB.getNumber(),
//                    new BigDecimal(0.01),
//                    new BigDecimal(0.01));
//            log.info("申请退款：{}", refund);
//        }

        // 拒单需要退款，根据订单id更新订单状态、拒单原因、取消时间
        Orders orders = new Orders();
        orders.setId(ordersDB.getId());
        orders.setStatus(Orders.CANCELLED);
        orders.setRejectionReason(ordersRejectionDTO.getRejectionReason());
        orders.setCancelTime(LocalDateTime.now());

        orderMapper.update(orders);
    }

    @Override
    public void cancel(OrdersCancelDTO ordersCancelDTO) {
        // 根据id查询订单
        Orders ordersDB = orderMapper.getById(ordersCancelDTO.getId());

//        //支付状态
//        Integer payStatus = ordersDB.getPayStatus();
//        if (payStatus == 1) {
//            //用户已支付，需要退款
//            String refund = weChatPayUtil.refund(
//                    ordersDB.getNumber(),
//                    ordersDB.getNumber(),
//                    new BigDecimal(0.01),
//                    new BigDecimal(0.01));
//            log.info("申请退款：{}", refund);
//        }

        // 管理端取消订单需要退款，根据订单id更新订单状态、取消原因、取消时间
        Orders orders = new Orders();
        orders.setId(ordersCancelDTO.getId());
        orders.setStatus(Orders.CANCELLED);
        orders.setCancelReason(ordersCancelDTO.getCancelReason());
        orders.setCancelTime(LocalDateTime.now());
        orderMapper.update(orders);
    }

    @Override
    public void delivery(Long id) {
        // 根据id查询订单
        Orders ordersDB = orderMapper.getById(id);

        // 校验订单是否存在，并且状态为3
        if (ordersDB == null || !ordersDB.getStatus().equals(Orders.CONFIRMED)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders orders = new Orders();
        orders.setId(ordersDB.getId());
        // 更新订单状态,状态转为派送中
        orders.setStatus(Orders.DELIVERY_IN_PROGRESS);

        orderMapper.update(orders);
    }

    @Override
    public void complete(Long id) {
        // 根据id查询订单
        Orders ordersDB = orderMapper.getById(id);

        // 校验订单是否存在，并且状态为4
        if (ordersDB == null || !ordersDB.getStatus().equals(Orders.DELIVERY_IN_PROGRESS)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Orders orders = new Orders();
        orders.setId(ordersDB.getId());
        // 更新订单状态,状态转为完成
        orders.setStatus(Orders.COMPLETED);
        orders.setDeliveryTime(LocalDateTime.now());

        orderMapper.update(orders);
    }

    @Override
    public void reminder(Long id) {
        // 根据id查询订单
        Orders ordersDB = orderMapper.getById(id);

        // 校验订单是否存在
        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        Map map = new HashMap();
        map.put("type", 2);
        map.put("orderId", id);
        map.put("content", "订单号：" + ordersDB.getNumber());

        webSocketServer.sendToAllClient(JSON.toJSONString(map));

    }

    private List<OrderVO> getOrderVOList(Page<Orders> page) {
        // 需要返回订单菜品信息，自定义OrderVO响应结果
        List<OrderVO> orderVOList = new ArrayList<>();

        List<Orders> ordersList = page.getResult();
        if (!CollectionUtils.isEmpty(ordersList)) {
            for (Orders orders : ordersList) {
                // 将共同字段复制到OrderVO
                OrderVO orderVO = new OrderVO();
                BeanUtils.copyProperties(orders, orderVO);
                String orderDishes = getOrderDishesStr(orders);

                // 将订单菜品信息封装到orderVO中，并添加到orderVOList
                orderVO.setOrderDishes(orderDishes);
                orderVOList.add(orderVO);
            }
        }
        return orderVOList;
    }

    /**
     * 根据订单id获取菜品信息字符串
     *
     * @param orders
     * @return
     */
    private String getOrderDishesStr(Orders orders) {
        // 查询订单菜品详情信息（订单中的菜品和数量）
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orders.getId());

        // 将每一条订单菜品信息拼接为字符串（格式：宫保鸡丁*3；）
        List<String> orderDishList = orderDetailList.stream().map(x -> {
            String orderDish = x.getName() + "*" + x.getNumber() + ";";
            return orderDish;
        }).collect(Collectors.toList());

        // 将该订单对应的所有菜品信息拼接在一起
        return String.join("", orderDishList);
    }


    /**
     * 根据用户地址判断用户地址是否超出配送范围
     * TODO 缺陷：同步串行调用两次第三方接口，性能差，下单接口响应慢,三次 HTTP 远程调用叠加网络延迟，高峰期下单接口超时风险极高。
     * TODO 缺陷(已经解决,见checkOutOfRange1)：同一店铺地址、同一用户地址每次下单都重复调用地理编码接口，百度开放平台有调用次数限额，高频下单会超限报错，且重复网络 IO 损耗性能。----redis缓存地址坐标。但是需要抽取方法（根据地址调用接口获取坐标）
     * @param userAddress
     */
    public void checkOutOfRange(String userAddress){
        //使用LinkedHashMap在哈希表基础上额外维护一条**双向链表**，保证**插入顺序有序**：可以按添加的顺序拼接 URL 参数，不会乱序。
        Map<String,String> params = new LinkedHashMap<>();
        params.put("address", shopAddress);
        params.put("output", "json");
        params.put("ak", ak);

        //1.获取店铺地址的经纬度
        //请求百度地理编码接口，请求路径后要附带查询参数，查询参数包括地址、ak值、返回数据格式和callback。callback：将json格式的返回值通过callback函数返回以实现jsonp功能
        //获得店铺地址的地理编码返回结果,返回结果是json格式的，其中包含了api请求状态和返回的经纬度坐标
        String shopResult = HttpClientUtil.doGet("https://api.map.baidu.com/geocoding/v3/", params);
        //把json字符串转换为jsonobject对象，通过jsonobject对象读取json里的字段
        JSONObject shopJsonObject = JSON.parseObject(shopResult);
        //访问接口状态校验：返回的状态是0才表示访问成功
        if (!shopJsonObject.getString("status").equals("0")){
            throw new OrderBusinessException("地理编码接口访问失败（店铺地址）");
        }

        //json格式数据解析
        JSONObject shopResultJSONObject = shopJsonObject.getJSONObject("result");
        JSONObject shopLocationJSONObject = shopResultJSONObject.getJSONObject("location");
        //获取店铺经纬度---格式为 纬度,经度。后续要调用路径规划api，参数格式要求为这样。
        String lat = shopLocationJSONObject.getString("lat");
        String lng = shopLocationJSONObject.getString("lng");
        if (lat == null || lng == null || lat.trim().isEmpty() || lng.trim().isEmpty()) {
            throw new OrderBusinessException("店铺经纬度获取失败");
        }
        String shopCoordinate = lat.trim() + "," + lng.trim();
        //String shopCoordinate = shopLocationJSONObject.getString("lat") + "," + shopLocationJSONObject.getString("lng");


        //2.同理获取用户地址的经纬度
        params.put("address",userAddress);
        String userResult = HttpClientUtil.doGet("https://api.map.baidu.com/geocoding/v3/", params);
        JSONObject userJsonObject = JSON.parseObject(userResult);
        if (!userJsonObject.getString("status").equals("0")){
            log.info("地理编码接口访问状态:{}",userJsonObject.getString("status"));
            throw new OrderBusinessException("地理编码接口访问失败（用户地址）");
        }
        JSONObject userResultJsonObject = userJsonObject.getJSONObject("result");
        JSONObject userLocationJsonObject = userResultJsonObject.getJSONObject("location");
        String userLat = userLocationJsonObject.getString("lat");
        String userLng = userLocationJsonObject.getString("lng");
        if (userLat == null || userLng == null || userLat.trim().isEmpty() || userLng.trim().isEmpty()) {
            throw new OrderBusinessException("用户地址经纬度获取失败");
        }
        String userCoordinate = userLat.trim() + "," + userLng.trim();
        //String userCoordinate = userLocationJsonObject.getString("lat") + "," + userLocationJsonObject.getString("lng");

        //3.调用路径规划接口，传递起点和终点经纬度坐标，返回路径规划结果，其中有路径距离参数
        Map<String, String> params2 = new LinkedHashMap<>();
        params2.put("origin", shopCoordinate);
        params2.put("destination", userCoordinate);
        params2.put("ak",ak);
        log.info("路径规划请求参数：origin={}, destination={}", shopCoordinate, userCoordinate);
        String routeResult = HttpClientUtil.doGet("https://api.map.baidu.com/directionlite/v1/riding", params2);
        //数据解析，json字符串转为jsonobject对象，使用jsonobject对象读取json字段
        JSONObject routeResultJsonObject = JSON.parseObject(routeResult);
        String status = routeResultJsonObject.getString("status");
        if (!status.equals("0")){
            String msg = routeResultJsonObject.getString("msg");
            log.info("路径规划接口访问状态:{}",status);
            throw new OrderBusinessException("路线规划失败：" + (msg != null ? msg : "未知错误"));
        }
        JSONObject resultJSONObject = routeResultJsonObject.getJSONObject("result");
        JSONArray jsonArray = (JSONArray) resultJSONObject.get("routes");
        //0号是一个json格式的数据，为了便于读取，转换成jsonobject对象
        JSONObject array0JsonObject = (JSONObject) jsonArray.get(0);
        Integer distance = (Integer) array0JsonObject.get("distance");

        //4.判断距离是否超过5km
        if (distance > 5000){
            throw new OrderBusinessException(MessageConstant.DISTANT_OUT_OF_RANGE);
        }
    }

    /**
     * 使用了缓存，通过注入工具类调用带有cacheable注解的方法
     *
     * @param userAddress
     */
    public void checkOutOfRange1(String userAddress){
        //1.获取店铺地址的经纬度
        String shopCoordinate = baiDuMapUtil.getCoordinateByAddress(shopAddress);

        //2.同理获取用户地址的经纬度
        String userCoordinate = baiDuMapUtil.getCoordinateByAddress(userAddress);

        //3.调用路径规划接口，传递起点和终点经纬度坐标，返回路径规划结果，其中有路径距离参数
        Map<String, String> params2 = new LinkedHashMap<>();
        params2.put("origin", shopCoordinate);
        params2.put("destination", userCoordinate);
        params2.put("ak",ak);
        String routeResult = HttpClientUtil.doGet("https://api.map.baidu.com/directionlite/v1/riding", params2);
        //数据解析，json字符串转为jsonobject对象，使用jsonobject对象读取json字段
        JSONObject routeResultJsonObject = JSON.parseObject(routeResult);
        if (!routeResultJsonObject.getString("status").equals("0")){
            throw new OrderBusinessException("路线规划接口访问失败");
        }
        JSONObject resultJSONObject = routeResultJsonObject.getJSONObject("result");
        JSONArray jsonArray = (JSONArray) resultJSONObject.get("routes");
        //0号是一个json格式的数据，为了便于读取，转换成jsonobject对象
        JSONObject array0JsonObject = (JSONObject) jsonArray.get(0);
        Integer distance = (Integer) array0JsonObject.get("distance");

        //4.判断距离是否超过5km
        if (distance > 5000){
            throw new OrderBusinessException(MessageConstant.DISTANT_OUT_OF_RANGE);
        }
    }
}
