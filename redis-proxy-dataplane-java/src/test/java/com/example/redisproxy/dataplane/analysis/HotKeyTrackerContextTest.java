package com.example.redisproxy.dataplane.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.redisproxy.dataplane.config.ProxyProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class HotKeyTrackerContextTest {
    @Test
    void canBeCreatedAsSpringBeanWithConfiguredDependencies() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
            context.registerBean(ProxyProperties.class, ProxyProperties::new);
            context.register(HotKeyTracker.class);
            context.refresh();

            assertThat(context.getBean(HotKeyTracker.class)).isNotNull();
        }
    }
}
