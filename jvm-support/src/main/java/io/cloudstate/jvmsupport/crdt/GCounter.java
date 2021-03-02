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
 * A Grow-only Counter.
 *
 * <p>A Grow-only Counter can be incremented, but can't be decremented.
 */
public interface GCounter extends Crdt {
  /**
   * Get the current value of the counter.
   *
   * @return The current value of the counter.
   */
  long getValue();

  /**
   * Increment the counter.
   *
   * @param by The amount to increment the counter by.
   * @return The new value of the counter.
   * @throws IllegalArgumentException If <code>by</code> is negative.
   */
  long increment(long by) throws IllegalArgumentException;
}
