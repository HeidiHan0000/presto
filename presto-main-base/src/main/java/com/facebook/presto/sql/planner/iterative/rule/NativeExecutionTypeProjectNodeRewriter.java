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
package com.facebook.presto.sql.planner.iterative.rule;

import com.facebook.presto.common.type.*;

import com.facebook.airlift.log.Logger;

import com.facebook.presto.Session;
import static com.facebook.presto.common.type.StandardTypes.BIGINT;
import static com.facebook.presto.common.type.StandardTypes.BIGINT_ENUM;
import static com.facebook.presto.common.type.StandardTypes.VARCHAR;
import static com.facebook.presto.common.type.StandardTypes.VARCHAR_ENUM;
import com.facebook.presto.matching.Captures;
import com.facebook.presto.matching.Pattern;
import com.facebook.presto.spi.plan.Assignments;
import com.facebook.presto.spi.plan.ProjectNode;
import com.facebook.presto.spi.relation.RowExpression;
import com.facebook.presto.spi.relation.VariableReferenceExpression;
import com.facebook.presto.sql.planner.iterative.Rule;
import com.facebook.presto.sql.relational.ProjectNodeUtils;
import com.google.common.collect.ImmutableSet;

import java.util.Map;

import static com.facebook.presto.sql.planner.plan.Patterns.project;

public class NativeExecutionTypeProjectNodeRewriter
        implements Rule<ProjectNode>
{

    private static final Logger LOG = Logger.get(NativeExecutionTypeProjectNodeRewriter.class);

    @Override
    public boolean isEnabled(Session session) {
        // not sure if we need to rewrite the VariableReferenceExpressions, disabling for now
        return false;
    }
    // used RemoveRedundantIdentityProjections as reference
    private static final Pattern<ProjectNode> PATTERN = project();

    @Override
    public Pattern<ProjectNode> getPattern()
    {
        return PATTERN;
    }

    @Override
    public Result apply(ProjectNode project, Captures captures, Context context)
    {
        Assignments assignments = project.getAssignments();
        Assignments.Builder newAssignments = Assignments.builder();
        
        LOG.info("hhhh ProjectNodeRewriter: " + project.toString());

        for (Map.Entry<VariableReferenceExpression, RowExpression> entry : assignments.entrySet()) {
            VariableReferenceExpression oldVar = entry.getKey();
            VariableReferenceExpression newVar = oldVar;
            if (oldVar.getType() instanceof TypeWithName) {
                if (((TypeWithName)oldVar.getType()).getType() instanceof BigintEnumType) {
                    newVar = new VariableReferenceExpression(oldVar.getSourceLocation(), oldVar.getName(), BigintType.BIGINT);
                } else if(((TypeWithName)oldVar.getType()).getType() instanceof VarcharEnumType) {
                    newVar = new VariableReferenceExpression(oldVar.getSourceLocation(), oldVar.getName(), VarcharType.VARCHAR);
                }
            }
        
            newAssignments.put(newVar, entry.getValue());
        }

        if (newAssignments.equals(project.getAssignments())) {
            return Result.empty(); 
        }
        project =  new ProjectNode(
                project.getSourceLocation(), 
                project.getId(), 
                project.getSource(), 
                newAssignments.build(), 
                project.getLocality()
        );

        return Result.ofPlanNode(project);

        
    }
}
