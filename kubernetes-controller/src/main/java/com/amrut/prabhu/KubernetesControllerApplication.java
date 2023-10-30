package com.amrut.prabhu;

import com.amrut.prabhu.models.V1MyCrd;
import com.amrut.prabhu.models.V1MyCrdList;
import io.kubernetes.client.extended.controller.Controller;
import io.kubernetes.client.extended.controller.builder.ControllerBuilder;
import io.kubernetes.client.extended.controller.reconciler.Reconciler;
import io.kubernetes.client.extended.controller.reconciler.Request;
import io.kubernetes.client.extended.controller.reconciler.Result;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.*;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
    SharedIndexInformer<V1MyCrd> sharedIndexInformer(SharedInformerFactory sharedInformerFactory, ApiClient apiClient) {
        GenericKubernetesApi<V1MyCrd, V1MyCrdList> api = new GenericKubernetesApi<>(V1MyCrd.class,
                V1MyCrdList.class,
                "com.amrut.prabhu",
                "v1",
                "my-crds",
                apiClient);
        return sharedInformerFactory.sharedIndexInformerFor(api, V1MyCrd.class, 0);
    }

    @Bean
    SharedIndexInformer<V1ConfigMap> configMapInformer(SharedInformerFactory sharedInformerFactory, ApiClient apiClient) {
        GenericKubernetesApi<V1ConfigMap, V1ConfigMapList> api = new GenericKubernetesApi<>(V1ConfigMap.class,
                V1ConfigMapList.class,
                "",
                "v1",
                "configmaps",
                apiClient);
        return sharedInformerFactory.sharedIndexInformerFor(api, V1ConfigMap.class, 0);
    }


    @Bean
    Controller controller(SharedInformerFactory shareformerFactory,
                          Reconciler reconsiler,
                          SharedIndexInformer<V1MyCrd> shareIndexInformer) {
        return ControllerBuilder
                .defaultBuilder(shareformerFactory)
                .watch(contrWatchQueue -> ControllerBuilder
                        .controllerWatchBuilder(V1MyCrd.class, contrWatchQueue)
                        .withResyncPeriod(Duration.of(1, ChronoUnit.SECONDS))
                        .build())
                .watch(contrWatchQueue -> ControllerBuilder
                        .controllerWatchBuilder(V1ConfigMap.class, contrWatchQueue)
                        .withWorkQueueKeyFunc(this::myCrdKeyForConfigMap)
                        .withResyncPeriod(Duration.of(1, ChronoUnit.SECONDS))
                        .build())
                // .withWorkerCount(2)
                .withReconciler(reconsiler)
                .withReadyFunc(shareIndexInformer::hasSynced)
                .withName("My controller")
                .build();
    }

    private Request myCrdKeyForConfigMap(V1ConfigMap v1ConfigMap) {
        List<V1OwnerReference> ownerReferences = v1ConfigMap.getMetadata().getOwnerReferences();
        if (ownerReferences == null || ownerReferences.size() < 1) {
            return null;
        }

        V1OwnerReference ownerReference = ownerReferences.get(0);
        if (! "my-crd".equals(ownerReference.getKind())) {
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

    @Bean
    Reconciler reconciler(SharedIndexInformer<V1MyCrd> shareIndexInformer,
                               CoreV1Api coreV1Api) {
        return request -> {
            logger.info("==== reconcile: " + request);
            String key = request.getNamespace() + "/" + request.getName();

            V1MyCrd resourceInstance = shareIndexInformer
                    .getIndexer()
                    .getByKey(key);

            if (resourceInstance != null) {

                V1ConfigMap v1ConfigMap = createConfigMap(resourceInstance);
                System.out.println("Creating resource...");

                try {
                    coreV1Api.createNamespacedConfigMap(request.getNamespace(),
                            v1ConfigMap,
                            "true",
                            null,
                            "",
                            "");
                } catch (ApiException e) {
                    System.out.println("Creating resource failed");
                    if (e.getCode() == 409) {
                        System.out.println("Updating resource...");
                        try {
                            coreV1Api.replaceNamespacedConfigMap("my-config-map",
                                    request.getNamespace(),
                                    v1ConfigMap,
                                    "true",
                                    null,
                                    "",
                                    "");
                        } catch (ApiException ex) {
                            throw new RuntimeException(ex);
                        }
                    } else {
                        throw new RuntimeException(e);
                    }
                }
                return new Result(false);
            }
            return new Result(false);

        };
    }

    private V1ConfigMap createConfigMap(V1MyCrd resourceInstance) {
        return new V1ConfigMap()
                .metadata(new V1ObjectMeta()
                        .name("my-config-map")
                        .addOwnerReferencesItem(new V1OwnerReference()
                                .apiVersion(resourceInstance.getApiVersion())
                                .kind(resourceInstance.getKind())
                                .name(resourceInstance.getMetadata().getName())
                                .uid(resourceInstance.getMetadata().getUid())))
                .data(Map.of("my-own-property", resourceInstance
                        .getSpec()
                        .getMyOwnProperty()));
    }

    private static V1Deployment createDeployment() {
        return new V1Deployment()
                .metadata(new V1ObjectMeta().name("nginx-deploy"))
                .spec(new V1DeploymentSpec()
                        .template(new V1PodTemplateSpec()
                                .metadata(new V1ObjectMeta()
                                        .labels(Map.of("app", "nginx")))
                                .spec(new V1PodSpec()
                                        .containers(Arrays.asList(new V1Container().name("nginx")
                                                .image("nginx:latest"))))));

    }
}
