package com.zuomagai.redisproxy.dataplane.governance;

import com.zuomagai.redisproxy.dataplane.protocol.RespRequest;

public interface CommandInterceptor {
    default boolean allow(RespRequest request) {
        return true;
    }
}
