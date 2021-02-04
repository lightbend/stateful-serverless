/*
 * Copyright 2019 Lightbend Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.cloudstate.proxy.telemetry

import io.prometheus.client.{CollectorRegistry, Counter, Histogram, Summary}

object PrometheusEntityInstrumentation {
  object MetricName {
    final val ActivatedEntitiesTotal = "cloudstate_valuebased_activated_entities_total"
    final val PassivatedEntitiesTotal = "cloudstate_valuebased_passivated_entities_total"
    final val EntityActiveTimeSeconds = "cloudstate_valuebased_entity_active_time_seconds"
    final val FailedEntitiesTotal = "cloudstate_valuebased_failed_entities_total"
    final val RecoveryTimeSeconds = "cloudstate_valuebased_recovery_time_seconds"
    final val RecoveryFailedTotal = "cloudstate_valuebased_recovery_failed_total"
    final val ReceivedCommandsTotal = "cloudstate_valuebased_received_commands_total"
    final val StashedCommandsTotal = "cloudstate_valuebased_stashed_commands_total"
    final val UnstashedCommandsTotal = "cloudstate_valuebased_unstashed_commands_total"
    final val CommandStashTimeSeconds = "cloudstate_valuebased_command_stash_time_seconds"
    final val CommandProcessingTimeSeconds = "cloudstate_valuebased_command_processing_time_seconds"
    final val FailedCommandsTotal = "cloudstate_valuebased_failed_commands_total"
    final val CompletedCommandsTotal = "cloudstate_valuebased_completed_commands_total"
    final val CommandTotalTimeSeconds = "cloudstate_valuebased_command_total_time_seconds"
    final val PersistTimeSeconds = "cloudstate_valuebased_persist_time_seconds"
    final val PersistFailedTotal = "cloudstate_valuebased_persist_failed_total"
    final val PersistedStatesTotal = "cloudstate_valuebased_persisted_states_total"
    final val PersistedStateBytesTotal = "cloudstate_valuebased_persisted_state_bytes_total"
    final val LoadedStatesTotal = "cloudstate_valuebased_loaded_state_total"
    final val LoadedStateBytesTotal = "cloudstate_valuebased_loaded_state_bytes_total"
    final val DeleteTimeSeconds = "cloudstate_valuebased_delete_time_seconds"
    final val DeleteFailedTotal = "cloudstate_valuebased_delete_failed_total"
  }

  object MetricLabel {
    final val EntityName = "entity_name"
  }

  final val NanosecondsPerSecond = 1e9
}

class PrometheusEntityInstrumentation(registry: CollectorRegistry) extends EntityInstrumentation {
  import EntityInstrumentation._
  import PrometheusEntityInstrumentation._

  // Notes:
  // Using Histograms for command timing metrics, with the default buckets (from 5ms up to 10s).
  // Timing metrics are in seconds, using the Prometheus convention of base units.
  // Not using Summary for command timing metrics, as it currently has terrible performance.
  // HDRHistogram-backed Summary still coming: https://github.com/prometheus/client_java/pull/484

  private val activatedEntitiesTotal: Counter = Counter.build
    .name(MetricName.ActivatedEntitiesTotal)
    .help("Total entity instances that have activated.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val passivatedEntitiesTotal: Counter = Counter.build
    .name(MetricName.PassivatedEntitiesTotal)
    .help("Total entity instances that have passivated.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val entityActiveTimeSeconds: Summary = Summary.build
    .name(MetricName.EntityActiveTimeSeconds)
    .help("Duration that entities are active, in seconds.")
    .labelNames(MetricLabel.EntityName)
    .quantile(0.50, 0.050) // 50th percentile (median), 5% error
    .quantile(0.95, 0.010) // 95th percentile, 1% error
    .quantile(0.99, 0.001) // 99th percentile, 0.1% error
    .quantile(1.00, 0.000) // 100th percentile (max), 0% error
    .maxAgeSeconds(60)
    .ageBuckets(6)
    .register(registry)

  private val failedEntitiesTotal: Counter = Counter.build
    .name(MetricName.FailedEntitiesTotal)
    .help("Total entity instances that have failed.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val recoveryTimeSeconds: Histogram = Histogram.build
    .name(MetricName.RecoveryTimeSeconds)
    .help("Duration for entity recovery, in seconds.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val recoveryFailedTotal: Counter = Counter.build
    .name(MetricName.RecoveryFailedTotal)
    .help("Total recovery process that have failed.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val receivedCommandsTotal: Counter = Counter.build
    .name(MetricName.ReceivedCommandsTotal)
    .help("Total commands received for an entity.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val stashedCommandsTotal: Counter = Counter.build
    .name(MetricName.StashedCommandsTotal)
    .help("Total commands stashed for an entity.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val unstashedCommandsTotal: Counter = Counter.build
    .name(MetricName.UnstashedCommandsTotal)
    .help("Total commands unstashed for an entity.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val commandStashTimeSeconds: Histogram = Histogram.build
    .name(MetricName.CommandStashTimeSeconds)
    .help("Duration that commands are stashed, in seconds.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val commandProcessingTimeSeconds: Histogram = Histogram.build
    .name(MetricName.CommandProcessingTimeSeconds)
    .help("Duration for command processing (until user function reply), in seconds.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val failedCommandsTotal: Counter = Counter.build
    .name(MetricName.FailedCommandsTotal)
    .help("Total commands that have failed.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val completedCommandsTotal: Counter = Counter.build
    .name(MetricName.CompletedCommandsTotal)
    .help("Total commands completed for an entity.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val commandTotalTimeSeconds: Histogram = Histogram.build
    .name(MetricName.CommandTotalTimeSeconds)
    .help("Total duration for command handling (including stash and persist), in seconds.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val persistTimeSeconds: Histogram = Histogram.build
    .name(MetricName.PersistTimeSeconds)
    .help("Duration for persisting state from a command, in seconds.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val persistFailedTotal: Counter = Counter.build
    .name(MetricName.PersistFailedTotal)
    .help("Total persist operations that have failed.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val persistedStatesTotal: Counter = Counter.build
    .name(MetricName.PersistedStatesTotal)
    .help("Total states persisted for an entity.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val persistedStateBytesTotal: Counter = Counter.build
    .name(MetricName.PersistedStateBytesTotal)
    .help("Total state sizes persisted for an entity.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val loadedStatesTotal: Counter = Counter.build
    .name(MetricName.LoadedStatesTotal)
    .help("Total states loaded for an entity.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val loadedStateBytesTotal: Counter = Counter.build
    .name(MetricName.LoadedStateBytesTotal)
    .help("Total states sizes loaded for an entity.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val deleteTimeSeconds: Histogram = Histogram.build
    .name(MetricName.DeleteTimeSeconds)
    .help("Duration for deleting state from a command, in seconds.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private val deleteFailedTotal: Counter = Counter.build
    .name(MetricName.DeleteFailedTotal)
    .help("Total delete operations that have failed.")
    .labelNames(MetricLabel.EntityName)
    .register(registry)

  private def now: Long = System.nanoTime()

  private def elapsedSeconds(context: Context): Double =
    (now - context.startTime) / NanosecondsPerSecond

  private def startSpan(): Context = Context(startTime = now)

  private def startSpan(entityName: String, counter: Counter): Context = {
    counter.labels(entityName).inc()
    startSpan()
  }

  private def endSpan(entityName: String, timer: Histogram, context: Context): Unit =
    timer.labels(entityName).observe(elapsedSeconds(context))

  private def endSpan(entityName: String, counter: Counter, timer: Histogram, context: Context): Unit = {
    counter.labels(entityName).inc()
    endSpan(entityName, timer, context)
  }

  private def endSpan(entityName: String, timer: Summary, context: Context): Unit =
    timer.labels(entityName).observe(elapsedSeconds(context))

  private def endSpan(entityName: String, counter: Counter, timer: Summary, context: Context): Unit = {
    counter.labels(entityName).inc()
    endSpan(entityName, timer, context)
  }

  override def entityActivated(entityName: String): Context =
    startSpan(entityName, activatedEntitiesTotal)

  override def entityPassivated(entityName: String, context: Context): Unit =
    endSpan(entityName, passivatedEntitiesTotal, entityActiveTimeSeconds, context)

  override def entityFailed(entityName: String): Unit =
    failedEntitiesTotal.labels(entityName).inc()

  override def recoveryStarted(entityName: String): Context =
    startSpan()

  override def recoveryCompleted(entityName: String, context: Context): Unit =
    endSpan(entityName, recoveryTimeSeconds, context)

  override def recoveryFailed(entityName: String): Unit =
    recoveryFailedTotal.labels(entityName).inc()

  override def commandReceived(entityName: String): Context =
    startSpan(entityName, receivedCommandsTotal)

  override def commandStashed(entityName: String, context: Context): StashContext =
    StashContext(stash = startSpan(entityName, stashedCommandsTotal), restore = context)

  override def commandUnstashed(entityName: String, context: StashContext): Unit =
    endSpan(entityName, unstashedCommandsTotal, commandStashTimeSeconds, context.stash)

  override def commandStarted(entityName: String): Context =
    startSpan()

  override def commandProcessed(entityName: String, context: Context): Unit =
    endSpan(entityName, commandProcessingTimeSeconds, context)

  override def commandFailed(entityName: String): Unit =
    failedCommandsTotal.labels(entityName).inc()

  override def commandCompleted(entityName: String, context: Context): Unit =
    endSpan(entityName, completedCommandsTotal, commandTotalTimeSeconds, context)

  override def persistStarted(entityName: String): Context =
    startSpan()

  override def persistCompleted(entityName: String, context: Context): Unit =
    endSpan(entityName, persistTimeSeconds, context)

  override def persistFailed(entityName: String): Unit =
    persistFailedTotal.labels(entityName).inc()

  override def statePersisted(entityName: String, size: Int): Unit = {
    persistedStatesTotal.labels(entityName).inc()
    persistedStateBytesTotal.labels(entityName).inc(size)
  }

  override def stateLoaded(entityName: String, size: Int): Unit = {
    loadedStatesTotal.labels(entityName).inc()
    loadedStateBytesTotal.labels(entityName).inc(size)
  }

  override def deleteStarted(entityName: String): Context =
    startSpan()

  override def deleteCompleted(entityName: String, context: Context): Unit =
    endSpan(entityName, deleteTimeSeconds, context)

  override def deleteFailed(entityName: String): Unit =
    deleteFailedTotal.labels(entityName).inc()
}
