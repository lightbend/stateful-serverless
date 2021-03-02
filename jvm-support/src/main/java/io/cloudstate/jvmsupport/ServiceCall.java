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

import com.google.protobuf.Any;
import com.google.protobuf.Descriptors;

/** Represents a call to a service, performed either as a forward, or as an effect. */
public interface ServiceCall {

  /**
   * The reference to the call.
   *
   * @return The reference to the call.
   */
  ServiceCallRef<?> ref();

  /**
   * The message to pass to the call when the call is invoked.
   *
   * @return The message to pass to the call, serialized as an {@link Any}.
   */
  Any message();
}
