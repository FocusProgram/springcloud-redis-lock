package com.redis.aspectlock.annotation;

import com.redis.aspectlock.enums.RedisLockKeyType;

import java.lang.annotation.*;

@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RedisLockKey {

    String expressionKeySeparator = ",";

    RedisLockKeyType type();

    String expression() default "";
}
