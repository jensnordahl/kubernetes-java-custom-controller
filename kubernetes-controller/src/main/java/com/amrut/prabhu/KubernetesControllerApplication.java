package com.amrut.prabhu;

import com.amrut.prabhu.models.V1MyCrd;
import com.amrut.prabhu.models.V1MyCrdList;
import com.amrut.prabhu.models.V1MyCrdStatus;
import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import io.kubernetes.client.extended.controller.Controller;
import io.kubernetes.client.extended.controller.builder.ControllerBuilder;
import io.kubernetes.client.extended.controller.reconciler.Reconciler;
import io.kubernetes.client.extended.controller.reconciler.Request;
import io.kubernetes.client.extended.controller.reconciler.Result;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.JSON;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.generic.GenericKubernetesApi;
import io.kubernetes.client.util.generic.KubernetesApiResponse;
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
                        .withWorkQueueKeyFunc(this::myCrdKeyForConfigMap)
                        .withResyncPeriod(Duration.of(1, ChronoUnit.SECONDS))
                        .build())
                .withWorkerCount(2)
                .withReconciler(reconciler)
                .withReadyFunc(() -> myCrdInformer.hasSynced() && configMapInformer.hasSynced())
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
    Reconciler reconciler(SharedIndexInformer<V1MyCrd> myCrdInformer,
                          CoreV1Api coreV1Api,
                          GenericKubernetesApi<V1MyCrd, V1MyCrdList> myCrdApi
                          ) {
        return request -> {
            logger.info("==== reconcile: " + request);
            String key = request.getNamespace() + "/" + request.getName();

            V1MyCrd resourceInstance = myCrdInformer
                    .getIndexer()
                    .getByKey(key);

            if (resourceInstance != null) {

                V1ConfigMap v1ConfigMap = createConfigMap(resourceInstance);
                logger.info("Creating resource...");

                V1MyCrd copy = deepCopy(resourceInstance);
                copy.setStatus(new V1MyCrdStatus().configMapId("fake-status"));
                KubernetesApiResponse<V1MyCrd> update = myCrdApi.update(copy);
                if (! update.isSuccess()) {
                    logger.warn("Failed to update, re-queueing: " + update.getStatus());
                    return new Result(true);
                }

                try {
                    coreV1Api.createNamespacedConfigMap(request.getNamespace(),
                            v1ConfigMap,
                            "true",
                            null,
                            "",
                            "");
                } catch (ApiException e) {
                    logger.info("Creating resource failed");
                    if (e.getCode() == 409) {
                        logger.info("Updating resource...");
                        try {
                            coreV1Api.replaceNamespacedConfigMap(v1ConfigMap.getMetadata().getName(),
                                    request.getNamespace(),
                                    v1ConfigMap,
                                    "true",
                                    null,
                                    "",
                                    "");
                        } catch (ApiException ex) {
                            logger.warn("Failed updating resource..." + ex.getResponseBody());
                            throw new RuntimeException(ex);
                        }
                    } else {
                        logger.warn("Failed creating resource (wrong reason)" + e.getResponseBody());
                        throw new RuntimeException(e);
                    }
                }
                return new Result(false);
            }
            return new Result(false);

        };
    }

    private <T> T deepCopy(T t) {
        // Deep copy kludge based on serialize+deserialize
        // Use kubernetes client wrapper to gson, rather than plain gson
        // to have it set up with type adapters
        JSON json = new JSON();
        return (T) json.deserialize(json.serialize(t), t.getClass());
    }

    private V1ConfigMap createConfigMap(V1MyCrd resourceInstance) {
        return new V1ConfigMap()
                .metadata(new V1ObjectMeta()
                        .name(resourceInstance.getMetadata().getName() + "-child")
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
