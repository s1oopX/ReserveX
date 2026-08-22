package com.reservex.config;

import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.spring.support.RocketMQUtil;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class RocketMqCompatibilityTest {

    @Test
    void starterUsesTheAclHookBundledWithClient55() {
        assertInstanceOf(AclClientRPCHook.class,
                RocketMQUtil.getRPCHookByAkSk(new MockEnvironment(),
                        "access-key", "secret-key"));
    }
}
