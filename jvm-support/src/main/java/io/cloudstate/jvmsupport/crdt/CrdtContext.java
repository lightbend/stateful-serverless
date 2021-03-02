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

package io.cloudstate.jvmsupport.crdt;

import io.cloudstate.jvmsupport.EntityContext;

import java.util.Optional;

/** Root context for all CRDT contexts. */
public interface CrdtContext extends EntityContext {
  /**
   * The current CRDT, if it's been created.
   *
   * @param crdtClass The type of the CRDT that is expected.
   * @return The current CRDT, or empty if none has been created yet.
   * @throws IllegalStateException If the current CRDT does not match the passed in <code>crdtClass
   *     </code> type.
   */
  <T extends Crdt> Optional<T> state(Class<T> crdtClass) throws IllegalStateException;
}
