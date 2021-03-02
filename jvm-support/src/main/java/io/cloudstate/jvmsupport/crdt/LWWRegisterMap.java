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
 * Convenience wrapper class for {@link ORMap} that uses {@link LWWRegister}'s for values.
 *
 * <p>This is useful as it allows the map to be used more idiomatically, with plain {@link
 * Map#get(Object)} and {@link Map#put(Object, Object)} calls for values.
 *
 * @param <K> The type for keys.
 * @param <V> The type for values.
 */
public final class LWWRegisterMap<K, V> extends AbstractORMapWrapper<K, V, LWWRegister<V>>
    implements Map<K, V> {

  public LWWRegisterMap(ORMap<K, LWWRegister<V>> ormap) {
    super(ormap);
  }

  @Override
  V getCrdtValue(LWWRegister<V> crdt) {
    return crdt.get();
  }

  @Override
  void setCrdtValue(LWWRegister<V> crdt, V value) {
    crdt.set(value);
  }

  @Override
  LWWRegister<V> getOrUpdateCrdt(K key, V value) {
    return ormap.getOrCreate(key, f -> f.newLWWRegister(value));
  }
}
