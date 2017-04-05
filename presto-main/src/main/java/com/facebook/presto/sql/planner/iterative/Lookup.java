/*
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
package com.facebook.presto.sql.planner.iterative;

import com.facebook.presto.Session;
import com.facebook.presto.cost.PlanNodeCostEstimate;
import com.facebook.presto.cost.PlanNodeStatsEstimate;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.planner.plan.PlanNode;

import java.util.Map;
import java.util.function.Function;

import static com.google.common.base.Verify.verify;

public interface Lookup
{
    /**
     * Resolves a node by materializing GroupReference nodes
     * representing symbolic references to other nodes.
     *
     * If the node is not a GroupReference, it returns the
     * argument as is.
     */
    PlanNode resolve(PlanNode node);

    PlanNodeStatsEstimate getStats(Session session, Map<Symbol, Type> types, PlanNode node);

    PlanNodeCostEstimate getCumulativeCost(Session session, Map<Symbol, Type> types, PlanNode node);

    /**
     * A Lookup implementation that does not perform lookup. It satisfies contract
     * by rejecting {@link GroupReference}-s.
     */
    static Lookup noLookup()
    {
        return new Lookup() {
            @Override
            public PlanNode resolve(PlanNode node)
            {
                verify(!(node instanceof GroupReference), "Unexpected GroupReference");
                return node;
            }

            @Override
            public PlanNodeStatsEstimate getStats(Session session, Map<Symbol, Type> types, PlanNode node)
            {
                return PlanNodeStatsEstimate.UNKNOWN_STATS;
            }

            @Override
            public PlanNodeCostEstimate getCumulativeCost(Session session, Map<Symbol, Type> types, PlanNode node)
            {
                return PlanNodeCostEstimate.UNKNOWN_COST;
            }
        };
    }

    static Lookup from(Function<GroupReference, PlanNode> resolver)
    {
        return new Lookup() {
            @Override
            public PlanNode resolve(PlanNode node)
            {
                if (node instanceof GroupReference) {
                    return resolver.apply((GroupReference) node);
                }

                return node;
            }

            @Override
            public PlanNodeStatsEstimate getStats(Session session, Map<Symbol, Type> types, PlanNode node)
            {
                return PlanNodeStatsEstimate.UNKNOWN_STATS;
            }

            @Override
            public PlanNodeCostEstimate getCumulativeCost(Session session, Map<Symbol, Type> types, PlanNode node)
            {
                return PlanNodeCostEstimate.UNKNOWN_COST;
            }
        };
    }
}
