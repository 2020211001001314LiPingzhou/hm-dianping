package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Resource
    private IFollowService followService;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();

        records.forEach(blog -> {
            // 查询blog发布人基本信息
            this.queryBlogUser(blog);
            // 是否已点过赞
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 点赞/取消点赞
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.判断当前用户是否已经点赞 SISMEMBER key number
        String key = BLOG_LIKED_KEY + id;
        //Boolean member = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        // 使用score()，如果sortedSet中存在该userId值，那么返回分数，如果不存在返回null
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //boolean isMember = Boolean.TRUE.equals(member);
        Blog blog = new Blog();

        if (score == null){
            // 3.如果未点赞，可以点赞
            blog.setIsLike(true);
            // 3.1.数据库点赞数+1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            // 3.2.保存用户到Redis集合
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 4.如果已点赞，取消点赞
            blog.setIsLike(false);
            // 4.1.数据库点赞数-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            // 4.2.把用户从Redis中移除
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }

        //blog.setLiked(((Blog) queryBlogById(id).getData()).getLiked());
        blog.setLiked(stringRedisTemplate.opsForZSet().size(key).intValue());
        return Result.ok(blog);
    }

    /**
     * 查询top5点赞用户
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        // 1.查询top5的点赞用户 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4); //TODO 这里有个bug：如果redis记录中没有人点赞，返回空的set，后面的idstr就为""，在后面查询时 id IN ()就会报错
        // 解决bug：集合为空时直接返回
        if (top5 == null || top5.size() == 0){
            return Result.ok();
        }
        // 2.解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        // 3.根据用户id查询用户
        // 存在 select ... in () 排序问题
        /*List<UserDTO> userDTOS = userService.listByIds(ids).stream().map(user ->
                BeanUtil.copyProperties(user, UserDTO.class)
        ).collect(Collectors.toList());*/
        String idStr = StrUtil.join(",", ids);
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        // 4.返回
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2.保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("新增笔记失败");
        }
        // 3.查询笔记作者的所有粉丝 select * from tb_follow where follow_user_id = ?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        // 4.推送笔记id给所有粉丝
        for (Follow follow : follows) {
            // 4.1.获取粉丝id
            Long fansId = follow.getUserId();
            // 4.2.推送
            // 每个用户在Redis中都有一个sortSet作为收件箱，可按时间戳进行排序，可用score作为角标排序，
            // list也能满足以上两点，但是list的下标在分页查询时可能会出现问题：用rightPop，查询第二页前，收到新的推送，在查询第二页时会重复查询到第一页的部分推送
            String key = FEED_KEY + fansId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }

        // 5.返回id
        return Result.ok(blog.getId());
    }

    /**
     * 前端有时滚动会不刷新，怀疑是图片大小的问题（前端问题）
     * @param max
     * @param offset
     * @return
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();

        // 2.查询收件箱 ZREVRANGEBYSCORE key Max Min LIMIT offset count
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset.longValue(), 2);
        // 3.非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        // 4.解析数据：blogId、minTime（时间戳）、offset
        List<Long> ids = new ArrayList<>(typedTuples.size()); //不需要那么大的空间，且如果需要大空间一开始指定大小也就省的自动扩容
        long minTime = 0L;
        int os = 1;
        StringBuilder ids_Str = new StringBuilder();
        /*
          这里foreach遍历的Set最后一个元素就一定是最小的，违反了我们学过的Set无序不可重复的认识，
          这是因为这里Set的实现类是LinkHashSet，这类集合有序无重复
         */
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            // 4.1.获取id
            String idStr = tuple.getValue();
            ids_Str.append(idStr).append(",");
            ids.add(Long.valueOf(idStr)); // 这里用Long.valueOf()比用Long.parseLong()更好，后者返回的是long，需要自动装箱，前者返回的是Long，且做了判断，在-128~127范围是从LongCache缓存池中获取
            // 4.2.获取分数（时间戳）
            long time = tuple.getScore().longValue();
            if (minTime != time) {
                minTime = time;
                //System.out.println(minTime);
                os = 1;
            }else {
                os++;
            }
        }
        ids_Str.deleteCharAt(ids_Str.lastIndexOf(",")); //删去多余的逗号
        os = minTime == max ? offset + os : os;
        // 5.根据id查询blog
        // 不能直接listByIds(ids)，之前就说过，底层是SQL是in(id1, id2,...)，这样查出来的结果顺序并不是按ids的顺序进行排序的
        // List<Blog> blogs = listByIds(ids);
        System.out.println(ids_Str);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + ids_Str + ")").list();

        for (Blog blog : blogs) {
            // 查询blog发布人基本信息
            queryBlogUser(blog);
            // 查询blog是否被点赞
            isBlogLiked(blog);
        }

        // 6.封装并返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);

        return Result.ok(scrollResult);
    }

    /**
     * 根据查看博客
     * @param id
     * @return
     */
    @Override
    public Result queryBlogById(Long id) {
        // 1.查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        // 2.查询blog发布人基本信息
        queryBlogUser(blog);
        // 3.查询blog是否被点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    /**
     * 判断用户是否已经点赞
     * 设置 isLike 属性，true为已点，false为未点
     * @param blog
     */
    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return;
        }

        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.判断当前用户是否已经点赞 SISMEMBER key number
        String key = "blog:liked:" + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    /**
     * 查询博客的发布人信息
     * @param blog
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        // 根据BlogId查询用户信息（基本信息）
        User user = userService.getById(userId);
        // 存入Blog
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }


}
