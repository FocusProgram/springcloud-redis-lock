package com.redis.exception;

public class RedisLockFailException extends RuntimeException {

    public RedisLockFailException(String message) {
        super(message);
    }
}
