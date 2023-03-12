package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    /**
     * 拦截器没有添加到spring容器中，不是自动创建的，是我们在配置文件中声明的，所以不使用注解
     * 我们在配置文件类MvcConfig中用@Resource中自动注入，再通过构造方法注入创建拦截器对象
     * 解释：拦截器Bean初始化之前它就执行了，所以它肯定是无法获取SpringIOC容器中的内容的。那么我们就让拦截器执行的时候实例化拦截器Bean，在拦截器配置类里面先实例化拦截器，然后再获取
     * 解释2：看配置文件中，拦截器是自己new出来的，他不是受springIoc容器管理的，这一点上就已经不能用到依赖注入了
     *        其实可以让容器去创建拦截器对象，在这里写@component注解不好，因为那样在配置类中就还有@Autowired到属性再注册到拦截器队列中，可以直接在配置类中创建。
     *        老师使用的方式是在配置类中@Autowired StringRedisTemplate，注册拦截器时还是自己new拦截器对象，不过通过有参构造，将stringRedisTemplate传给了拦截器对象，这样确实也行。
     *
     */

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        // 1.获取请求头中的token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            return true; //为空就直接跳到下一个拦截器
        }

        // 2.基于token获取redis中的用户
        String key = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);

        // 3.判断用户是否存在
        if (userMap.isEmpty()) {
            return true;
        }
        // 5.将查询到的Hash数据转为UserDTO对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

        // 6.存在，保存用户信息到ThreadLocal
        UserHolder.saveUser(userDTO);

        // 7.刷新token有效期
        stringRedisTemplate.expire(key, 30, TimeUnit.MINUTES);
        // 8.放行
        return true;

    }

    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户
        UserHolder.removeUser();
    }
}
