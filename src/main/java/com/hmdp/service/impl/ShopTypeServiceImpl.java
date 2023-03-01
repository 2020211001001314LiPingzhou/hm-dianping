package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
/*ServiceImpl<ShopTypeMapper, ShopType>是MybatisPlus的单表操作要求，ShopType类里面有指向对应单表的注解*/
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        //使用list取 【0 -1】 代表全部
        List<String> shopTypeList = stringRedisTemplate.opsForList().range("shop:list", 0, -1);
        System.out.println("shopTypeList = " + shopTypeList);
        if (CollectionUtil.isNotEmpty(shopTypeList)) {
            //shopTypeList.get(0) 其实是获取了整个List集合里的元素（因为里面多套了一个[]）
            List<ShopType> types = JSONUtil.toList(shopTypeList.get(0), ShopType.class);
            return Result.ok(types);
        }

        List<ShopType> typeList = query().orderByAsc("sort").list();

        if (CollectionUtil.isEmpty(typeList)) {
            return Result.fail("列表信息不存在");
        }

        //list 存
        String jsonStr = JSONUtil.toJsonStr(typeList);
        stringRedisTemplate.opsForList().leftPushAll("shop:list", jsonStr);
        stringRedisTemplate.expire("shop:list", 30L, TimeUnit.MINUTES);

        return Result.ok(typeList);

        /*// 使用String类型存储到Redis
        // 1.尝试从redis中获取数据
        String typeJson = stringRedisTemplate.opsForValue().get("shop:type");

        // 2.判断是否存在
        if (StrUtil.isNotBlank(typeJson)) {
            // 3.存在，返回
            List<ShopType> shopTypeList = JSONUtil.toList(typeJson, ShopType.class);
            return Result.ok(shopTypeList);
        }

        // 4.不存在，从数据库中获取
        List<ShopType> typeList = query().orderByAsc("sort").list();

        // 5.存入redis
        stringRedisTemplate.opsForValue().set("shop:type", JSONUtil.toJsonStr(typeList), 30L, TimeUnit.MINUTES);

        // 6.返回
        return Result.ok(typeList);*/
    }
}
