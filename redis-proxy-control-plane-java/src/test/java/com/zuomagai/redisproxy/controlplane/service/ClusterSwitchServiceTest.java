package com.zuomagai.redisproxy.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zuomagai.redisproxy.controlplane.model.ClusterSwitchJumpRequest;
import com.zuomagai.redisproxy.controlplane.model.ClusterSwitchPlan;
import com.zuomagai.redisproxy.controlplane.model.CreateClusterSwitchPlanRequest;
import com.zuomagai.redisproxy.controlplane.model.ProxyConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClusterSwitchServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void stagedPlanPublishesStepsAndCompletesByChangingDefaultCluster() {
        ConfigService configService = new ConfigService();
        ClusterSwitchService service = service(configService);
        ClusterSwitchPlan plan = service.create(request("STAGED"));

        plan = service.precheck(plan.getPlanId());
        assertThat(plan.getStatus()).isEqualTo("PRECHECKED");

        plan = service.start(plan.getPlanId());
        assertThat(plan.getStatus()).isEqualTo("RUNNING");
        assertThat(plan.currentTrafficPercent()).isZero();
        assertThat(configService.get().getRouting().getDefaultCluster()).isEqualTo("redis-a");
        assertThat(configService.get().getRouting().getRules().getFirst().isMatchAll()).isTrue();

        plan = service.advance(plan.getPlanId());
        assertThat(plan.currentTrafficPercent()).isEqualTo(10);
        assertThat(configService.get().getRouting().getRules().getFirst().getTrafficPercent()).isEqualTo(10);

        plan = service.jump(plan.getPlanId(), jump(100));
        assertThat(plan.getStatus()).isEqualTo("COMPLETED");
        assertThat(configService.get().getRouting().getDefaultCluster()).isEqualTo("redis-b");
        assertThat(configService.get().getRouting().getRules()).isEmpty();
        assertThat(plan.getPublishedSteps()).hasSize(3);
    }

    @Test
    void fullPlanSwitchesDefaultClusterInSinglePublish() {
        ConfigService configService = new ConfigService();
        ClusterSwitchService service = service(configService);
        ClusterSwitchPlan plan = service.create(request("FULL"));

        plan = service.start(plan.getPlanId());

        assertThat(plan.getStatus()).isEqualTo("COMPLETED");
        assertThat(plan.currentTrafficPercent()).isEqualTo(100);
        assertThat(configService.get().getRouting().getDefaultCluster()).isEqualTo("redis-b");
        assertThat(configService.get().getRouting().getRules()).isEmpty();
    }

    @Test
    void createsPlanWhenTargetClusterAlreadyExistsInCurrentConfig() {
        ConfigService configService = new ConfigService();
        ProxyConfig config = configService.get();
        ProxyConfig.Cluster target = new ProxyConfig.Cluster();
        target.setName("redis-b");
        target.setNodes(List.of("127.0.0.1:6380"));
        config.getBackends().setClusters(List.of(config.getBackends().getClusters().getFirst(), target));
        configService.update(config);
        ClusterSwitchService service = service(configService);
        CreateClusterSwitchPlanRequest request = request("STAGED");
        request.setTargetClusterDefinition(null);

        ClusterSwitchPlan plan = service.create(request);
        plan = service.precheck(plan.getPlanId());

        assertThat(plan.getTargetCluster()).isEqualTo("redis-b");
        assertThat(plan.getTargetClusterDefinition()).isNull();
        assertThat(plan.getStatus()).isEqualTo("PRECHECKED");
    }

    @Test
    void rollbackRestoresBaselineWithHigherEpoch() {
        ConfigService configService = new ConfigService();
        ClusterSwitchService service = service(configService);
        ClusterSwitchPlan plan = service.create(request("STAGED"));
        plan = service.start(plan.getPlanId());
        plan = service.advance(plan.getPlanId());
        long beforeRollbackEpoch = configService.get().getRouting().getRouteEpoch();

        plan = service.rollback(plan.getPlanId(), jump(0));

        assertThat(plan.getStatus()).isEqualTo("ROLLED_BACK");
        assertThat(configService.get().getRouting().getDefaultCluster()).isEqualTo("redis-a");
        assertThat(configService.get().getRouting().getRules()).isEmpty();
        assertThat(configService.get().getRouting().getRouteEpoch()).isGreaterThan(beforeRollbackEpoch);
        assertThat(plan.getPublishedSteps().getLast().getAction()).isEqualTo("ROLLBACK");
    }

    @Test
    void rejectsDuplicateActivePlanForSameSource() {
        ConfigService configService = new ConfigService();
        ClusterSwitchService service = service(configService);
        service.create(request("STAGED"));

        assertThatThrownBy(() -> service.create(request("STAGED")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active cluster switch plan");
    }

    private ClusterSwitchService service(ConfigService configService) {
        return new ClusterSwitchService(configService, new ObservabilityService(objectMapper), objectMapper);
    }

    private CreateClusterSwitchPlanRequest request(String mode) {
        CreateClusterSwitchPlanRequest request = new CreateClusterSwitchPlanRequest();
        request.setSourceCluster("redis-a");
        request.setTargetCluster("redis-b");
        request.setMode(mode);
        request.setOperator("ops");
        request.setReason("machine migration");
        ProxyConfig.Cluster target = new ProxyConfig.Cluster();
        target.setName("redis-b");
        target.setNodes(List.of("127.0.0.1:6380"));
        request.setTargetClusterDefinition(target);
        return request;
    }

    private ClusterSwitchJumpRequest jump(int percent) {
        ClusterSwitchJumpRequest request = new ClusterSwitchJumpRequest();
        request.setTrafficPercent(percent);
        request.setOperator("ops");
        request.setReason("jump");
        return request;
    }
}
