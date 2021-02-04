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

import akka.testkit.EventFilter
import io.prometheus.client.CollectorRegistry

class CloudstateTelemetrySpec extends AbstractTelemetrySpec {

  "CloudstateTelemetry" should {

    "be enabled in production mode by default" in withTestRegistry() { testKit =>
      CloudstateTelemetry(testKit.system).settings.enabled shouldBe true
    }

    "be disabled in development mode by default" in withTestKit("""
      | cloudstate.proxy.dev-mode-enabled = true
      """) { testKit =>
      CloudstateTelemetry(testKit.system).settings.disabled shouldBe true
    }

    "allow telemetry to be enabled in development mode" in withTestRegistry(
      """
      | cloudstate.proxy.dev-mode-enabled = true
      | cloudstate.proxy.telemetry.disabled = false
      """
    ) { testKit =>
      CloudstateTelemetry(testKit.system).settings.enabled shouldBe true
    }

    "use Prometheus default registry by default" in withTestKit(
      """
      | # disable telemetry so we don't register any metrics with the global default registry
      | cloudstate.proxy.telemetry.disabled = true
      """
    ) { testKit =>
      CloudstateTelemetry(testKit.system).prometheusRegistry should be theSameInstanceAs CollectorRegistry.defaultRegistry
    }

    "create separate Prometheus registry if configured" in withTestKit(
      """
      | cloudstate.proxy.telemetry.prometheus.use-default-registry = false
      """
    ) { testKit =>
      CloudstateTelemetry(testKit.system).prometheusRegistry shouldNot be theSameInstanceAs CollectorRegistry.defaultRegistry
    }

    "start Prometheus exporter on default port (9090)" in withTestRegistry(
      """
      | akka.loggers = ["akka.testkit.TestEventListener"]
      """
    ) { testKit =>
      import testKit.system
      EventFilter.info(start = "Prometheus exporter started", occurrences = 1).intercept {
        CloudstateTelemetry(system).start()
      }
      val metrics = scrape("http://localhost:9090")(_.mkString)
      metrics should (include("# TYPE cloudstate_eventsourced") and include("# TYPE cloudstate_valuebased"))
    }

    "allow Prometheus metrics port to be configured" in withTestRegistry(
      """
      | cloudstate.proxy.telemetry.prometheus.port = 9999
      | akka.loggers = ["akka.testkit.TestEventListener"]
      """
    ) { testKit =>
      import testKit.system
      EventFilter.info(start = "Prometheus exporter started", occurrences = 1).intercept {
        CloudstateTelemetry(system).start()
      }
      val metrics = scrape("http://localhost:9999")(_.mkString)
      metrics should (include("# TYPE cloudstate_eventsourced") and include("# TYPE cloudstate_valuebased"))
    }

    "bind event-sourced instrumentation to Prometheus by default" in withTestRegistry() { testKit =>
      CloudstateTelemetry(testKit.system).eventSourcedInstrumentation shouldBe a[PrometheusEventSourcedInstrumentation]
    }

    "use noop event-sourced instrumentation when disabled" in withTestKit("""
      | cloudstate.proxy.telemetry.disabled = true
      """) { testKit =>
      CloudstateTelemetry(testKit.system).eventSourcedInstrumentation should be theSameInstanceAs NoEventSourcedInstrumentation
    }

    "bind active event-sourced entity instrumentation by default" in withTestRegistry() { testKit =>
      CloudstateTelemetry(testKit.system)
        .eventSourcedEntityInstrumentation("name") shouldBe an[ActiveEventSourcedEntityInstrumentation]
    }

    "use noop event-sourced entity instrumentation when disabled" in withTestKit(
      """
      | cloudstate.proxy.telemetry.disabled = true
      """
    ) { testKit =>
      CloudstateTelemetry(testKit.system)
        .eventSourcedEntityInstrumentation("name") should be theSameInstanceAs NoEventSourcedEntityInstrumentation
    }

    "bind value-based instrumentation to Prometheus by default" in withTestRegistry() { testKit =>
      CloudstateTelemetry(testKit.system).valueBasedInstrumentation shouldBe a[PrometheusEntityInstrumentation]
    }

    "use noop value-based instrumentation when disabled" in withTestKit("""
      | cloudstate.proxy.telemetry.disabled = true
      """) { testKit =>
      CloudstateTelemetry(testKit.system).valueBasedInstrumentation should be theSameInstanceAs NoEntityInstrumentation
    }

    "bind active value-based entity instrumentation by default" in withTestRegistry() { testKit =>
      CloudstateTelemetry(testKit.system)
        .valueBasedInstrumentation("name") shouldBe an[ActiveValueEntityInstrumentation]
    }

    "use noop value-based entity instrumentation when disabled" in withTestKit(
      """
      | cloudstate.proxy.telemetry.disabled = true
      """
    ) { testKit =>
      CloudstateTelemetry(testKit.system)
        .valueBasedInstrumentation("name") should be theSameInstanceAs NoValueEntityInstrumentation
    }
  }
}
