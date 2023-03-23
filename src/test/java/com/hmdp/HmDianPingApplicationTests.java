package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_HOT_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    void testIdWork() throws InterruptedException {
        // CountDownLatch允许一个或者多个线程去等待其他线程完成操作。 300为线程数
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown(); //执行完一个线程就+1
        };

        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await(); // 等待300个线程都结束，即countDown计数到300才继续执行
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));
    }

    @Test
    void testSaveShop() throws InterruptedException {
        // 模拟后台进行缓存预热
        //shopService.saveShop2Redis(1L, 10L);

        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(CACHE_HOT_KEY + 1L, shop, 10L, TimeUnit.SECONDS);
    }

    @Test
    void loadShopData(){
        // 1.查询店铺信息 数据量大时可以分批查
        List<Shop> list = shopService.list();
        // 2.把店铺分组，按照typeId分组，typeId一致的放到一个集合
        // 之前用的多的是 collect收集是 Collectors.toList()，这里用groupingBy()，可按传入分组方法进行分组，形成一个map集合
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));

        String prefix = "shop:geo:";

        // 3.分批完成写入Redis 两个foreach嵌套写法
        map.forEach((typeId, shopList) -> {
            // GeoLocation就是一个 number(商品的id) + 一个点
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>();
            shopList.forEach(shop -> {
                // 一个坐标一个坐标的往Redis中加效率低
                //stringRedisTemplate.opsForGeo().add(prefix + typeId, new Point(shop.getX(), shop.getY()), shop.getId().toString())
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())
                ));

            });
            // 一次往Redis中加一类的店铺，这样效率就高多了
            stringRedisTemplate.opsForGeo().add(prefix + typeId, locations);
        });
    }

    /**
     * HLL做UV统计，无论数据量有多大，内存大小永远不超过16KB
     */
    @Test
    void testHyperLogLog(){
        String[] values = new String[1000];
        int j = 0;
        // 分批存入redis 一批1000个
        for (int i = 0; i < 1000000; i++) {
            j = i % 1000;
            values[j] = "user_" + i;
            if (j == 999) {
                // 发送到Redis
                stringRedisTemplate.opsForHyperLogLog().add("hl2", values);
            }
        }
        // 统计数量
        Long count = stringRedisTemplate.opsForHyperLogLog().size("hl2");
        System.out.println("count = " + count);

    }

}
