package com.sky.utils;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Slf4j
public class BaiDuMapUtil {

    @Value("${sky.shop.address}")
    private String shopAddress;
    @Value("${sky.baidu.ak}")
    private String ak;

    /**
     * 抽离出来的调用百度地图接口，根据地址字符串返回地址对应的经纬度坐标字符串。并且会使用cacheable注解。
     *
     * @param address
     * @return
     */
    // TODO 接口失败直接抛异常（已解决），引发缓存穿透。解决方法：捕获异常返回null，同时设置当返回值为null的时候不存入redis：`unless="#result == null"`
    @Cacheable(cacheNames = "coordinateCache",key = "#address",unless = "#result == null",sync = true)
    public String getCoordinateByAddress(String address) {
        //请求百度地理编码接口，请求路径后要附带查询参数，查询参数包括地址、ak值、返回数据格式和callback。callback：将json格式的返回值通过callback函数返回以实现jsonp功能

        //使用LinkedHashMap在哈希表基础上额外维护一条**双向链表**，保证**插入顺序有序**：可以按添加的顺序拼接 URL 参数，不会乱序。
        Map<String,String> params = new LinkedHashMap<>();
        params.put("address", address);
        params.put("output", "json");
        params.put("ak", ak);

        //获得地址的地理编码返回结果,返回结果是json格式的，其中包含了api请求状态和返回的经纬度坐标
        String result = null;
        try {
            result = HttpClientUtil.doGet("https://api.map.baidu.com/geocoding/v3/", params);
        } catch (Exception e) {
            log.error("调用百度地理编码网络异常，地址：{}", address, e);
            return null;
        }
        //把json字符串转换为jsonobject对象，通过jsonobject对象读取json里的字段
        JSONObject jsonObject = JSON.parseObject(result);
        //访问接口状态校验：返回的状态是0才表示访问成功
        if (!jsonObject.getString("status").equals("0")){
            log.error("地址{}解析失败，百度返回状态：{}", address, jsonObject.getString("status"));
            return null;
        }

        //json格式数据解析
        JSONObject resultJSONObject = jsonObject.getJSONObject("result");
        JSONObject locationJSONObject = resultJSONObject.getJSONObject("location");
        //判空防止NPE（NullPointerException 空指针异常）
        if(locationJSONObject == null){
            log.error("地址{}无法解析出坐标", address);
            return null;
        }
        //获取经纬度---格式为 纬度,经度。后续要调用路径规划api，参数格式要求为这样。
        String lat = locationJSONObject.getString("lat");
        String lng = locationJSONObject.getString("lng");
        if(lat == null || lng == null){
            return null;
        }
        return lat + "," + lng;
    }

}
