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

/**
 * Low level interface for handling commands for CRDTs.
 *
 * <p>Generally, this should not be used, rather, a {@link CrdtEntity} annotated class should be
 * used.
 */
public interface CrdtEntityFactory {
  /**
   * Create a CRDT entity handler for the given context.
   *
   * <p>This will be invoked each time a new CRDT entity stream from the proxy is established, for
   * handling commands\ for a single CRDT.
   *
   * @param context The creation context.
   * @return The handler to handle commands.
   */
  CrdtEntityHandler create(CrdtCreationContext context);
}
