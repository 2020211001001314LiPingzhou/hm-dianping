package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    // 静态代码块在类加载时就初始化DefaultRedisScript，避免每次newSimpleRedisLock对象都要新new一个
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    // 构造方法后执行，即初始化对象后执行
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    // 线程池，因为异步后处理订单的速度不用特别快（减小数据库压力），所以尝试使用单线程线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    // 实现Runnable的内部类
    private class VoucherOrderHandler implements Runnable{

        String queueName = "stream.orders";

        @Override
        // 执行业务逻辑
        public void run() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 2.判断消息是否获取成功
                    // 2.1.如果获取失败，说明没有消息，继续下一次循环
                    if (list == null || list.isEmpty()) {
                        // 如果获取失败，说明没有消息，继续下一次循序
                        continue;
                    }
                    // 3.解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    // 4.如果获取成功，可以下单
                    handleVoucherOrder(BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true));
                    // 5.ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    // 出异常后尝试重新从PendingList中获取消息
                    handlePendingList();
                }
            }
        }

        /**
         * pendingList被对应组读取后才会被添加，使用ACK确认后才移出
         */
        private void handlePendingList() {
            while (true) {
                try {
                    // 1.获取pendingList中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 2.判断消息是否获取成功
                    // 2.1.如果获取失败，说明没有消息，继续下一次循环
                    if (list == null || list.isEmpty()) {
                        // 如果获取失败，说明pendingList没有消息，结束循序
                        break;
                    }
                    // 3.解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    // 4.如果获取成功，可以下单
                    handleVoucherOrder(BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true));
                    // 5.ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pending-list订单异常", e);
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }

    }

    /*
    // 阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    // 线程池，因为异步后处理订单的速度不用特别快（减小数据库压力），所以尝试使用单线程线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    // 实现Runnable的内部类
    private class VoucherOrderHandler implements Runnable{

        @Override
        // 执行业务逻辑
        public void run() {
            while (true) {
                try {
                    // 1.获取队列中的订单信息
                    //获取和删除该队列的头部元素，如果队列中无元素则等待，直到有元素为止。
                    VoucherOrder voucherOrder = orderTasks.take();//没元素可获取会卡在这，所以不用担心while循环空转占cpu问题。
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }*/

    /**
     * 创建订单
     * @param voucherOrder
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 1.获取用户
        // 这里用不了threadLocal获取userId了，因为这是从多线程里获取的新线程，不是创建ThreadLocal并存入用户数据的主线程
        // 只能从voucherOrder中获取userId
        Long userId = voucherOrder.getUserId();
        // 2.创建锁对象
        // 理论上其实不加锁也行，因为在前面lua脚本中做了并发判断，这里还加锁是兜底，以防万一
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 3.获取锁
        boolean isLock = lock.tryLock();
        // 4.判断是否获取锁成功
        if (!isLock) {
            // 获取锁失败，因为是异步，也不用返回什么给前端，用日志记录即可
            log.error("不允许重复下单");
            return;
        }

        try {
            // 5.获取代理对象
            // 获取不到代理对象，因为 AopContext中用于获取代理类的currentProxy也是从threadLocal中获取到的
            // 所以这个代理类要在主线程中获取
            // IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    private IVoucherOrderService proxy;

    /**
     * 使用基于Stream消息队列实现异步秒杀 取代 之前的阻塞队列实现
     * @param voucherId
     * @return
     */
    public Result seckillVoucher(Long voucherId) {
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        // 获取订单Id
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId));
        // 2.判断结果是否为0
        int r = result.intValue();
        if (r != 0){
            // 2.1.不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        // 3.获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 4.返回订单id
        return Result.ok(orderId);
    }

