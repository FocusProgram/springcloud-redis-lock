package com.redis.enums;

public enum RedisLockKeyType {
    /**
     * 当前对象的toString做key
     */
    ALL,

    /**
     * 当前对象的内部属性的toString做key
     */
    FIELD,
    ;
}
