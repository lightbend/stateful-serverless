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
 * Factory for creating CRDTs.
 *
 * <p>This is used both by CRDT contexts that allow creating CRDTs, as well as by CRDTs that allow
 * nesting other CRDTs.
 *
 * <p>CRDTs may only be created by a supplied CRDT factory, CRDTs created any other way will not be
 * known by the library and so won't have their deltas synced to and from the proxy.
 */
public interface CrdtFactory {
  /**
   * Create a new GCounter.
   *
   * @return The new GCounter.
   */
  GCounter newGCounter();

  /**
   * Create a new PNCounter.
   *
   * @return The new PNCounter.
   */
  PNCounter newPNCounter();

  /**
   * Create a new GSet.
   *
   * @return The new GSet.
   */
  <T> GSet<T> newGSet();

  /**
   * Create a new ORSet.
   *
   * @return The new ORSet.
   */
  <T> ORSet<T> newORSet();

  /**
   * Create a new Flag.
   *
   * @return The new Flag.
   */
  Flag newFlag();

  /**
   * Create a new LWWRegister.
   *
   * @return The new LWWRegister.
   */
  <T> LWWRegister<T> newLWWRegister(T value);

  /**
   * Create a new ORMap.
   *
   * @return The new ORMap.
   */
  <K, V extends Crdt> ORMap<K, V> newORMap();

  /**
   * Create a new Vote.
   *
   * @return The new Vote.
   */
  Vote newVote();
}
