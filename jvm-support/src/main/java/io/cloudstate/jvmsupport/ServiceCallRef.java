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

package io.cloudstate.jvmsupport;

import com.google.protobuf.Descriptors;

/**
 * A reference to a call on a service.
 *
 * @param <T> The type of message the call accepts.
 */
public interface ServiceCallRef<T> {
  /**
   * The protobuf descriptor for the method.
   *
   * @return The protobuf descriptor for the method.
   */
  Descriptors.MethodDescriptor method();

  /**
   * Create a call from this reference, using the given message as the message to pass to it when
   * it's invoked.
   *
   * @param message The message to pass to the method.
   * @return A service call that can be used as a forward or effect.
   */
  ServiceCall createCall(T message);
}
