package com.redis.distributelock.exception;

public class RedisLockFailException extends RuntimeException {

    public RedisLockFailException(String message) {
        super(message);
    }
}
