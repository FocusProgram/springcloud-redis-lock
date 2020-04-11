package com.redis.service.impl;

import com.redis.annotation.RedisLock;
import com.redis.annotation.RedisLockKey;
import com.redis.enums.RedisLockKeyType;
import com.redis.service.api.AspectLockOneService;
import com.redis.service.api.AspectLockTwoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AspectLockOneServiceImpl implements AspectLockOneService {

    private static final Logger logger = LoggerFactory.getLogger(AspectLockOneServiceImpl.class);

    private int number = 0;

    @Autowired
    private AspectLockTwoService aspectLockTwoService;

    @Override
    @RedisLock(lockKey = "lockKey", retryCount = 50, expireTime = 100)
    public String method1(@RedisLockKey(type = RedisLockKeyType.ALL) String parm) throws InterruptedException {
        int sleepMS = 3000;
        Thread.sleep(sleepMS);
        number = number + 10;
        logger.info("method1 ... 休眠{}ms num={}", sleepMS, parm);
        aspectLockTwoService.method2(parm);
        return "method1 " + number;
    }
}
