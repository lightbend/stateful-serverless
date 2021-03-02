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
 * A Positive-Negative Counter.
 *
 * <p>A Positive-Negative Counter is a counter that allows both incrementing, and decrementing. It
 * is based on two {@link GCounter}'s, a positive one that is incremented for every increment, and a
 * negative one that is incremented for every decrement. The current value of the counter is
 * calculated by subtracting the negative counter from the positive counter.
 */
public interface PNCounter extends Crdt {
  /**
   * Get the current value of the counter.
   *
   * @return The current value of the counter.
   */
  long getValue();

  /**
   * Increment the counter.
   *
   * <p>If <code>by</code> is negative, then the counter will be decremented by that much instead.
   *
   * @param by The amount to increment the counter by.
   * @return The new value of the counter.
   */
  long increment(long by);

  /**
   * Decrement the counter.
   *
   * <p>If <code>by</code> is negative, then the counter will be incremented by that much instead.
   *
   * @param by The amount to decrement the counter by.
   * @return The new value of the counter.
   */
  long decrement(long by);
}
