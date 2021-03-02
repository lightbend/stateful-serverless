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

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.util.ByteString
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.common.TextFormat
import java.io.OutputStreamWriter
import scala.util.{Failure, Success}

/**
 * Serves Prometheus metrics using Akka HTTP
 */
class PrometheusExporter(registry: CollectorRegistry, metricsHost: String, metricsPort: Int)(
    implicit system: ActorSystem
) {

  private[this] val PrometheusContentType = ContentType.parse(TextFormat.CONTENT_TYPE_004).right.get

  private def routes = get {
    (path("metrics") | pathSingleSlash) {
      encodeResponse {
        parameter(Symbol("name[]").*) { names =>
          complete {
            val namesSet = new java.util.HashSet[String]()
            names.foreach(namesSet.add)
            val builder = ByteString.newBuilder
            val writer = new OutputStreamWriter(builder.asOutputStream)
            TextFormat.write004(writer, registry.filteredMetricFamilySamples(namesSet))
            // Very important to flush the writer before we build the byte string!
            writer.flush()
            HttpEntity(PrometheusContentType, builder.result())
          }
        }
      }
    }
  }

  def start(): Unit = {
    import system.dispatcher
    Http().newServerAt(metricsHost, metricsPort).bind(routes).onComplete {
      case Success(binding) =>
        system.log.info("Prometheus exporter started on {}", binding.localAddress)
      case Failure(error) =>
        system.log.error(error, "Error starting Prometheus exporter!")
        system.terminate()
    }
  }
}
