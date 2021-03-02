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

import java.util.*;

/**
 * Convenience wrapper class for {@link ORMap} that uses {@link PNCounter}'s for values.
 *
 * <p>This offers a few extra methods for interacting with the map.
 *
 * @param <K> The type for keys.
 */
public final class PNCounterMap<K> extends AbstractORMapWrapper<K, Long, PNCounter>
    implements Map<K, Long> {

  public PNCounterMap(ORMap<K, PNCounter> ormap) {
    super(ormap);
  }

  /**
   * Get the value for the given key.
   *
   * <p>This differs from {@link Map#get(Object)} in that it returns a primitive <code>long</code>,
   * and thus avoids an allocation.
   *
   * @param key The key to get the value for.
   * @return The current value of the counter at that key, or zero if no counter exists for that
   *     key.
   */
  public long getValue(Object key) {
    PNCounter counter = ormap.get(key);
    if (counter != null) {
      return counter.getValue();
    } else {
      return 0;
    }
  }

  /**
   * Increment the counter at the given key by the given amount.
   *
   * <p>The counter will be created if it is not already in the map.
   *
   * @param key The key of the counter.
   * @param by The amount to increment by.
   * @return The new value of the counter.
   */
  public long increment(Object key, long by) {
    return getOrUpdate(key).increment(by);
  }

  /**
   * Decrement the counter at the given key by the given amount.
   *
   * <p>The counter will be created if it is not already in the map.
   *
   * @param key The key of the counter.
   * @param by The amount to decrement by.
   * @return The new value of the counter.
   */
  public long decrement(Object key, long by) {
    return getOrUpdate(key).decrement(by);
  }

  /** Not supported on PNCounter, use increment/decrement instead. */
  @Override
  public Long put(K key, Long value) {
    throw new UnsupportedOperationException(
        "Put is not supported on PNCounterMap, use increment or decrement instead");
  }

  @Override
  Long getCrdtValue(PNCounter pnCounter) {
    return pnCounter.getValue();
  }

  @Override
  void setCrdtValue(PNCounter pnCounter, Long value) {
    throw new UnsupportedOperationException(
        "Using value mutating methods on PNCounterMap is not supported, use increment or decrement instead");
  }

  @Override
  PNCounter getOrUpdateCrdt(K key, Long value) {
    return ormap.getOrCreate(key, CrdtFactory::newPNCounter);
  }

  private PNCounter getOrUpdate(Object key) {
    return ormap.getOrCreate((K) key, CrdtFactory::newPNCounter);
  }
}
