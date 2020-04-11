package com.redis.controller;

import com.redis.service.api.AspectLockOneService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
public class AspectLockController {

    private static final Logger logger = LoggerFactory.getLogger(DistributeLockController.class);

    @Autowired
    private AspectLockOneService testService;

    @RequestMapping("/test/{parm}")
    public String test(@PathVariable("parm") String parm) throws InterruptedException {
        logger.info("接收到请求参数：" + parm);
        testService.method1(parm);
        return "ok";
    }
}
