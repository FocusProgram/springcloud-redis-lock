package com.redis.annotation;

import com.redis.enums.RedisLockKeyType;

import java.lang.annotation.*;

@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RedisLockKey {

    String expressionKeySeparator = ",";

    RedisLockKeyType type();

    String expression() default "";
}
