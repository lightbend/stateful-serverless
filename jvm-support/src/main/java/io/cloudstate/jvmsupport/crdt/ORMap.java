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

import java.util.Map;
import java.util.function.Function;

/**
 * An Observed-Removed Map.
 *
 * <p>An Observed-Removed Map allows both the addition and removal of objects in a map. A removal
 * can only be done if all of the additions that caused the key to be in the map have been seen by
 * this node. This means that, for example, if node 1 adds key A, and node 2 also adds key A, then
 * node 1's addition is replicated to node 3, and node 3 deletes it before node 2's addition is
 * replicated, then the item will still be in the map because node 2's addition had not yet been
 * observed by node 3. However, if both additions had been replicated to node 3, then the key will
 * be removed.
 *
 * <p>The values of the map are themselves CRDTs, and hence allow concurrent updates that will
 * eventually converge. Values may only be inserted using the {@link ORMap#getOrCreate(Object,
 * Function)} function, using the {@link CrdtFactory} passed in to the creation callback. Invoking
 * {@link ORMap#put(Object, Crdt)} or any other insertion method will throw a {@link
 * UnsupportedOperationException}.
 *
 * <p>While removing entries from the map is supported, if the entries are added back again, it is
 * possible that the value of the deleted entry may be merged into the value of the current entry,
 * depending on whether the removal has been replicated to all nodes before the addition is
 * performed.
 *
 * <p>The map may contain different CRDT types as values, however, for a given key, the type must
 * never change. If two different types for the same key are inserted on different nodes, the CRDT
 * will enter an invalid state that can never be merged, and behavior of the CRDT is undefined.
 *
 * <p>Care needs to be taken to ensure that the serialized value of keys in the set is stable. For
 * example, if using protobufs, the serialized value of any maps contained in the protobuf is not
 * stable, and can yield a different set of bytes for the same logically equal element. Hence maps
 * should be avoided. Additionally, some changes in protobuf schemas which are backwards compatible
 * from a protobuf perspective, such as changing from sint32 to int32, do result in different
 * serialized bytes, and so must be avoided.
 *
 * @param <K> The type of keys.
 * @param <V> The CRDT to be used for values.
 */
public interface ORMap<K, V extends Crdt> extends Crdt, Map<K, V> {
  /**
   * Get or create an entry in the map with the given key.
   *
   * @param key The key of the entry.
   * @param create A callback used to create the value using the given {@link CrdtFactory} if an
   *     entry for the key is not currently in the map.
   * @return The existing or newly created value for the given key.
   */
  V getOrCreate(K key, Function<CrdtFactory, V> create);

  /** Not supported on ORMap. Use {@link ORMap#getOrCreate(Object, Function)} instead. */
  @Override
  default V put(K key, V value) {
    throw new UnsupportedOperationException(
        "put is not supported on ORMap, use getOrCreate instead");
  }
}
