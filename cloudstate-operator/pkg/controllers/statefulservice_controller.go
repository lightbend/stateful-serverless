/*
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package controllers

import (
	"context"
	"errors"
	"fmt"
	"github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/listeners"
	"time"

	"github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/config"
	"github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/reconciliation"
	"github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/stores"

	"github.com/banzaicloud/k8s-objectmatcher/patch"
	cloudstate "github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/apis/v1alpha1"
	"github.com/go-logr/logr"
	appsv1 "k8s.io/api/apps/v1"
	autoscalingv2beta2 "k8s.io/api/autoscaling/v2beta2"
	corev1 "k8s.io/api/core/v1"
	rbacv1 "k8s.io/api/rbac/v1"
	apierrs "k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/api/meta"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/util/intstr"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
)

const (
	CloudstateStatefulServiceLabel            = "cloudstate.io/stateful-service"
	CloudstateStatefulServiceConfigAnnotation = "cloudstate.io/stateful-service-config"
	CloudstateEnabledAnnotation               = "cloudstate.io/enabled"
)

var (
	defaultReplicas int32 = 1
)

// StatefulServiceReconciler reconciles a StatefulService object.
type StatefulServiceReconciler struct {
	client.Client
	Log              logr.Logger
	Scheme           *runtime.Scheme
	ReconcileTimeout time.Duration
	OperatorConfig   *config.OperatorConfig
	Stores           stores.Stores
	Listener         listeners.StatefulServiceListener
}

// +kubebuilder:rbac:groups=cloudstate.io,resources=statefulservices,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=cloudstate.io,resources=statefulservices/status,verbs=get;update;patch
// +kubebuilder:rbac:groups=core,resources=services;configmaps;secrets,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=core,resources=namespaces;pods,verbs=get;watch;list
// +kubebuilder:rbac:groups=apps,resources=deployments,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=sql.cnrm.cloud.google.com,resources=sqldatabases,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=sql.cnrm.cloud.google.com,resources=sqlusers,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=autoscaling,resources=horizontalpodautoscalers,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=rbac.authorization.k8s.io,resources=roles;rolebindings,verbs=get;list;watch;create;update;patch;delete

func (r *StatefulServiceReconciler) Reconcile(req ctrl.Request) (ctrl.Result, error) {
	ctx, cancelFunc := context.WithTimeout(context.Background(), r.ReconcileTimeout)
	defer cancelFunc()
	log := r.Log.WithValues("statefulservice", req.NamespacedName)

	// Get the stateful service to reconcile.
	var service cloudstate.StatefulService
	if err := r.Get(ctx, req.NamespacedName, &service); err != nil {
		log.Error(err, "Unable to fetch StatefulService")
		return ctrl.Result{}, ignoreNotFound(err)
	}

	// Get the ConfigMap for the service.
	configMap, err := r.getOrCreateConfigMap(ctx, &service)
	if err != nil {
		return ctrl.Result{}, fmt.Errorf("unable to create ConfigMap: %w", err)
	}
	// Parse the config.
	config, err := config.ParseStatefulServiceFromConfigMapWithDefaults(configMap)
	if err != nil {
		return ctrl.Result{}, fmt.Errorf("unable to parse config from ConfigMap: %w", err)
	}
	// Get owned deployment.
	obj, err := reconciliation.GetControlledStructured(ctx, r, &service, &appsv1.DeploymentList{})
	if err != nil {
		return ctrl.Result{}, err
	}
	var actual *appsv1.Deployment
	if obj != nil {
		var ok bool
		if actual, ok = obj.(*appsv1.Deployment); !ok {
			return ctrl.Result{}, fmt.Errorf("controlled structured: %+v is no appsv1.Deployment: %w", obj, err)
		}
	}
	// Tweak statefulservice if need be.
	if err := r.checkStatefulService(ctx, &service); err != nil {
		return ctrl.Result{}, fmt.Errorf("unable to fixup statefulservice: %w", err)
	}
	// Create service for deployment pods.
	if _, err := r.reconcileService(ctx, &service); err != nil {
		return ctrl.Result{}, fmt.Errorf("unable to reconcile service: %w", err)
	}
	// Create HPA for pods.
	if err := r.reconcileHpa(ctx, &service, config); err != nil {
		return ctrl.Result{}, fmt.Errorf("unable to reconcile horizontal pod autoscaler: %w", err)
	}
	if err := r.reconcileRoleAndBinding(ctx, log, service.Namespace); err != nil {
		return ctrl.Result{}, fmt.Errorf("unable to reconcile role and role binding: %w", err)
	}
	desired, err := r.createDesiredDeployment(&service, config)
	if err != nil {
		return ctrl.Result{}, err
	}
	conditions, err := r.Stores.ReconcileStatefulService(ctx, &service, desired)
	if err != nil {
		return ctrl.Result{}, err
	}
	if err := r.updateStatus(ctx, log, &service, actual, conditions); err != nil {
		return ctrl.Result{}, err
	}

	// Update last applied annotation to detect when updates happen.
	setLastApplied(desired)

	// Create new deployment
	if actual == nil {
		if err := r.Create(ctx, desired); err != nil {
			if apierrs.IsAlreadyExists(err) {
				log.V(1).Info("Deployment creation already in progress, skipping")
				return ctrl.Result{}, nil
			}
			log.Error(err, "Unable to create deployment")
			return ctrl.Result{}, err
		}
		if err := r.Listener.DeploymentCreated(ctx, &service, desired); err != nil {
			return ctrl.Result{}, err
		}
		log.Info("Created deployment")
		return ctrl.Result{}, nil
	}

	// Check if needs update.
	if !needsUpdate(log.WithValues("type", "deployment"), actual, desired) {
		log.V(1).Info("No change in deployment")
		return ctrl.Result{}, nil
	}

	if err := r.Update(ctx, desired); err != nil {
		log.Error(err, "Unable to update deployment")
		return ctrl.Result{}, err
	}

	if err := r.Listener.DeploymentUpdated(ctx, &service, actual, desired); err != nil {
		return ctrl.Result{}, err
	}

	log.Info("Updated deployment")
	return ctrl.Result{}, nil
}

func (r *StatefulServiceReconciler) getOrCreateConfigMap(ctx context.Context, statefulService *cloudstate.StatefulService) (*corev1.ConfigMap, error) {
	obj, err := reconciliation.GetControlledStructured(ctx, r, statefulService, &corev1.ConfigMapList{})
	if err != nil {
		return nil, err
	}
	if obj != nil {
		// Don't do anything, return it as is
		return obj.(*corev1.ConfigMap), nil
	}

	configMap := &corev1.ConfigMap{
		ObjectMeta: metav1.ObjectMeta{
			Name:      "ss-cfg-" + statefulService.Name,
			Namespace: statefulService.Namespace,
		},
		Data: make(map[string]string),
	}
	config.SetExampleStatefulServiceConfigMap(configMap)
	if err := ctrl.SetControllerReference(statefulService, configMap, r.Scheme); err != nil {
		return nil, err
	}
	return configMap, r.Create(ctx, configMap)
}

func (r *StatefulServiceReconciler) createDesiredDeployment(service *cloudstate.StatefulService, config *config.StatefulServiceConfig) (*appsv1.Deployment, error) {
	containers := make([]corev1.Container, len(service.Spec.Containers))
	copy(containers, service.Spec.Containers)
	if len(containers) > 0 && containers[0].Name == "" {
		containers[0].Name = "user-function"
	}
	desired := &appsv1.Deployment{
		ObjectMeta: metav1.ObjectMeta{
			Name:        service.Name,
			Namespace:   service.Namespace,
			Labels:      make(map[string]string),
			Annotations: make(map[string]string),
		},
		Spec: appsv1.DeploymentSpec{
			Replicas: service.Spec.Replicas,
			Selector: &metav1.LabelSelector{
				MatchLabels: map[string]string{
					CloudstateStatefulServiceLabel: service.Name,
				},
			},
			Template: corev1.PodTemplateSpec{
				ObjectMeta: metav1.ObjectMeta{
					Labels: map[string]string{
						CloudstateStatefulServiceLabel: service.Name,
					},
					Annotations: map[string]string{
						CloudstateEnabledAnnotation:               "true",
						CloudstateStatefulServiceConfigAnnotation: "ss-cfg-" + service.Name,
					},
				},
				Spec: corev1.PodSpec{
					Containers: containers,
				},
			},
		},
	}

	if service.Spec.StoreConfig != nil && service.Spec.StoreConfig.StatefulStore.Name != "" {
		desired.Spec.Template.Annotations[stores.CloudstateStatefulStoreAnnotation] = service.Spec.StoreConfig.StatefulStore.Name
	}
	reconciliation.SetCommonLabels(service.Name, desired.Labels)
	if err := ctrl.SetControllerReference(service, desired, r.Scheme); err != nil {
		return nil, fmt.Errorf("unable to set owner: %w", err)
	}
	if service.Spec.Replicas == nil {
		desired.Spec.Replicas = &defaultReplicas
	}
	// Create pod template for deployment.
	reconciliation.SetCommonLabels(service.Name, desired.Spec.Template.Labels)
	return desired, nil
}

// setLastApplied sets the last applied annotation to the value of the desired object.
func setLastApplied(desired runtime.Object) {
	if err := patch.DefaultAnnotator.SetLastAppliedAnnotation(desired); err != nil {
		panic(err) // TODO(review comment): is this needed, if yes explain why.
	}
}

// needsUpdate returns true if the actual object state has deviated from desired, indicating
// the actual should be updated.  It will return false if the object is known to be in the
// process of being deleted.
func needsUpdate(log logr.Logger, actual, desired runtime.Object) bool {
	if !lastAppliedConfigExists(desired) {
		// Precondition is that the lastApplied annotation is set on desired.
		panic("lastApplied was not set on desired") // TODO(review comment): is this needed, if yes explain why.
	}
	if deletionTimestampExists(actual) {
		return false
	}
	if !lastAppliedConfigExists(actual) {
		// No lastApplied annotation on actual state - update is needed.
		return true
	}
	patchResult, err := patch.DefaultPatchMaker.Calculate(actual, desired)
	if err != nil {
		panic(err) // TODO(review comment): is this needed, if yes explain why.
	}
	if !patchResult.IsEmpty() {
		log.V(1).Info("changed", "patchResult", patchResult.String())
		return true
	}
	return false
}

func lastAppliedConfigExists(obj runtime.Object) bool {
	anno, err := meta.NewAccessor().Annotations(obj)
	if err != nil {
		panic(err) // TODO(review comment): is this needed, if yes explain why.
	}
	_, exists := anno[patch.LastAppliedConfig]
	return exists
}

func deletionTimestampExists(obj runtime.Object) bool {
	a, err := meta.Accessor(obj)
	if err != nil {
		panic(err) // TODO(review comment): is this needed, if yes explain why.
	}
	return !a.GetDeletionTimestamp().IsZero()
}

func (r *StatefulServiceReconciler) reconcileService(ctx context.Context, statefulService *cloudstate.StatefulService) (*corev1.Service, error) {
	obj, err := reconciliation.GetControlledStructured(ctx, r, statefulService, &corev1.ServiceList{})
	if err != nil {
		return nil, err
	}
	var actual *corev1.Service
	if obj != nil {
		actual = obj.(*corev1.Service)
	}

	desired := &corev1.Service{
		ObjectMeta: metav1.ObjectMeta{
			Name:      statefulService.Name,
			Namespace: statefulService.Namespace,
		},
		Spec: corev1.ServiceSpec{
			Type: corev1.ServiceTypeClusterIP,
			Selector: map[string]string{
				CloudstateStatefulServiceLabel: statefulService.Name,
			},
			Ports: []corev1.ServicePort{
				{
					Name:       "http2",
					Protocol:   "TCP",
					Port:       80,
					TargetPort: intstr.FromInt(8013),
				},
				{
					Name:       "metrics",
					Protocol:   "TCP",
					Port:       9090,
					TargetPort: intstr.FromInt(9090),
				},
			},
		},
	}
	if err := ctrl.SetControllerReference(statefulService, desired, r.Scheme); err != nil {
		return nil, err
	}
	if actual == nil {
		return desired, r.Create(ctx, desired)
	}
	if actual.Name == desired.Name {
		return desired, nil
	}
	return desired, r.Update(ctx, desired)
}

func (r *StatefulServiceReconciler) reconcileHpa(ctx context.Context, service *cloudstate.StatefulService, config *config.StatefulServiceConfig) error {
	obj, err := reconciliation.GetControlledStructured(ctx, r, service, &autoscalingv2beta2.HorizontalPodAutoscalerList{})
	if err != nil {
		return err
	}
	if !config.Autoscaler.Enabled {
		if obj != nil {
			return r.Delete(ctx, obj)
		}
		return nil
	}
	var actual *autoscalingv2beta2.HorizontalPodAutoscaler
	if obj != nil {
		actual = obj.(*autoscalingv2beta2.HorizontalPodAutoscaler)
	}
	desired := &autoscalingv2beta2.HorizontalPodAutoscaler{
		ObjectMeta: metav1.ObjectMeta{
			Name:      service.Name,
			Namespace: service.Namespace,
		},
		Spec: autoscalingv2beta2.HorizontalPodAutoscalerSpec{
			ScaleTargetRef: autoscalingv2beta2.CrossVersionObjectReference{
				APIVersion: service.APIVersion,
				Kind:       service.Kind,
				Name:       service.Name,
			},
			MinReplicas: &config.Autoscaler.MinReplicas,
			MaxReplicas: config.Autoscaler.MaxReplicas,
			Metrics: []autoscalingv2beta2.MetricSpec{
				{
					Type: autoscalingv2beta2.ResourceMetricSourceType,
					Resource: &autoscalingv2beta2.ResourceMetricSource{
						Name: corev1.ResourceCPU,
						Target: autoscalingv2beta2.MetricTarget{
							Type:               autoscalingv2beta2.UtilizationMetricType,
							AverageUtilization: &config.Autoscaler.CPUUtilizationThreshold,
						},
					},
				},
			},
		},
	}
	if err := ctrl.SetControllerReference(service, desired, r.Scheme); err != nil {
		return err
	}
	setLastApplied(desired)
	if actual == nil {
		return r.Create(ctx, desired)
	}
	if !needsUpdate(r.Log.WithValues("type", "HorizontalPodAutoscaler"), actual, desired) {
		return nil
	}
	return r.Update(ctx, desired)
}

// checkStatefulService modifies the StatefulService if need be.
// For now, this is a hack to write an explicit spec.replicas value.
func (r *StatefulServiceReconciler) checkStatefulService(ctx context.Context, statefulService *cloudstate.StatefulService) error {
	if statefulService.Spec.Replicas == nil {
		statefulService.Spec.Replicas = &defaultReplicas
		if err := r.Update(ctx, statefulService); err != nil {
			return err
		}
	}
	return nil
}

func (r *StatefulServiceReconciler) reconcileRoleAndBinding(ctx context.Context, log logr.Logger, namespace string) error {
	err := r.Client.Get(ctx, client.ObjectKey{
		Name:      "cloudstate-pod-reader",
		Namespace: namespace,
	}, &rbacv1.Role{})
	if err != nil && apierrs.IsNotFound(err) {
		desiredRole := &rbacv1.Role{
			ObjectMeta: metav1.ObjectMeta{
				Name:      "cloudstate-pod-reader",
				Namespace: namespace,
			},
			Rules: []rbacv1.PolicyRule{
				{
					APIGroups: []string{""},
					Resources: []string{"pods"},
					Verbs:     []string{"get", "watch", "list"},
				},
			},
		}
		if err := r.Client.Create(ctx, desiredRole); err != nil {
			return err
		}
	} else if err != nil {
		return err
	}

	err = r.Client.Get(ctx, client.ObjectKey{
		Name:      "cloudstate-pod-reader-binding",
		Namespace: namespace,
	}, &rbacv1.RoleBinding{})
	if err != nil && apierrs.IsNotFound(err) {
		desiredBinding := &rbacv1.RoleBinding{
			ObjectMeta: metav1.ObjectMeta{
				Name:      "cloudstate-pod-reader-binding",
				Namespace: namespace,
			},
			RoleRef: rbacv1.RoleRef{
				APIGroup: "rbac.authorization.k8s.io",
				Kind:     "Role",
				Name:     "cloudstate-pod-reader",
			},
			Subjects: []rbacv1.Subject{
				{
					Kind: "ServiceAccount",
					Name: "default",
				},
			},
		}
		if err := r.Client.Create(ctx, desiredBinding); err != nil {
			return err
		}
	} else if err != nil {
		return err
	}

	return nil
}

func (r *StatefulServiceReconciler) updateStatus(ctx context.Context, log logr.Logger, service *cloudstate.StatefulService, deployment *appsv1.Deployment, conditions []cloudstate.CloudstateCondition) error {
	origStatus := service.Status.DeepCopy()
	service.Status.Selector = CloudstateStatefulServiceLabel + "=" + service.Name
	condition := cloudstate.CloudstateCondition{
		Type: cloudstate.CloudstateNotReady,
	}

	if deployment == nil {
		condition.Status = corev1.ConditionTrue
		condition.Reason = "Unavailable"
		condition.Message = "Application is not running yet"
		service.Status.Replicas = 0
	} else {
		log.V(1).Info("Inspecting status",
			"spec.replicas", *deployment.Spec.Replicas,
			"status.updatedReplicas", deployment.Status.UpdatedReplicas,
			"status.availableReplicas", deployment.Status.AvailableReplicas)

		// exactly the desired number of replicas running?
		desiredNumberOfReplicas := *deployment.Spec.Replicas == deployment.Status.Replicas
		// replicas are all up to date?
		allReplicasUpToDate := deployment.Status.Replicas == deployment.Status.UpdatedReplicas
		// replicas are all available?
		allReplicasAvailable := deployment.Status.Replicas == deployment.Status.AvailableReplicas
		if desiredNumberOfReplicas && allReplicasUpToDate && allReplicasAvailable {
			condition.Status = corev1.ConditionFalse
			condition.Reason = "Ready"
			condition.Message = "All replicas are up to date and fully available"
		} else if !desiredNumberOfReplicas || !allReplicasUpToDate {
			condition.Status = corev1.ConditionTrue
			condition.Reason = "UpdateInProgress"
			condition.Message = "Application is in the process of updating"
		} else if !allReplicasAvailable {
			condition.Status = corev1.ConditionTrue
			if deployment.Status.AvailableReplicas == 0 {
				condition.Reason = "Unavailable"
				condition.Message = "All replicas are unavailable"
			} else {
				condition.Reason = "PartiallyReady"
				condition.Message = "Some replicas are unavailable"
			}
		}

		service.Status.Replicas = deployment.Status.AvailableReplicas
	}
	conditions = append(conditions, condition)

	// The summary is the reason for the first true (ie, failing) condition, or Ready otherwise.
	service.Status.Summary = "Ready"
	for _, cond := range conditions {
		if cond.Status == corev1.ConditionTrue {
			service.Status.Summary = cond.Reason
			break
		}
	}
	updated := reconciliation.ReconcileConditions(service.Status.Conditions, conditions)
	if len(updated) == 0 &&
		origStatus != nil &&
		origStatus.Replicas == service.Status.Replicas &&
		origStatus.Summary == service.Status.Summary &&
		origStatus.Selector == service.Status.Selector {
		// do nothing - no changes
		log.V(1).Info("No status update")
		return nil
	}

	// update status
	if len(updated) > 0 {
		service.Status.Conditions = updated
	}
	if err := r.Status().Update(ctx, service); err != nil {
		return err
	}
	log.Info("Updated status",
		"replicas", service.Status.Replicas,
		"summary", service.Status.Summary)
	return nil
}

func ignoreNotFound(err error) error {
	if apierrs.IsNotFound(err) {
		return nil
	}
	return err
}

func (r *StatefulServiceReconciler) SetupWithManager(mgr ctrl.Manager) error {
	if r.ReconcileTimeout == 0 {
		return errors.New("reconcile timeout should be greater than 0")
	}

	builder := ctrl.NewControllerManagedBy(mgr).
		For(&cloudstate.StatefulService{}).
		Owns(&appsv1.Deployment{}).
		Owns(&corev1.Service{}).
		Owns(&corev1.Secret{}).
		Owns(&corev1.ConfigMap{}).
		Owns(&autoscalingv2beta2.HorizontalPodAutoscaler{})
	if err := r.Stores.SetupWithStatefulServiceController(builder); err != nil {
		return err
	}
	return builder.Complete(r)
}
