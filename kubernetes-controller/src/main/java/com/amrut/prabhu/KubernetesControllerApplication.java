package com.amrut.prabhu;

import com.amrut.prabhu.models.V1MyCrd;
import com.amrut.prabhu.models.V1MyCrdList;
import io.kubernetes.client.extended.controller.Controller;
import io.kubernetes.client.extended.controller.builder.ControllerBuilder;
import io.kubernetes.client.extended.controller.reconciler.Reconciler;
import io.kubernetes.client.extended.controller.reconciler.Request;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1ConfigMapList;
import io.kubernetes.client.openapi.models.V1OwnerReference;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.generic.GenericKubernetesApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootApplication
public class KubernetesControllerApplication {

    public static final Logger logger = LoggerFactory.getLogger(KubernetesControllerApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(KubernetesControllerApplication.class, args);
    }

    @Bean
    ApiClient apiClient() throws IOException {
        return Config.defaultClient();
    }

    @Bean
    SharedInformerFactory sharedInformerFactory(ApiClient apiClient) {
        return new SharedInformerFactory(apiClient);
    }

    @Bean
    GenericKubernetesApi<V1MyCrd, V1MyCrdList> myCrdApi(ApiClient apiClient) {
        return new GenericKubernetesApi<>(V1MyCrd.class,
                V1MyCrdList.class,
                "com.amrut.prabhu",
                "v1",
                "my-crds",
                apiClient);
    }

    @Bean
    SharedIndexInformer<V1MyCrd> myCrdInformer(SharedInformerFactory sharedInformerFactory,
                                               GenericKubernetesApi<V1MyCrd, V1MyCrdList> myCrdApi) {
        return sharedInformerFactory.sharedIndexInformerFor(myCrdApi, V1MyCrd.class, 0);
    }

    @Bean
    GenericKubernetesApi<V1ConfigMap, V1ConfigMapList> configMapApi(ApiClient apiClient) {
        return new GenericKubernetesApi<>(V1ConfigMap.class,
                V1ConfigMapList.class,
                "",
                "v1",
                "configmaps",
                apiClient);
    }

    @Bean
    SharedIndexInformer<V1ConfigMap> configMapInformer(SharedInformerFactory sharedInformerFactory,
                                                       GenericKubernetesApi<V1ConfigMap, V1ConfigMapList> configMapApi) {
        return sharedInformerFactory.sharedIndexInformerFor(configMapApi, V1ConfigMap.class, 0);
    }

    @Bean
    Controller controller(SharedInformerFactory sharedInformerFactory,
                          Reconciler reconciler,
                          SharedIndexInformer<V1MyCrd> myCrdInformer,
                          SharedIndexInformer<V1ConfigMap> configMapInformer) {
        return ControllerBuilder
                .defaultBuilder(sharedInformerFactory)
                .watch(workQueue -> ControllerBuilder
                        .controllerWatchBuilder(V1MyCrd.class, workQueue)
                        .withResyncPeriod(Duration.of(1, ChronoUnit.SECONDS))
                        .build())
                .watch(workQueue -> ControllerBuilder
                        .controllerWatchBuilder(V1ConfigMap.class, workQueue)
                        .withWorkQueueKeyFunc(KubernetesControllerApplication::myCrdKeyForConfigMap)
                        .withResyncPeriod(Duration.of(1, ChronoUnit.SECONDS))
                        .build())
                .withWorkerCount(2)
                .withReconciler(reconciler)
                .withReadyFunc(() -> myCrdInformer.hasSynced() && configMapInformer.hasSynced())
                .withName("My controller")
                .build();
    }

    private static Request myCrdKeyForConfigMap(V1ConfigMap v1ConfigMap) {
        List<V1OwnerReference> ownerReferences = v1ConfigMap.getMetadata().getOwnerReferences();
        if (ownerReferences == null || ownerReferences.size() < 1) {
            return null;
        }

        V1OwnerReference ownerReference = ownerReferences.get(0);
        if (!"my-crd".equals(ownerReference.getKind())) {
            return null;
        }

        return new Request(v1ConfigMap.getMetadata().getNamespace(), ownerReference.getName());
    }

    @Bean
    ExecutorService executorService() {
        return Executors.newCachedThreadPool();
    }

    @Bean
    ApplicationRunner runner(ExecutorService executorService,
                             SharedInformerFactory sharedInformerFactory,
                             Controller controller) {
        return args -> executorService.execute(() -> {
            sharedInformerFactory.startAllRegisteredInformers();
            controller.run();
        });
    }

    @Bean
    CoreV1Api coreV1Api(ApiClient apiClient) {
        return new CoreV1Api(apiClient);
    }
}
