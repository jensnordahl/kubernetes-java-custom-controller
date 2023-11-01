package com.amrut.prabhu;

import com.amrut.prabhu.models.V1MyCrd;
import com.amrut.prabhu.models.V1MyCrdList;
import com.amrut.prabhu.models.V1MyCrdStatus;
import io.kubernetes.client.extended.controller.reconciler.Reconciler;
import io.kubernetes.client.extended.controller.reconciler.Request;
import io.kubernetes.client.extended.controller.reconciler.Result;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.JSON;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1OwnerReference;
import io.kubernetes.client.util.generic.GenericKubernetesApi;
import io.kubernetes.client.util.generic.KubernetesApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class MyCrdReconciler implements Reconciler {

    public static final Logger logger = LoggerFactory.getLogger(MyCrdReconciler.class);

    private final SharedIndexInformer<V1MyCrd> myCrdInformer;
    private final SharedIndexInformer<V1ConfigMap> configMapInformer;
    private final CoreV1Api coreV1Api;
    private final GenericKubernetesApi<V1MyCrd, V1MyCrdList> myCrdApi;

    public MyCrdReconciler(SharedIndexInformer<V1MyCrd> myCrdInformer, SharedIndexInformer<V1ConfigMap> configMapInformer, CoreV1Api coreV1Api, GenericKubernetesApi<V1MyCrd, V1MyCrdList> myCrdApi) {
        this.myCrdInformer = myCrdInformer;
        this.configMapInformer = configMapInformer;
        this.coreV1Api = coreV1Api;
        this.myCrdApi = myCrdApi;
    }

    @Override
    public Result reconcile(Request request) {
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
    }

    private static <T> T deepCopy(T t) {
        // Deep copy kludge.
        // Use kubernetes client wrapper to gson, rather than plain gson
        // to have it set up with type adapters
        JSON json = new JSON();
        return (T) json.deserialize(json.serialize(t), t.getClass());
    }

    private static V1ConfigMap createConfigMap(V1MyCrd resourceInstance) {
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


}
