package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;

import java.io.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;

@SpringBootTest
public class userLoginLoad extends ServiceImpl<UserMapper, User> {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    public void userLoad() throws IOException {
        BaseMapper<User> baseMapper = query().getBaseMapper();
        List<User> users = baseMapper.selectList(null);

        File file = new File("D:\\javaweb\\redis\\code\\hm-dianping\\src\\token.txt");
        OutputStream output = new FileOutputStream(file);

        for (User user: users) {
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
            Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                    CopyOptions.create()
                            .setIgnoreNullValue(true)
                            .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

            String token = UUID.randomUUID().toString(true);
            getToken2File(file, output, token+'\n');
            String tokenKey = LOGIN_USER_KEY + token;
            stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        }

        output.close();
    }


    public void getToken2File(File file, OutputStream output, String token) throws IOException {
        //第一步：定义文件路径
        //File file = new File("D:"+ File.separator + "demo"+ File.separator + "test.txt");
        file = new File("D:\\javaweb\\redis\\code\\hm-dianping\\src\\token.txt");

        if(!file.getParentFile().exists()){
            file.getParentFile().mkdirs();
        }

        //第二步：实例化输出流
        //OutputStream output = new FileOutputStream(file);

        // 第三步：输出数据，要将数据变为字节数组输出
        output.write(token.getBytes());

        //第四步：关闭资源
        //output.close();
    }

}
