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

import java.util.List;
import java.util.Map;
import java.util.function.Function;

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

        V1MyCrd resource = myCrdInformer.getIndexer().getByKey(key);
        if (resource == null) {
            // Resource deleted since it was added to the work queue - forget it
            return new Result(true);
        }

        String expectedChildName = childNameForResource(resource);
        Map<String, String> expectedChildData = childDataForResource(resource);
        boolean actualChildFound = false;

        List<V1ConfigMap> actualChildren = findChildren(resource);
        for (V1ConfigMap child : actualChildren) {
            if (expectedChildName.equals(child.getMetadata().getName())) {
                actualChildFound = true;
                if (!expectedChildData.equals(child.getData())) {
                    updateChild(child, expectedChildData);
                }

                updateStatus(resource, child);
            } else {
                deleteChild(child);
            }
        }

        if (!actualChildFound) {
            createChild(resource, expectedChildName, expectedChildData);
        }

        return new Result(false);
    }

    private List<V1ConfigMap> findChildren(V1MyCrd resource) {
        return configMapInformer.getIndexer().list().stream()
                .filter(child -> isOwnedBy(resource, child))
                .toList();
    }

    private boolean isOwnedBy(V1MyCrd resource, V1ConfigMap child) {
        List<V1OwnerReference> ownerReferences = child.getMetadata().getOwnerReferences();
        if (ownerReferences == null) {
            return false;
        }
        return ownerReferences.stream()
                .anyMatch(v1OwnerReference
                        -> v1OwnerReference.getUid().equals(resource.getMetadata().getUid()));
    }

    private void createChild(V1MyCrd resourceInstance, String childName, Map<String, String> childData) {
        logger.info("Creating child configmap: " + childName);
        V1ConfigMap child = new V1ConfigMap()
                .metadata(new V1ObjectMeta()
                        .name(childName)
                        .addOwnerReferencesItem(new V1OwnerReference()
                                .apiVersion(resourceInstance.getApiVersion())
                                .kind(resourceInstance.getKind())
                                .name(resourceInstance.getMetadata().getName())
                                .uid(resourceInstance.getMetadata().getUid())))
                .data(childData);

        try {
            coreV1Api.createNamespacedConfigMap(
                    resourceInstance.getMetadata().getNamespace(),
                    child,
                    "true",
                    null,
                    "",
                    "");
        } catch (ApiException e) {
            throw new RuntimeException("Failed to create configmap", e);
        }
    }

    private void updateChild(V1ConfigMap child, Map<String, String> childData) {
        logger.info("Updating child configmap: " + child.getMetadata().getName());
        V1ConfigMap copy = deepCopy(child);
        copy.setData(childData);

        try {
            coreV1Api.replaceNamespacedConfigMap(copy.getMetadata().getName(),
                    copy.getMetadata().getNamespace(),
                    copy,
                    "true",
                    null,
                    "",
                    "");
        } catch (ApiException e) {
            throw new RuntimeException("Failed to update configmap", e);
        }
    }

    private void deleteChild(V1ConfigMap child) {
        logger.info("Deleting child configmap: " + child.getMetadata().getName());
        try {
            coreV1Api.deleteNamespacedConfigMap(
                    child.getMetadata().getName(),
                    child.getMetadata().getNamespace(),
                    "true",
                    null,
                    0,
                    false,
                    "",
                    null);
        } catch (ApiException e) {
            throw new RuntimeException("Failed to delete configmap", e);
        }
    }

    private void updateStatus(V1MyCrd resource, V1ConfigMap child) {
        logger.info("Updating status: " + child.getMetadata().getName());
        KubernetesApiResponse<V1MyCrd> apiResponse = myCrdApi.updateStatus(resource, r -> new V1MyCrdStatus().configMapId(child.getMetadata().getUid()));
        if (!apiResponse.isSuccess()) {
            throw new RuntimeException("Failed to update status: " + apiResponse.getStatus());
        }
    }

    private Map<String, String> childDataForResource(V1MyCrd resource) {
        return Map.of("my-own-property", resource
                .getSpec()
                .getMyOwnProperty());
    }

    private String childNameForResource(V1MyCrd resource) {
        return resource.getMetadata().getName() + "-child";
    }

    private static <T> T deepCopy(T t) {
        // Deep copy kludge.
        // Use kubernetes client wrapper to gson, rather than plain gson
        // to have it set up with type adapters
        JSON json = new JSON();
        return json.deserialize(json.serialize(t), t.getClass());
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
