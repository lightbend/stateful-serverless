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

import akka.actor.{ActorSystem, ExtendedActorSystem, Extension, ExtensionId}
import com.typesafe.config.Config
import io.prometheus.client.CollectorRegistry

object CloudstateTelemetry extends ExtensionId[CloudstateTelemetry] {

  final case class Settings(
      disabled: Boolean,
      prometheusHost: String,
      prometheusPort: Int,
      prometheusUseDefaultRegistry: Boolean
  ) {
    def enabled: Boolean = !disabled
  }

  object Settings {
    def apply(rootConfig: Config): Settings = {
      val config = rootConfig.getConfig("cloudstate.proxy.telemetry")
      Settings(
        disabled = config.getBoolean("disabled"),
        prometheusHost = config.getString("prometheus.host"),
        prometheusPort = config.getInt("prometheus.port"),
        prometheusUseDefaultRegistry = config.getBoolean("prometheus.use-default-registry")
      )
    }
  }

  def createExtension(system: ExtendedActorSystem): CloudstateTelemetry = new CloudstateTelemetry(system)
}

final class CloudstateTelemetry(system: ActorSystem) extends Extension {
  val settings: CloudstateTelemetry.Settings = CloudstateTelemetry.Settings(system.settings.config)

  val prometheusRegistry: CollectorRegistry =
    if (settings.prometheusUseDefaultRegistry) CollectorRegistry.defaultRegistry else new CollectorRegistry

  val eventSourcedInstrumentation: EventSourcedInstrumentation =
    if (settings.enabled) new PrometheusEventSourcedInstrumentation(prometheusRegistry)
    else NoEventSourcedInstrumentation

  val valueBasedInstrumentation: EntityInstrumentation =
    if (settings.enabled) new PrometheusEntityInstrumentation(prometheusRegistry)
    else NoEntityInstrumentation

  def eventSourcedEntityInstrumentation(entityName: String): EventSourcedEntityInstrumentation =
    if (settings.enabled) new ActiveEventSourcedEntityInstrumentation(entityName, eventSourcedInstrumentation)
    else NoEventSourcedEntityInstrumentation

  def valueBasedInstrumentation(entityName: String): ValueEntityInstrumentation =
    if (settings.enabled) new ActiveValueEntityInstrumentation(entityName, valueBasedInstrumentation)
    else NoValueEntityInstrumentation

  def start(): Unit =
    if (settings.enabled) {
      new PrometheusExporter(prometheusRegistry, settings.prometheusHost, settings.prometheusPort)(system).start()
    }
}
