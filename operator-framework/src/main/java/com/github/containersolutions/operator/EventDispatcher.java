package com.github.containersolutions.operator;

import com.github.containersolutions.operator.api.ResourceController;
import io.fabric8.kubernetes.client.*;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.internal.CustomResourceOperationsImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

public class EventDispatcher<R extends CustomResource> implements Watcher<R> {

    private final static Logger log = LoggerFactory.getLogger(EventDispatcher.class);

    private final ResourceController<R> controller;
    private final CustomResourceOperationsImpl<R, CustomResourceList<R>, CustomResourceDoneable<R>> resourceOperation;
    private final String resourceDefaultFinalizer;
    private final NonNamespaceOperation<R, CustomResourceList<R>, CustomResourceDoneable<R>,
            Resource<R, CustomResourceDoneable<R>>> resourceClient;
    private final KubernetesClient k8sClient;

    public EventDispatcher(ResourceController<R> controller,
                           CustomResourceOperationsImpl<R, CustomResourceList<R>, CustomResourceDoneable<R>> resourceOperation,
                           NonNamespaceOperation<R, CustomResourceList<R>, CustomResourceDoneable<R>,
                                   Resource<R, CustomResourceDoneable<R>>> resourceClient, KubernetesClient k8sClient,
                           String defaultFinalizer

    ) {

        this.controller = controller;
        this.resourceOperation = resourceOperation;
        this.resourceClient = resourceClient;
        this.resourceDefaultFinalizer = defaultFinalizer;
        this.k8sClient = k8sClient;
    }

    public void eventReceived(Action action, R resource) {
        try {
            log.debug("Action: {}, {}: {}", action, resource.getClass().getSimpleName(), resource.getMetadata().getName());
            handleEvent(action, resource);
        } catch (RuntimeException e) {
            log.error("Error on resource: {}", resource.getMetadata().getName(), e);
        }
    }

    private void handleEvent(Action action, R resource) {
        if (action == Action.MODIFIED || action == Action.ADDED) {
            // we don't want to call delete resource if it not contains our finalizer,
            // since the resource still can be updates when marked for deletion and contains other finalizers
            if (markedForDeletion(resource) && hasDefaultFinalizer(resource)) {
                controller.deleteResource(resource, new Context(k8sClient, resourceClient));
                removeDefaultFinalizer(resource);
            } else {
                R updatedResource = controller.createOrUpdateResource(resource, new Context<>(k8sClient, resourceClient));
                addFinalizerIfNotPresent(updatedResource);
                replace(updatedResource);
            }
        }
        if (Action.ERROR == action) {
            log.error("Received error for resource: {}", resource.getMetadata().getName());
        }
        if (Action.DELETED == action) {
            log.debug("Resource deleted: {}", resource.getMetadata().getName());
        }
    }

    private boolean hasDefaultFinalizer(R resource) {
        if (resource.getMetadata().getFinalizers() != null) {
            return resource.getMetadata().getFinalizers().contains(resourceDefaultFinalizer);
        }
        return false;
    }

    private void removeDefaultFinalizer(R resource) {
        resource.getMetadata().getFinalizers().remove(resourceDefaultFinalizer);
        resourceOperation.lockResourceVersion(resource.getMetadata().getResourceVersion()).replace(resource);
    }

    private void replace(R resource) {
        resourceOperation.lockResourceVersion(resource.getMetadata().getResourceVersion()).replace(resource);
    }

    private void addFinalizerIfNotPresent(R resource) {
        if (!hasDefaultFinalizer(resource)) {
            if (resource.getMetadata().getFinalizers() == null) {
                resource.getMetadata().setFinalizers(new ArrayList<>(1));
            }
            resource.getMetadata().getFinalizers().add(resourceDefaultFinalizer);
        }
    }

    private boolean markedForDeletion(R resource) {
        return resource.getMetadata().getDeletionTimestamp() != null && !resource.getMetadata().getDeletionTimestamp().isEmpty();
    }

    @Override
    public void onClose(KubernetesClientException e) {
        if (e != null) {
            log.error("Error: ", e);
        }
    }
}