package com.example.redisproxy.dataplane.router;

import com.example.redisproxy.dataplane.config.ProxyProperties;
import com.example.redisproxy.dataplane.protocol.RespRequest;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RouteResolver {
    private final ProxyProperties properties;
    private final ProxyProperties.Cluster defaultCluster;

    public RouteResolver(ProxyProperties properties) {
        properties.validate();
        this.properties = properties;
        this.defaultCluster = properties.getBackends().getClusters().stream()
                .filter(c -> properties.getRouting().getDefaultCluster().equals(c.getName()))
                .findFirst()
                .orElseThrow();
    }

    public String route(RespRequest request) {
        List<String> nodes = defaultCluster.getNodes();
        if (!"cluster".equals(properties.getMode()) || nodes.size() == 1 || request.args().size() < 2) {
            return nodes.getFirst();
        }
        int slot = RedisSlot.slot(request.args().get(1));
        return nodes.get(slot % nodes.size());
    }
}
