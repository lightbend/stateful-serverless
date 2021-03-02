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

package io.cloudstate.jvmsupport.impl

import io.cloudstate.jvmsupport.{ServiceCallFactory, ServiceCallRef, StatefulService}

class ResolvedServiceCallFactory(services: Map[String, StatefulService]) extends ServiceCallFactory {
  override def lookup[T](serviceName: String, methodName: String, methodType: Class[T]): ServiceCallRef[T] =
    services.get(serviceName) match {
      case Some(service) =>
        service.resolvedMethods match {
          case Some(resolvedMethods) =>
            resolvedMethods.get(methodName) match {
              case Some(method) if method.inputType.typeClass.isAssignableFrom(methodType) =>
                method.asInstanceOf[ServiceCallRef[T]]
              case Some(badTypedMethod) =>
                throw new IllegalArgumentException(
                  s"The input type ${badTypedMethod.inputType.typeClass.getName} of $serviceName.$methodName does not match the requested message type ${methodType.getName}"
                )
              case None =>
                throw new NoSuchElementException(s"No method named $methodName found on service $serviceName")
            }
          case None =>
            throw new IllegalStateException(
              s"Service $serviceName does not provide resolved methods and so can't be looked up by this factory"
            )
        }
      case _ =>
        throw new NoSuchElementException(s"No service named $serviceName is being handled by this stateful service")
    }
}
