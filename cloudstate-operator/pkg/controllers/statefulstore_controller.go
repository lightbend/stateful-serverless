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
	"time"

	"github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/config"
	"github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/reconciliation"
	"github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/stores"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/runtime"

	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"

	"github.com/go-logr/logr"

	cloudstate "github.com/cloudstateio/cloudstate/cloudstate-operator/pkg/apis/v1alpha1"
)

type StatefulStoreConfig struct {
	// This is the DB type and version as used by GCP. Must start with "POSTGRES_" to ensure we get a Postgres DB.
	DBTypeVersion         string `yaml:"dbTypeVersion" env-description:"DB descriptor for GCP"`
	DBRegion              string `yaml:"dbRegion" env-description:"GCP region for instances/databases"`
	PostgresDefaultCores  int32  `yaml:"postgresDefaultCores" env-description:"default number of cores for Postgres SQLInstances"`
	PostgresDefaultMemory string `yaml:"postgresDefaultMemory" env-description:"default memory allocation (MiB) for Postgres SQLInstances"`
}

// SetDefaults initializes the structure with default values.
func (cfg *StatefulStoreConfig) SetDefaults() {
	cfg.DBTypeVersion = "POSTGRES_11"
	cfg.DBRegion = "us-east1"
	cfg.PostgresDefaultCores = 1
	cfg.PostgresDefaultMemory = "3840"
}

// StatefulStoreReconciler reconciles a StatefulStore object.
type StatefulStoreReconciler struct {
	client.Client
	Log              logr.Logger
	Scheme           *runtime.Scheme
	ReconcileTimeout time.Duration
	OperatorConfig   *config.OperatorConfig
	Stores           stores.Stores
}

// +kubebuilder:rbac:groups=cloudstate.io,resources=statefulstores,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=cloudstate.io,resources=statefulstores/status,verbs=get;update;patch
// +kubebuilder:rbac:groups=sql.cnrm.cloud.google.com,resources=sqlinstances,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=core,resources=secrets,verbs=get;list;watch;create;update;patch;delete

// Reconcile StatefulStore.
func (r *StatefulStoreReconciler) Reconcile(req ctrl.Request) (ctrl.Result, error) {
	ctx, cancelFunc := context.WithTimeout(context.Background(), r.ReconcileTimeout)
	defer cancelFunc()
	log := r.Log.WithValues("statefulstore", req.NamespacedName)

	// Get the stateful store to reconcile.
	var store cloudstate.StatefulStore
	if err := r.Get(ctx, req.NamespacedName, &store); err != nil {
		log.Error(err, "unable to fetch StatefulStore")
		return ctrl.Result{}, ignoreNotFound(err)
	}
	conditions, updated, err := r.Stores.ReconcileStatefulStore(ctx, &store)
	if err != nil {
		return ctrl.Result{}, err
	}
	if len(conditions) == 0 {
		conditions = []cloudstate.CloudstateCondition{
			{
				Type:   cloudstate.CloudstateNotReady,
				Status: corev1.ConditionFalse,
				Reason: "Ready",
			},
		}
	}

	summary := "Ready"
	for i := range conditions {
		if conditions[i].Status != corev1.ConditionFalse {
			summary = conditions[i].Reason
			break
		}
	}
	if store.Status.Summary != summary {
		updated = true
		store.Status.Summary = summary
	}
	updatedConditions := reconciliation.ReconcileConditions(store.Status.Conditions, conditions)
	if len(updatedConditions) > 0 || updated {
		if len(updatedConditions) > 0 {
			store.Status.Conditions = updatedConditions
		}
		if err := r.Status().Update(ctx, &store); err != nil {
			return ctrl.Result{}, err
		}
	}
	return ctrl.Result{}, nil
}

func (r *StatefulStoreReconciler) SetupWithManager(mgr ctrl.Manager) error {
	builder := ctrl.NewControllerManagedBy(mgr).For(&cloudstate.StatefulStore{})
	if err := r.Stores.SetupWithStatefulStoreController(builder); err != nil {
		return err
	}
	return builder.Complete(r)
}
