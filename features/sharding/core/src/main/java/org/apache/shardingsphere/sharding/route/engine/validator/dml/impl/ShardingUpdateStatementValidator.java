/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.sharding.route.engine.validator.dml.impl;

import org.apache.shardingsphere.infra.binder.context.statement.SQLStatementContext;
import org.apache.shardingsphere.infra.binder.context.statement.dml.UpdateStatementContext;
import org.apache.shardingsphere.infra.config.props.ConfigurationProperties;
import org.apache.shardingsphere.infra.exception.core.ShardingSpherePreconditions;
import org.apache.shardingsphere.infra.hint.HintValueContext;
import org.apache.shardingsphere.infra.metadata.database.ShardingSphereDatabase;
import org.apache.shardingsphere.infra.route.context.RouteContext;
import org.apache.shardingsphere.sharding.exception.syntax.DMLMultipleDataNodesWithLimitException;
import org.apache.shardingsphere.sharding.exception.syntax.UnsupportedUpdatingShardingValueException;
import org.apache.shardingsphere.sharding.route.engine.condition.ShardingConditions;
import org.apache.shardingsphere.sharding.route.engine.type.standard.ShardingStandardRouteEngine;
import org.apache.shardingsphere.sharding.route.engine.validator.dml.ShardingDMLStatementValidator;
import org.apache.shardingsphere.sharding.rule.ShardingRule;
import org.apache.shardingsphere.sql.parser.statement.core.statement.dml.UpdateStatement;

import java.util.List;
import java.util.Optional;

/**
 * Sharding update statement validator.
 */
public final class ShardingUpdateStatementValidator extends ShardingDMLStatementValidator {
    
    @Override
    public void postValidate(final ShardingRule shardingRule, final SQLStatementContext sqlStatementContext, final HintValueContext hintValueContext, final List<Object> params,
                             final ShardingSphereDatabase database, final ConfigurationProperties props, final RouteContext routeContext) {
        UpdateStatementContext updateStatementContext = (UpdateStatementContext) sqlStatementContext;
        String tableName = updateStatementContext.getTablesContext().getTableNames().iterator().next();
        UpdateStatement updateStatement = updateStatementContext.getSqlStatement();
        Optional<ShardingConditions> shardingConditions = createShardingConditions(sqlStatementContext, shardingRule, updateStatement.getSetAssignment().getAssignments(), params);
        Optional<RouteContext> setAssignmentRouteContext = shardingConditions.map(optional -> new ShardingStandardRouteEngine(tableName, optional, sqlStatementContext,
                hintValueContext, props).route(shardingRule));
        if (setAssignmentRouteContext.isPresent() && !isSameRouteContext(routeContext, setAssignmentRouteContext.get())) {
            throw new UnsupportedUpdatingShardingValueException(tableName);
        }
        ShardingSpherePreconditions.checkState(!updateStatement.getLimit().isPresent()
                || routeContext.getRouteUnits().size() <= 1, () -> new DMLMultipleDataNodesWithLimitException("UPDATE"));
    }
}
