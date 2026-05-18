package com.example.redisproxy.dataplane.governance;

import com.example.redisproxy.dataplane.protocol.RespRequest;

public interface CommandInterceptor {
    default boolean allow(RespRequest request) {
        return true;
    }
}
