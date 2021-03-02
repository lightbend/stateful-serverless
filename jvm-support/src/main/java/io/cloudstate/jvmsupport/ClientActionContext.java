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

/**
 * Context that provides client actions, which include failing and forwarding.
 *
 * <p>These contexts are typically made available in response to commands.
 */
public interface ClientActionContext extends Context {
  /**
   * Fail the command with the given message.
   *
   * @param errorMessage The error message to send to the client.
   */
  RuntimeException fail(String errorMessage);

  /**
   * Instruct the proxy to forward handling of this command to another entity served by this
   * stateful function.
   *
   * <p>The command will be forwarded after successful completion of handling this command,
   * including any persistence that this command does.
   *
   * <p>{@link ServiceCall} instances can be created using the {@link ServiceCallFactory} obtained
   * from any (including this) contexts {@link Context#serviceCallFactory()} method.
   *
   * @param to The service call to forward command processing to.
   */
  void forward(ServiceCall to);
}