//    /**
//     * 异步秒杀优化：
//     * 主线程判断用户有没有下单资格，有就返回订单编号。
//     * 而耗时较长的数据库创建订单，修改库存等操作交给异步线程执行。
//     * 这样在高并发下对用户而言，能提高获取到数据速度；对数据库而言，异步操作时对速度要求不高，可以慢慢操作，降低了数据库压力
//     * @param voucherId
//     * @return
//     */
/*    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString());
        // 2.判断结果是否为0
        int r = result.intValue();
        if (r != 0){
            // 2.1.不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 2.2.为0，有购买资格，把下单信息保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        // 2.3.订单Id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 2.4.用户Id
        voucherOrder.setUserId(userId);
        // 2.5.代金券Id
        voucherOrder.setVoucherId(voucherId);
        // 2.6.订单信息放入阻塞队列
        orderTasks.add(voucherOrder);

        // 3.获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 4.返回订单id
        return Result.ok(orderId);
    }*/

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 5.一人一单
        // 异步线程不能从threadLocal中获取到userId
        //Long userId = UserHolder.getUser().getId();
        Long userId = voucherOrder.getUserId();

        // 5.1.查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户已经购买过
            log.error("用户已经购买过一次！");
            return ;
        }

        // 6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0) // where id = ? and stock = ? 乐观锁可以解决超卖问题，但是失败率高，会出现有票卖不出的情况，这里用stock>0也行 gt即大于
                .update();
        if (!success) {
            // 扣减失败
            log.error("库存不足！");
            return;
        }
        // 订单存入数据库
        save(voucherOrder);

    }


    /*@Override
    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠券信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀尚未开始！");
        }
        // 3.判断库存是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 已经结束
            return Result.fail("秒杀已经结束！");
        }
        // 4.判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足！");
        }

        Long userId = UserHolder.getUser().getId();
        *//**
         * 4.分布式并发情况每个服务器的JVM都是独立的，进而他们的synchronized锁都是锁自己知道的进程，如果一个用户的多个请求分别向不同服务器发送请求，
         *  那么还是会出现一人抢多单情况。对于分布式系统来说，用synchronized显然是不合适了，需要让所有服务器都共享同一个锁才行，所以要使用分布式锁，
         *  其中用redis的set lock thread1 nx ex 10就是一种实现方式。
         *//*
        // 创建锁对象 (用Redisson替换掉我们自己写锁)
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 获取锁（无参采用默认值：waitTime:-1 不阻塞等待、leaseTime:30 超时时间30秒、 TimeUtil.SECOND）
        boolean isLock = lock.tryLock();
        // 判断是否获取锁成功
        if (!isLock) {
            // 获取锁失败，返回错误或重试
            return Result.fail("请求操作过于频繁");
        }

        try {
            //synchronized (userId.toString().intern()) {
            *//**
             * 3.（坑点3，没想到还有问题）直接的this.createVoucherOrder()是使用当前对象来调用方法，而不是使用VoucherOrderServiceImpl的
             *  代理对象来调用，事务要想生效是spring对当前类做了动态代理，拿到了代理对象，用它来进行事务处理。现在直接thisthis.createVoucherOrder()
             *  调用的是非代理对象，也就是是原始目标对象，它是没有事务功能的。
             *  这就是spring事务失效的几种可能情况之一
             *  解决方法是用AopContext.currentProxy()方法获取到当前类的代理对象，用代理对象来调用方法即可实现事务功能
             *  （要先添加aspectjweaver依赖，并在项目启动类上加上@EnableAspectJAutoProxy(exposeProxy = true)注解，暴露代理对象）
             *//*
            //return this.createVoucherOrder(voucherId);
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
            //}
        // 只try不用抛异常，写这个主要目的是为了finally能释放锁
        } finally {
            // 释放锁。刚开始犹豫了下释放了后面此用户又请求不是又能获取到锁了吗？这样不会造成一人多单吗？
            // 想想后发现释放是正确的，后面就算有新线程获取到锁了也没关系，在第一个进程获取到锁时若不出错，
            // 则数据库中就会已存在一条订单，后面进程即使获取到锁了也过不了“查询订单已存在”这关。相反如果这里
            // 不释放锁，或者说等锁自己过期，会影响第一次抢票线程获取到锁但后面因发送异常而没真正抢到票的用户的体验。
            lock.unlock();
        }
    }*/

    /**
     * 1.我们要实现的是一人一单，限制的是同一个用户，如果把synchronized加到方法上，锁的作用范围就变成了整个方法，
     *  锁的对象是this，意味着不管是任意一个用户来了都要加锁，大家都用一把锁，整个方法就变成串行执行了，性能就变差了
     *  我们应该用用户的id来锁，这样只会锁住一个用户，避免其多个请求串行执行，且不影响其他用户
     * 2.synchronized加在方法体里面也不是完美的，还有事务提交延时带来的并发问题：当一个进程查询订单，减库存，提交订单后释放了
     *  锁，这时事务还没有提交，数据库还没有更新，此时如果有同用户另一个进程获取到了锁，查询数据库还是没订单，就又出现了并发问题。
     *  所以我们应该先获取锁，再进行事务，执行完后提交事务，最后再来释放锁。要实现这样就需要在调用方法时使用synchronized
     */
    /*@Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 5.一人一单
        Long userId = UserHolder.getUser().getId();

        *//**
         * 每次获取到的userId是局部变量，地址是不同的，使用我们需要比较的是内容，按照这个要求先toString变为字符串，
         * 但这还没有结束，这里直接userId.toString()，底层是在堆区new了一个新的字符串对象，
         * 虽然指向到方法区的内容相同，但是返回的堆区对象地址是不同的，所以直接这样也是锁不住的。
         * intern()作用是去方法区的字符串常量池里寻找一样的字符串，并返回其在方法区的地址
         * 所以userId.toString().intern()实现一样的用户id使用一把锁
         *//*
        //synchronized (userId.toString().intern()){
            // 5.1.查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            // 5.2.判断是否存在
            if (count > 0) {
                // 用户已经购买过
                return Result.fail("用户已经购买过一次！");
            }

            // 6.扣减库存
            boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock = ? 乐观锁可以解决超卖问题，但是失败率高，会出现有票卖不出的情况，这里用stock>0也行 gt即大于
                .update();
            if (!success) {
                // 扣减失败
                return Result.fail("库存不足！");
            }
            // 7.创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            // 7.1.订单id 用我们写的全局唯一id生成器生成
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            // 7.2.用户id 从在登录时拦截器中存在ThreadLocal的用户信息中获取
            voucherOrder.setUserId(userId);
            // 7.3.代金券id
            voucherOrder.setVoucherId(voucherId);
            // 订单存入数据库
            save(voucherOrder);
            // 8.返回订单id
            return Result.ok(orderId);
        //}
    }*/
}
