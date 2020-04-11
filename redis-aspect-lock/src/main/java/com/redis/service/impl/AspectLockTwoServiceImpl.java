package com.redis.service.impl;

import com.redis.annotation.RedisLockKey;
import com.redis.annotation.RedisLock;
import com.redis.enums.RedisLockKeyType;
import com.redis.service.api.AspectLockTwoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AspectLockTwoServiceImpl implements AspectLockTwoService {

    private static final Logger logger = LoggerFactory.getLogger(AspectLockTwoServiceImpl.class);

    @Override
    @RedisLock(lockKey = "lockKey", expireTime = 100, retryCount = 3)
    public String method2(@RedisLockKey(type = RedisLockKeyType.ALL) String parm) throws InterruptedException {
        int sleepMS = 1000;
        Thread.sleep(sleepMS);
        logger.info("method2 ... 休眠{}ms num={}", sleepMS, parm);
        return "method2";
    }

}
