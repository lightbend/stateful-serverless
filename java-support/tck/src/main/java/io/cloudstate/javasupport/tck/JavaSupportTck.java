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

package io.cloudstate.javasupport.tck;

import com.example.valueentity.shoppingcart.Shoppingcart;
import io.cloudstate.javasupport.CloudState;
import io.cloudstate.javasupport.PassivationStrategy;
import io.cloudstate.javasupport.entity.EntityOptions;
import io.cloudstate.javasupport.tck.model.passivation.PassivationTckModelEntity;
import io.cloudstate.javasupport.tck.model.valuebased.ValueEntityTckModelEntity;
import io.cloudstate.javasupport.tck.model.valuebased.ValueEntityTwoEntity;
import io.cloudstate.javasupport.tck.model.action.ActionTckModelBehavior;
import io.cloudstate.javasupport.tck.model.action.ActionTwoBehavior;
import io.cloudstate.javasupport.tck.model.eventsourced.EventSourcedTckModelEntity;
import io.cloudstate.javasupport.tck.model.eventsourced.EventSourcedTwoEntity;
import io.cloudstate.samples.shoppingcart.ShoppingCartEntity;
import io.cloudstate.tck.model.Action;
import io.cloudstate.tck.model.Eventsourced;
import io.cloudstate.tck.model.entitypassivation.Entitypassivation;
import io.cloudstate.tck.model.valueentity.Valueentity;

import java.time.Duration;

public final class JavaSupportTck {
  public static final void main(String[] args) throws Exception {
    new CloudState()
        .registerAction(
            new ActionTckModelBehavior(),
            Action.getDescriptor().findServiceByName("ActionTckModel"),
            Action.getDescriptor())
        .registerAction(
            new ActionTwoBehavior(),
            Action.getDescriptor().findServiceByName("ActionTwo"),
            Action.getDescriptor())
        .registerEntity(
            ValueEntityTckModelEntity.class,
            Valueentity.getDescriptor().findServiceByName("ValueEntityTckModel"),
            Valueentity.getDescriptor())
        .registerEntity(
            ValueEntityTwoEntity.class,
            Valueentity.getDescriptor().findServiceByName("ValueEntityTwo"))
        .registerEntity(
            ShoppingCartEntity.class,
            Shoppingcart.getDescriptor().findServiceByName("ShoppingCart"),
            com.example.valueentity.shoppingcart.persistence.Domain.getDescriptor())
        .registerEventSourcedEntity(
            EventSourcedTckModelEntity.class,
            Eventsourced.getDescriptor().findServiceByName("EventSourcedTckModel"),
            Eventsourced.getDescriptor())
        .registerEventSourcedEntity(
            EventSourcedTwoEntity.class,
            Eventsourced.getDescriptor().findServiceByName("EventSourcedTwo"))
        .registerEventSourcedEntity(
            io.cloudstate.samples.eventsourced.shoppingcart.ShoppingCartEntity.class,
            com.example.shoppingcart.Shoppingcart.getDescriptor().findServiceByName("ShoppingCart"),
            com.example.shoppingcart.persistence.Domain.getDescriptor())
        .registerEntity(
            PassivationTckModelEntity.class,
            Entitypassivation.getDescriptor().findServiceByName("PassivationTckModel"),
            EntityOptions.defaults()
                .withPassivationStrategy(
                    PassivationStrategy.timeout(
                        Duration.ofSeconds(2))), // 2 seconds for timeout passivation testing!
            Entitypassivation.getDescriptor())
        .start()
        .toCompletableFuture()
        .get();
  }
}
