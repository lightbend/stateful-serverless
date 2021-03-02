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

/** A context that allows instructing the proxy to perform a side effect. */
public interface EffectContext extends Context {

  /**
   * Invoke the referenced service call as an effect once this action is completed.
   *
   * <p>The effect will be performed asynchronously, ie, the proxy won't wait for the effect to
   * finish before sending the reply.
   *
   * <p>{@link ServiceCall} instances can be created using the {@link ServiceCallFactory} obtained
   * from any (including this) contexts {@link Context#serviceCallFactory()} method.
   *
   * @param effect The service call to make as an effect effect.
   */
  default void effect(ServiceCall effect) {
    this.effect(effect, false);
  }

  /**
   * Invoke the referenced service call as an effect once this action is completed.
   *
   * <p>{@link ServiceCall} instances can be created using the {@link ServiceCallFactory} obtained
   * from any (including this) contexts {@link Context#serviceCallFactory()} method.
   *
   * @param effect The service call to make as an effect effect.
   * @param synchronous Whether the effect should be performed synchronously (ie, wait till it has
   *     finished before sending a reply) or asynchronously.
   */
  void effect(ServiceCall effect, boolean synchronous);
}
