<font size=4.5>

**分布式锁-基于Redis实现**

---

- **文章目录**

* [1\. 高可用分布式锁特性](#1-%E9%AB%98%E5%8F%AF%E7%94%A8%E5%88%86%E5%B8%83%E5%BC%8F%E9%94%81%E7%89%B9%E6%80%A7)
* [2\. 实现原理](#2-%E5%AE%9E%E7%8E%B0%E5%8E%9F%E7%90%86)
  * [2\.1 常用命令解析](#21-%E5%B8%B8%E7%94%A8%E5%91%BD%E4%BB%A4%E8%A7%A3%E6%9E%90)
  * [2\.2 原理解析](#22-%E5%8E%9F%E7%90%86%E8%A7%A3%E6%9E%90)
  * [2\.3 问题总结](#23-%E9%97%AE%E9%A2%98%E6%80%BB%E7%BB%93)
* [3\. 具体实现](#3-%E5%85%B7%E4%BD%93%E5%AE%9E%E7%8E%B0)
  * [3\.1 引入依赖](#31-%E5%BC%95%E5%85%A5%E4%BE%9D%E8%B5%96)
  * [3\.2 编辑配置文件](#32-%E7%BC%96%E8%BE%91%E9%85%8D%E7%BD%AE%E6%96%87%E4%BB%B6)
  * [3\.3 初始化lua脚本](#33-%E5%88%9D%E5%A7%8B%E5%8C%96lua%E8%84%9A%E6%9C%AC)
  * [3\.4 定义分布式锁接口](#34-%E5%AE%9A%E4%B9%89%E5%88%86%E5%B8%83%E5%BC%8F%E9%94%81%E6%8E%A5%E5%8F%A3)
  * [3\.5 redisclient工具类](#35-redisclient%E5%B7%A5%E5%85%B7%E7%B1%BB)
  * [3\.6 测试分布式锁](#36-%E6%B5%8B%E8%AF%95%E5%88%86%E5%B8%83%E5%BC%8F%E9%94%81)
  * [3\.7 基于注解切面简化实现分布式锁](#37-%E5%9F%BA%E4%BA%8E%E6%B3%A8%E8%A7%A3%E5%88%87%E9%9D%A2%E7%AE%80%E5%8C%96%E5%AE%9E%E7%8E%B0%E5%88%86%E5%B8%83%E5%BC%8F%E9%94%81)
  * [3\.8 源码参考地址](#38-%E6%BA%90%E7%A0%81%E5%8F%82%E8%80%83%E5%9C%B0%E5%9D%80)
  * [3\.9 总结](#39-%E6%80%BB%E7%BB%93)

# 1. 高可用分布式锁特性

> - 互斥性：作为锁，需要保证任何时刻只能有一个客户端(用户)持有锁
>
> - 可重入： 同一个客户端在获得锁后，可以再次进行加锁
>
> - 高可用：获取锁和释放锁的效率较高，不会出现单点故障
>
> - 自动重试机制：当客户端加锁失败时，能够提供一种机制让客户端自动重试

# 2. 实现原理

## 2.1 常用命令解析

> - setnx 是『SET if Not eXists』(如果不存在，则 SET)的简写。 命令格式：SETNX key value；使用：只在键 key 不存在的情况下，将键 key 的值设置为 value 。若键 key 已经存在， 则 SETNX 命令不做任何动作。返回值：命令在设置成功时返回 1 ，设置失败时返回 0 。
>
> - getset 命令格式：GETSET key value，将键 key 的值设为 value ，并返回键 key 在被设置之前的旧的value。返回值：如果键 key 没有旧值， 也即是说， 键 key 在被设置之前并不存在， 那么命令返回 nil 。当键 key 存在但不是字符串类型时，命令返回一个错误。
>
> - expire 命令格式：EXPIRE key seconds，使用：为给定 key 设置生存时间，当 key 过期时(生存时间为 0 )，它会被自动删除。返回值：设置成功返回 1 。 当 key 不存在或者不能为 key 设置生存时间时(比如在低于 2.1.3 版本的 Redis 中你尝试更新 key 的生存时间)，返回 0 。
>
> - del 命令格式：DEL key [key …]，使用：删除给定的一个或多个 key ，不存在的 key 会被忽略。返回值：被删除 key 的数量。

## 2.2 原理解析

**实现原理一：**

![](https://gitee.com/FocusProgram/PicGo/raw/master/redis-lock.png)

**过程分析**：

- 1.客户端获取锁，通过setnx(lockkey,currenttime+timeout)命令，将key为lockkey的value设置为当前时间+锁超时时间
- 2.如果setnx(lockkey,currenttime+timeout)设置后返回值为1时，获取锁成功，说明redis中不存在lockkey，也不存在别的客户端拥有这个锁
- 3.获取锁后首先使用expire(lockkey)命令设置lockkey的过期时间，目的是为了防止死锁的发生，因为不设置lockKey的过期时间，lockkey就会一直存在于redis中，当别的客户端使用setnx(lockkey,currenttime+timeout)命令时返回的结果一直未0，造成死锁
- 4.执行相关业务逻辑
- 5.释放锁，执行业务逻辑完成后，使用del(lockkey)命令删除lockKey，为了别的客户端可以及时获取到锁，减少等待时间

**缺陷：**

> 如果客户端A，在获取锁以后，也就是在执行setnx(lockkey,currenttime+timeout)命令成功以后，redis宕机或者程序异常终止，未执行expire(lockkey)命令，那么锁就一直存在，别的客户端就一直获取不到锁，造成阻塞。

**解决方法：**

> 关闭Tomcat有两种方式，一种通过温柔的执行shutdown关闭，一种通过kill杀死进程关闭

```
//通过温柔的执行shutdown关闭时，以下的方法会在关闭前执行，即可以释放锁，而对于通过kill杀死进程关闭时，以下方法不会执行，即不会释放锁
//这种方式释放锁的缺点在于，如果关闭的锁过多，将造成关闭服务器耗时过长
@PreDestroy
public void delLock() {
    RedisShardedPoolUtil.del(Const.REDIS_LOCK.CLOSE_ORDER_TASK_LOCK);
}
```

> 为解决以上设计存在的弊端，优化设计，采用双重防死锁解决死锁问题

**实现原理二：**

![](https://gitee.com/FocusProgram/PicGo/raw/master/redis-lock-2.png)

**过程分析：**

- 1.当A通过setnx(lockkey,currenttime+timeout)命令能成功设置lockkey时，即返回值为1，过程与原理1一致；
- 2.当A通过setnx(lockkey,currenttime+timeout)命令不能成功设置lockkey时，这是不能直接断定获取锁失败；因为我们在设置锁时，设置了锁的超时时间timeout，当当前时间大于redis中存储键值为lockkey的value值时，可以认为上一任的拥有者对锁的使用权已经失效了，A就可以强行拥有该锁；具体判定过程如下；
- 3.A通过get(lockkey)，获取redis中的存储键值为lockkey的value值，即获取锁的相对时间lockvalueA
- 4.lockvalueA!=null && currenttime>lockvalue，A通过当前的时间与锁设置的时间做比较，如果当前时间已经大于锁设置的时间临界，即可以进一步判断是否可以获取锁，否则说明该锁还在被占用，A就还不能获取该锁，结束，获取锁失败；
- 5.步骤4返回结果为true后，通过getSet设置新的超时时间，并返回旧值lockvalueB，以作判断，因为在分布式环境，在进入这里时可能另外的进程获取到锁并对值进行了修改，只有旧值与返回的值一致才能说明中间未被其他进程获取到这个锁；
- 6.lockvalueB == null || lockvalueA==lockvalueB，判断：若果lockvalueB为null，说明该锁已经被释放了，此时该进程可以获取锁；旧值与返回的lockvalueB一致说明中间未被其他进程获取该锁，可以获取锁；否则不能获取锁，结束，获取锁失败。

**优化点：**

> 加入了超时时间判断锁是否超时了，及时A在成功设置了锁之后，服务器就立即出现宕机或是重启，也不会出现死锁问题；因为B在尝试获取锁的时候，如果不能setnx成功，会去获取redis中锁的超时时间与当前的系统时间做比较，如果当前的系统时间已经大于锁超时时间，说明A已经对锁的使用权失效，B能继续判断能否获取锁，解决了redis分布式锁的死锁问题。

## 2.3 问题总结

- **问题一：时间戳的问题**

> 我们看到lockkey的value值为时间戳，所以要在多客户端情况下，保证锁有效，一定要同步各服务器的时间，如果各服务器间，时间有差异。时间不一致的客户端，在判断锁超时，就会出现偏差，从而产生竞争条件。
锁的超时与否，严格依赖时间戳，时间戳本身也是有精度限制，假如我们的时间精度为秒，从加锁到执行操作再到解锁，一般操作肯定都能在一秒内完成。这样的话，我们上面的CASE，就很容易出现。所以，最好把时间精度提升到毫秒级。这样的话，可以保证毫秒级别的锁是安全的。
>
> 分布式锁，多客户端的时间戳不能保证严格意义的一致性，所以在某些特定因素下，有可能存在锁串的情况。要适度的机制，可以承受小概率的事件产生。

- **问题二：死锁**

> 必要的超时机制：获取锁的客户端一旦崩溃，一定要有过期机制，否则其他客户端都降无法获取锁，造成死锁问题。

- **问题三：阻塞**

> 只对关键处理节点加锁，良好的习惯是，把相关的资源准备好，比如连接数据库后，调用加锁机制获取锁，直接进行操作，然后释放，尽量减少持有锁的时间。
>
> 在持有锁期间要不要CHECK锁，如果需要严格依赖锁的状态，最好在关键步骤中做锁的CHECK检查机制，但是根据我们的测试发现，在大并发时，每一次CHECK锁操作，都要消耗掉几个毫秒，而我们的整个持锁处理逻辑才不到10毫秒，玩客没有选择做锁的检查。
>
> 为了减少对Redis的压力，获取锁尝试时，循环之间一定要做sleep操作。但是sleep时间是多少是门学问。需要根据自己的Redis的QPS，加上持锁处理时间等进行合理计算。

# 3. 具体实现

## 3.1 引入依赖

> pom.xml

```
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<dependency>
    <groupId>redis.clients</groupId>
    <artifactId>jedis</artifactId>
</dependency>
```

## 3.2 编辑配置文件

> application.yml

```
server:
  port: 8000
spring:
  redis:
    database: 5
    host: 114.55.34.44
    password: root
    port: 6379
    timeout: 3000ms
    jedis:
      pool:
        max-active: 2000
        max-idle: 500
        max-wait: 1000ms
        min-idle: 50
```

> lock.lua 获得分布式锁lua脚本

```
-- 获取参数
local requestIDKey = KEYS[1]
local currentRequestID = ARGV[1]
local expireTimeTTL = ARGV[2]

-- setnx 尝试加锁
local lockSet = redis.call('hsetnx',KEYS[1],'lockKey',currentRequestID)

if lockSet == 1
then
    -- 加锁成功 设置过期时间和重入次数=1
	redis.call('expire',KEYS[1],expireTimeTTL)
	redis.call('hset',KEYS[1],'lockCount',1)
	return 1
else
    -- 判断是否是重入加锁
	local oldRequestID = redis.call('hget',KEYS[1],'lockKey')
	if currentRequestID == oldRequestID
	then
	    -- 是重入加锁
		redis.call('hincrby',KEYS[1],'lockCount',1)
		-- 重置过期时间
		redis.call('expire',KEYS[1],expireTimeTTL)
		return 1
	else
	    -- requestID不一致，加锁失败
	    return 0
	end
end
```

> unlock.lua 释放分布式锁lua脚本

```
-- 获取参数
local requestIDKey = KEYS[1]
local currentRequestID = ARGV[1]

-- 判断requestID一致性
if redis.call('hget',KEYS[1],'lockKey') == currentRequestID
then
    -- requestID相同，重入次数自减
	local currentCount = redis.call('hincrby',KEYS[1],'lockCount',-1)
	if currentCount == 0
	then
	    -- 重入次数为0，删除锁
	    redis.call('del',KEYS[1])
	    return 1
	else
	    return 0 end
else 
	return 0 end
```

## 3.3 初始化lua脚本

> LuaScript
>
> 使用redis实现分布式锁时，加锁操作必须是原子操作，否则多客户端并发操作时会导致各种各样的问题
>
> 由于我们实现的是可重入锁，加锁过程中需要判断客户端ID的正确与否。而redis原生的简单接口没法保证一系列逻辑的原子性执行，因此采用了lua脚本来实现加锁操作。lua脚本可以让redis在执行时将一连串的操作以原子化的方式执行。

```
public class LuaScript {

    /**
     * 加锁脚本 lock.lua
     * 1. 判断key是否存在
     * 2. 如果存在，判断requestID是否相等
     * 相等，则删除掉key重新创建新的key值，重置过期时间
     * 不相等，说明已经被抢占，加锁失败，返回null
     * 3. 如果不存在，说明恰好已经过期，重新生成key
     */
    public static String LOCK_SCRIPT;

    /**
     * 解锁脚本 unlock.lua
     */
    public static String UN_LOCK_SCRIPT;

    public static void initLockScript() throws IOException {
        if (StringUtils.isEmpty(LOCK_SCRIPT)) {
            InputStream inputStream = Objects.requireNonNull(
                    LuaScript.class.getClassLoader().getResourceAsStream("lock.lua"));
            LOCK_SCRIPT = readFile(inputStream);
        }
    }

    public static void initUnLockScript() throws IOException {
        if (StringUtils.isEmpty(UN_LOCK_SCRIPT)) {
            InputStream inputStream = Objects.requireNonNull(
                    LuaScript.class.getClassLoader().getResourceAsStream("unlock.lua"));
            UN_LOCK_SCRIPT = readFile(inputStream);
        }
    }

    private static String readFile(InputStream inputStream) throws IOException {
        try (
                BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))
        ) {
            String line;
            StringBuilder stringBuilder = new StringBuilder();
            while ((line = br.readLine()) != null) {
                stringBuilder.append(line)
                        .append(System.lineSeparator());
            }

            return stringBuilder.toString();
        }
    }
}
```

## 3.4 定义分布式锁接口

> DistributeLock

```
/**
 * 分布式锁 api接口
 */
public interface DistributeLock {

    /**
     * 尝试加锁
     *
     * @param lockKey 锁的key
     * @return 加锁成功 返回uuid
     * 加锁失败 返回null
     */
    String lock(String lockKey);

    /**
     * 尝试加锁 (requestID相等 可重入)
     *
     * @param lockKey    锁的key
     * @param expireTime 过期时间 单位：秒
     * @return 加锁成功 返回uuid
     * 加锁失败 返回null
     */
    String lock(String lockKey, int expireTime);

    /**
     * 尝试加锁 (requestID相等 可重入)
     *
     * @param lockKey   锁的key
     * @param requestID 用户ID
     * @return 加锁成功 返回uuid
     * 加锁失败 返回null
     */
    String lock(String lockKey, String requestID);

    /**
     * 尝试加锁 (requestID相等 可重入)
     *
     * @param lockKey    锁的key
     * @param requestID  用户ID
     * @param expireTime 过期时间 单位：秒
     * @return 加锁成功 返回uuid
     * 加锁失败 返回null
     */
    String lock(String lockKey, String requestID, int expireTime);

    /**
     * 尝试加锁，失败自动重试 会阻塞当前线程
     *
     * @param lockKey 锁的key
     * @return 加锁成功 返回uuid
     * 加锁失败 返回null
     */
    String lockAndRetry(String lockKey);

    /**
     * 尝试加锁，失败自动重试 会阻塞当前线程 (requestID相等 可重入)
     *
     * @param lockKey   锁的key
     * @param requestID 用户ID
     * @return 加锁成功 返回uuid
     * 加锁失败 返回null
     */
    String lockAndRetry(String lockKey, String requestID);

    /**
     * 尝试加锁 (requestID相等 可重入)
     *
     * @param lockKey    锁的key
     * @param expireTime 过期时间 单位：秒
     * @return 加锁成功 返回uuid
     * 加锁失败 返回null
     */
    String lockAndRetry(String lockKey, int expireTime);

    /**
     * 尝试加锁 (requestID相等 可重入)
     *
     * @param lockKey    锁的key
     * @param expireTime 过期时间 单位：秒
     * @param retryCount 重试次数
     * @return 加锁成功 返回uuid
     * 加锁失败 返回null
     */
    String lockAndRetry(String lockKey, int expireTime, int retryCount);

    /**
     * 尝试加锁 (requestID相等 可重入)
     *
     * @param lockKey    锁的key
     * @param requestID  用户ID
     * @param expireTime 过期时间 单位：秒
     * @return 加锁成功 返回uuid
     * 加锁失败 返回null
     */
    String lockAndRetry(String lockKey, String requestID, int expireTime);

    /**
     * 尝试加锁 (requestID相等 可重入)
     *
     * @param lockKey    锁的key
     * @param expireTime 过期时间 单位：秒
     * @param requestID  用户ID
     * @param retryCount 重试次数
     * @return 加锁成功 返回uuid
     * 加锁失败 返回null
     */
    String lockAndRetry(String lockKey, String requestID, int expireTime, int retryCount);

    /**
     * 释放锁
     *
     * @param lockKey   锁的key
     * @param requestID 用户ID
     * @return true     释放自己所持有的锁 成功
     * false    释放自己所持有的锁 失败
     */
    boolean unLock(String lockKey, String requestID);
}
```

> RedisDistributeLock
>
> 调用lockAndRetry方法进行加锁时，如果加锁失败，则当前客户端线程会短暂的休眠一段时间，并进行重试。在重试了一定的次数后，会终止重试加锁操作，从而加锁失败。
>
> 需要注意的是，加锁失败之后的线程休眠时长是"固定值 + 随机值"，引入随机值的主要目的是防止高并发时大量的客户端在几乎同一时间被唤醒并进行加锁重试，给redis服务器带来周期性的、不必要的瞬时压力。

```
@Component("distributeLock")
public final class RedisDistributeLock implements DistributeLock {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisDistributeLock.class);

    /**
     * 无限重试
     */
    public static final int UN_LIMIT_RETRY_COUNT = -1;

    private RedisDistributeLock() {
        try {
            LuaScript.initLockScript();
            LuaScript.initUnLockScript();
        } catch (IOException e) {
            throw new RuntimeException("LuaScript init error!", e);
        }
    }

    /**
     * 持有锁 成功标识
     */
    private static final Long ADD_LOCK_SUCCESS = 1L;
    /**
     * 释放锁 失败标识
     */
    private static final Long RELEASE_LOCK_SUCCESS = 1L;

    /**
     * 默认过期时间 单位：秒
     */
    private static final int DEFAULT_EXPIRE_TIME_SECOND = 300;
    /**
     * 默认加锁重试时间 单位：毫秒
     */
    private static final int DEFAULT_RETRY_FIXED_TIME = 100;
    /**
     * 默认的加锁浮动时间区间 单位：毫秒
     */
    private static final int DEFAULT_RETRY_TIME_RANGE = 10;
    /**
     * 默认的加锁重试次数
     */
    private static final int DEFAULT_RETRY_COUNT = 30;

    @Resource
    private RedisClient redisClient;

    //===========================================api=======================================

    @Override
    public String lock(String lockKey) {
        String uuid = UUID.randomUUID().toString();

        return lock(lockKey, uuid);
    }

    @Override
    public String lock(String lockKey, int expireTime) {
        String uuid = UUID.randomUUID().toString();

        return lock(lockKey, uuid, expireTime);
    }

    @Override
    public String lock(String lockKey, String requestID) {
        return lock(lockKey, requestID, DEFAULT_EXPIRE_TIME_SECOND);
    }

    @Override
    public String lock(String lockKey, String requestID, int expireTime) {
        List<String> keyList = Collections.singletonList(lockKey);

        List<String> argsList = Arrays.asList(
                requestID,
                expireTime + ""
        );
        Long result = (Long) redisClient.eval(LuaScript.LOCK_SCRIPT, keyList, argsList);

        if (result.equals(ADD_LOCK_SUCCESS)) {
            return requestID;
        } else {
            return null;
        }
    }

    @Override
    public String lockAndRetry(String lockKey) {
        String uuid = UUID.randomUUID().toString();

        return lockAndRetry(lockKey, uuid);
    }

    @Override
    public String lockAndRetry(String lockKey, String requestID) {
        return lockAndRetry(lockKey, requestID, DEFAULT_EXPIRE_TIME_SECOND);
    }

    @Override
    public String lockAndRetry(String lockKey, int expireTime) {
        String uuid = UUID.randomUUID().toString();

        return lockAndRetry(lockKey, uuid, expireTime);
    }

    @Override
    public String lockAndRetry(String lockKey, int expireTime, int retryCount) {
        String uuid = UUID.randomUUID().toString();

        return lockAndRetry(lockKey, uuid, expireTime, retryCount);
    }

    @Override
    public String lockAndRetry(String lockKey, String requestID, int expireTime) {
        return lockAndRetry(lockKey, requestID, expireTime, DEFAULT_RETRY_COUNT);
    }

    @Override
    public String lockAndRetry(String lockKey, String requestID, int expireTime, int retryCount) {
        if (retryCount <= 0) {
            // retryCount小于等于0 无限循环，一直尝试加锁
            while (true) {
                String result = lock(lockKey, requestID, expireTime);
                if (result != null) {
                    return result;
                }

                LOGGER.info("加锁失败，稍后重试：lockKey={},requestID={}", lockKey, requestID);
                redisClient.increment("retryCount", 1);
                // 休眠一会
                sleepSomeTime();
            }
        } else {
            // retryCount大于0 尝试指定次数后，退出
            for (int i = 0; i < retryCount; i++) {
                String result = lock(lockKey, requestID, expireTime);
                if (result != null) {
                    return result;
                }
                // 休眠一会
                sleepSomeTime();
            }

            return null;
        }
    }

    @Override
    public boolean unLock(String lockKey, String requestID) {
        List<String> keyList = Collections.singletonList(lockKey);

        List<String> argsList = Collections.singletonList(requestID);

        Object result = redisClient.eval(LuaScript.UN_LOCK_SCRIPT, keyList, argsList);

        // 释放锁成功
        return RELEASE_LOCK_SUCCESS.equals(result);
    }

    //==============================================私有方法========================================

    /**
     * 获得最终的获得锁的重试时间
     */
    private int getFinallyGetLockRetryTime() {
        Random ra = new Random();

        // 最终重试时间 = 固定时间 + 浮动时间
        return DEFAULT_RETRY_FIXED_TIME + ra.nextInt(DEFAULT_RETRY_TIME_RANGE);
    }

    /**
     * 当前线程 休眠一端时间
     */
    private void sleepSomeTime() {
        // 重试时间 单位：毫秒
        int retryTime = getFinallyGetLockRetryTime();
        try {
            Thread.sleep(retryTime);
        } catch (InterruptedException e) {
            throw new RuntimeException("redis锁重试时，出现异常", e);
        }
    }
}
```

## 3.5 redisclient工具类

```
public interface RedisClient {

    /**
     * 执行脚本
     */
    Object eval(String script, List<String> keys, List<String> args);

    /**
     * get
     */
    Object get(String key);

    /**
     * set
     */
    void set(String key, Object value);

    /**
     * set
     */
    void set(String key, Object value, long expireTime, TimeUnit timeUnit);

    /**
     * setNX
     */
    Boolean setNX(String key, Object value);

    /**
     * 设置过期时间
     */
    Boolean expire(String key, long time, TimeUnit type);

    /**
     * 移除过期时间
     */
    Boolean persist(String key);

    /**
     * 增加
     */
    Long increment(String key, long number);

    /**
     * 增加
     */
    Double increment(String key, double number);

    /**
     * 删除
     */
    Boolean delete(String key);

    // ==========================hash========================

    void hset(String key, String hashKey, Object value);

    void hsetAll(String key, Map<String, Object> map);

    Boolean hsetNX(String key, String hashKey, Object value);

    Object hget(String key, String hashKey);

    Map hgetAll(String key);
}
```

```
@Component("redisClient")
public class RedisClientImpl implements RedisClient {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Override
    public Object eval(String script, List<String> keys, List<String> args) {
        DefaultRedisScript<Integer> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(script);
        redisScript.setResultType(Integer.class);

        Object result = redisTemplate.execute((RedisCallback<Object>) redisConnection -> {
            Object nativeConnection = redisConnection.getNativeConnection();
            // 集群模式和单机模式虽然执行脚本的方法一样，但是没有共同的接口，所以只能分开执行
            // 集群模式
            if (nativeConnection instanceof JedisCluster) {
                return (Long) ((JedisCluster) nativeConnection).eval(script, keys, args);
            }

            // 单机模式
            else if (nativeConnection instanceof Jedis) {
                return (Long) ((Jedis) nativeConnection).eval(script, keys, args);
            }
            return -1L;
        });
        return result;
    }

    @Override
    public Object get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    @Override
    public void set(String key, Object value) {
        redisTemplate.opsForValue().set(key, value);
    }

    @Override
    public void set(String key, Object value, long expireTime, TimeUnit timeUnit) {
        redisTemplate.opsForValue().set(key, value, expireTime, timeUnit);
    }

    @Override
    public Boolean setNX(String key, Object value) {
        return redisTemplate.opsForValue().setIfAbsent(key, value);
    }

    @Override
    public Boolean expire(String key, long time, TimeUnit timeUnit) {
        return redisTemplate.boundValueOps(key).expire(time, timeUnit);
    }

    @Override
    public Boolean persist(String key) {
        return redisTemplate.boundValueOps(key).persist();
    }

    @Override
    public Long increment(String key, long number) {
        return redisTemplate.opsForValue().increment(key, number);
    }

    @Override
    public Double increment(String key, double number) {
        return redisTemplate.opsForValue().increment(key, number);
    }

    @Override
    public Boolean delete(String key) {
        return redisTemplate.delete(key);
    }

    @Override
    public void hset(String key, String hashKey, Object value) {
        redisTemplate.opsForHash().put(key, hashKey, value);
    }

    @Override
    public void hsetAll(String key, Map<String, Object> map) {
        redisTemplate.opsForHash().putAll(key, map);
    }

    @Override
    public Boolean hsetNX(String key, String hashKey, Object value) {
        return redisTemplate.opsForHash().putIfAbsent(key, hashKey, value);
    }

    @Override
    public Object hget(String key, String hashKey) {
        return redisTemplate.opsForHash().get(key, hashKey);
    }

    @Override
    public Map hgetAll(String key) {
        return redisTemplate.opsForHash().entries(key);
    }

}
```

## 3.6 测试分布式锁

```
@RestController
@RequestMapping("distributelock")
public class DistributeLockController {

    private static final String TEST_REDIS_LOCK_KEY = "lock_key";

    private static final int EXPIRE_TIME = 100;

    @Autowired
    private RedisDistributeLock redisDistributeLock;

    @RequestMapping("/getlock")
    public String test() throws ExecutionException, InterruptedException {
        int threadNum = 100;
        ThreadFactory namedThreadFactory = new ThreadFactoryBuilder()
                .setNameFormat("demo-pool-%d").build();
        ExecutorService executorService = new ThreadPoolExecutor(1, 1,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(1024), namedThreadFactory, new ThreadPoolExecutor.AbortPolicy());
        List<Future> futureList = new ArrayList<>();
        for (int i = 0; i <= threadNum; i++) {
            int currentThreadNum = i;
            Future future = executorService.submit(() -> {
                System.out.println("线程尝试获得锁 i=" + currentThreadNum);
                String requestID = redisDistributeLock.lockAndRetry(TEST_REDIS_LOCK_KEY, EXPIRE_TIME);

                if (!StringUtils.isEmpty(requestID)) {
                    System.out.println("获得锁，开始执行任务 requestID=" + requestID + "i=" + currentThreadNum);
                }

                // 模拟 宕机事件 不释放锁
               /* if (currentThreadNum == 1) {
                    System.out.println("模拟 宕机事件 不释放锁，直接返回 currentThreadNum=" + currentThreadNum);
                    return;
                }*/

                try {
                    // 休眠完毕
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                System.out.println("任务执行完毕" + "i=" + currentThreadNum);
                redisDistributeLock.unLock(TEST_REDIS_LOCK_KEY, requestID);
                System.out.println("释放锁完毕");
                redisDistributeLock.lockAndRetry(TEST_REDIS_LOCK_KEY, requestID, EXPIRE_TIME);
                System.out.println("重入获得锁，开始执行任务 requestID=" + requestID + "i=" + currentThreadNum);
                redisDistributeLock.unLock(TEST_REDIS_LOCK_KEY, requestID);
                System.out.println("释放重入锁完毕");
            });
            futureList.add(future);
        }
        for (Future future : futureList) {
            future.get();
        }
        return "ok";
    }
}
```

## 3.7 基于注解切面简化实现分布式锁

> RedisLock

```
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RedisLock {
    /**
     * redis锁，重试次数-1代表无限重试
     */
    int unLimitRetryCount = RedisDistributeLock.UN_LIMIT_RETRY_COUNT;

    /**
     * redis锁对应的key 会拼接此参数，用于进一步区分，避免redis的key被覆盖
     */
    String lockKey() default "";

    /**
     * redis锁过期时间（单位：秒）
     */
    int expireTime() default 10;

    /**
     * redis锁，加锁失败重试次数 默认30次，大约3s
     * 超过指定次数后，抛出加锁失败异常，可以由调用方自己补偿
     *
     * @see RedisLockFailException
     */
    int retryCount() default 30;
}
```

> RedisLockAspect

```
@Component
@Aspect
public class RedisLockAspect {

    private static final Logger logger = LoggerFactory.getLogger(RedisLockAspect.class);

    private final RequestIDMap REQUEST_ID_MAP = new RequestIDMap();

    @Autowired
    private Environment environment;

    @Autowired
    private DistributeLock distributeLock;

    /**
     * 将ThreadLocal包装成一个对象方便使用
     */
    private class RequestIDMap {
        private ThreadLocal<Map<String, String>> innerThreadLocal = new ThreadLocal<>();

        private void setRequestID(String redisLockKey, String requestID) {
            Map<String, String> requestIDMap = innerThreadLocal.get();
            if (requestIDMap == null) {
                Map<String, String> newMap = new HashMap<>();
                newMap.put(redisLockKey, requestID);
                innerThreadLocal.set(newMap);
            } else {
                requestIDMap.put(redisLockKey, requestID);
            }
        }

        private String getRequestID(String redisLockKey) {
            Map<String, String> requestIDMap = innerThreadLocal.get();
            if (requestIDMap == null) {
                return null;
            } else {
                return requestIDMap.get(redisLockKey);
            }
        }

        private void removeRequestID(String redisLockKey) {
            Map<String, String> requestIDMap = innerThreadLocal.get();
            if (requestIDMap != null) {
                requestIDMap.remove(redisLockKey);
                // 如果requestIDMap为空，说明当前重入锁 最外层已经解锁
                if (requestIDMap.isEmpty()) {
                    // 清空threadLocal避免内存泄露
                    innerThreadLocal.remove();
                }
            }
        }
    }

    @Pointcut("@annotation(com.redis.annotation.RedisLock)")
    public void annotationPointcut() {
    }

    @Around("annotationPointcut()")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        Method method = methodSignature.getMethod();
        RedisLock annotation = method.getAnnotation(RedisLock.class);

        // 方法执行前，先尝试加锁
        boolean lockSuccess = lock(annotation, joinPoint);
        // 如果加锁成功
        if (lockSuccess) {
            // 执行方法
            Object result = joinPoint.proceed();
            // 方法执行后，进行解锁
            unlock(annotation, joinPoint);
            return result;
        } else {
            throw new RedisLockFailException("redis分布式锁加锁失败，method= " + method.getName());
        }
    }

    /**
     * 加锁
     */
    private boolean lock(RedisLock annotation, ProceedingJoinPoint joinPoint) {
        int retryCount = annotation.retryCount();

        // 拼接redisLock的key
        String redisLockKey = getFinallyKeyLock(annotation, joinPoint);
        String requestID = REQUEST_ID_MAP.getRequestID(redisLockKey);
        if (requestID != null) {
            // 当前线程 已经存在requestID
            distributeLock.lockAndRetry(redisLockKey, requestID, annotation.expireTime(), retryCount);
            logger.info("重入加锁成功 redisLockKey= " + redisLockKey);

            return true;
        } else {
            // 当前线程 不存在requestID
            String newRequestID = distributeLock.lockAndRetry(redisLockKey, annotation.expireTime(), retryCount);

            if (newRequestID != null) {
                // 加锁成功，设置新的requestID
                REQUEST_ID_MAP.setRequestID(redisLockKey, newRequestID);
                logger.info("加锁成功 redisLockKey= " + redisLockKey);
                return true;
            } else {
                logger.info("加锁失败，超过重试次数，直接返回 retryCount= {}", retryCount);
                return false;
            }
        }
    }

    /**
     * 解锁
     */
    private void unlock(RedisLock annotation, ProceedingJoinPoint joinPoint) {
        // 拼接redisLock的key
        String redisLockKey = getFinallyKeyLock(annotation, joinPoint);
        String requestID = REQUEST_ID_MAP.getRequestID(redisLockKey);
        if (requestID != null) {
            // 解锁成功
            boolean unLockSuccess = distributeLock.unLock(redisLockKey, requestID);
            if (unLockSuccess) {
                // 移除 ThreadLocal中的数据，防止内存泄漏
                REQUEST_ID_MAP.removeRequestID(redisLockKey);
                logger.info("解锁成功 redisLockKey= " + redisLockKey);
            }
        } else {
            logger.info("解锁失败 redisLockKey= " + redisLockKey);
        }
    }

    /**
     * 拼接redisLock的key
     */
    private String getFinallyKeyLock(RedisLock annotation, ProceedingJoinPoint joinPoint) {
        String applicationName = environment.getProperty("spring.application.name");
        if (applicationName == null) {
            applicationName = "";
        }

        // applicationName在前
        String finallyKey = applicationName + RedisConstants.KEY_SEPARATOR + RedisLockKeyUtil.getFinallyLockKey(annotation, joinPoint);

        if (finallyKey.length() > RedisConstants.FINALLY_KEY_LIMIT) {
            throw new RuntimeException("finallyLockKey is too long finallyKey=" + finallyKey);
        } else {
            return finallyKey;
        }
    }
}
```

## 3.8 源码参考地址

[https://github.com/FocusProgram/springcloud-redis-lock](https://github.com/FocusProgram/springcloud-redis-lock)

## 3.9 总结

**主从同步可能导致锁的互斥性失效**

- 在redis主从结构下，出于性能的考虑，redis采用的是主从异步复制的策略，这会导致短时间内主库和从库数据短暂的不一致。

- 试想，当某一客户端刚刚加锁完毕，redis主库还没有来得及和从库同步就挂了，之后从库中新选拔出的主库是没有对应锁记录的，这就可能导致多个客户端加锁成功，破坏了锁的互斥性。

**休眠并反复尝试加锁效率较低**

- lockAndRetry方法在客户端线程加锁失败后，会休眠一段时间之后再进行重试。当锁的持有者持有锁的时间很长时，其它客户端会有大量无效的重试操作，造成系统资源的浪费。

- 进一步优化时，可以使用发布订阅的方式。这时加锁失败的客户端会监听锁被释放的信号，在锁真正被释放时才会进行新的加锁操作，从而避免不必要的轮询操作，以提高效率。

**不是一个公平的锁**

- 当前实现版本中，多个客户端同时对锁进行抢占时，是完全随机的，既不遵循先来后到的顺序，客户端之间也没有加锁的优先级区别。

- 后续优化时可以提供一个创建公平锁的接口，能指定加锁的优先级，内部使用一个优先级队列维护加锁客户端的顺序。公平锁虽然效率稍低，但在一些场景能更好的控制并发行为。

</font>