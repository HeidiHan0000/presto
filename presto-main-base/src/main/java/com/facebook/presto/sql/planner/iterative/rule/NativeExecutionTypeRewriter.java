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

import com.facebook.presto.Session;
import com.facebook.presto.common.function.OperatorType;
import com.facebook.presto.common.type.*;
import com.facebook.presto.expressions.RowExpressionRewriter;
import com.facebook.presto.expressions.RowExpressionTreeRewriter;
import com.facebook.presto.metadata.FunctionAndTypeManager;
import com.facebook.presto.spi.relation.*;
import com.facebook.presto.sql.planner.iterative.Rule;
import com.facebook.presto.sql.relational.FunctionResolution;


import static com.facebook.presto.SystemSessionProperties.NATIVE_EXECUTION_TYPE_REWRITE_ENABLED;

import static com.facebook.presto.sql.analyzer.TypeSignatureProvider.fromTypes;
import static com.facebook.presto.sql.relational.Expressions.call;
import static java.util.Objects.requireNonNull;

import com.facebook.airlift.log.Logger;
import com.facebook.presto.sql.analyzer.SemanticException;
import com.facebook.presto.sql.tree.ExpressionRewriter;
import io.airlift.slice.Slices;


import java.lang.invoke.MethodHandle;
import java.util.*;
import java.util.stream.Collectors;

import static com.facebook.presto.common.type.StandardTypes.BIGINT_ENUM;
import static com.facebook.presto.common.type.StandardTypes.VARCHAR_ENUM;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.TYPE_MISMATCH;

public class NativeExecutionTypeRewriter
        extends RowExpressionRewriteRuleSet
{
    private static final Logger LOG = Logger.get(ExpressionRewriter.class);

    private static final String FUNCTION_ENUM_KEY = "enum_key";
    private static final String FUNCTION_ELEMENT_AT = "element_at";
    private static final String FUNCTION_MAP = "map";
    private static final String FUNCTION_ARRAY = "array";

    public NativeExecutionTypeRewriter(FunctionAndTypeManager functionAndTypeManager)
    {
        super(new Rewriter(functionAndTypeManager));
    }

    @Override
    public boolean isRewriterEnabled(Session session)
    {
       return session.getSystemProperty(NATIVE_EXECUTION_TYPE_REWRITE_ENABLED, Boolean.class);
    }

    private static class Rewriter
            implements PlanRowExpressionRewriter
    {
        private final EnumExpressionRewriter enumExpressionRewriter;

        public Rewriter(FunctionAndTypeManager functionAndTypeManager)
        {
            requireNonNull(functionAndTypeManager, "functionAndTypeManager is null");
            this.enumExpressionRewriter = new EnumExpressionRewriter(functionAndTypeManager);
        }

        @Override
        public RowExpression rewrite(RowExpression expression, Rule.Context context)
        {
            return rewrite(expression, context.getSession());
        }

        private RowExpression rewrite(RowExpression expression, Session session)
        {
            return RowExpressionTreeRewriter.rewriteWith(enumExpressionRewriter, expression);
        }
    }

    private static class EnumExpressionRewriter
            extends RowExpressionRewriter<Void>
    {
        private final FunctionAndTypeManager functionAndTypeManager;
        
        private final FunctionResolution functionResolution;

        private EnumExpressionRewriter(FunctionAndTypeManager functionAndTypeManager)
        {
            this.functionAndTypeManager = functionAndTypeManager;
            this.functionResolution = new FunctionResolution(functionAndTypeManager.getFunctionAndTypeResolver());
        }

        private Type rewriteEnumTypeToBaseType(Type originalType) {
            if (originalType instanceof TypeWithName) {
                Type type = ((TypeWithName) originalType).getType();
                if (type instanceof BigintEnumType) {
                    return BigintType.BIGINT;
                } else if (type instanceof VarcharEnumType) {
                    return VarcharType.VARCHAR;
                } 
            }
            return originalType;
        }

        @Override
        public RowExpression rewriteRowExpression(RowExpression node, Void context, RowExpressionTreeRewriter<Void> treeRewriter)
        {
            Type argumentType = node.getType();
            if (argumentType instanceof TypeWithName) {
                LOG.info("rewriteRowExpression unhandled case " + node.toString() + " " + node.getType());
            }
            return node;
        }

        @Override
        public RowExpression rewriteInputReference(InputReferenceExpression node, Void context, RowExpressionTreeRewriter<Void> treeRewriter)
        {
            LOG.info("rewriteInputReference " + node.toString() + " " +  node.getType());
            return rewriteRowExpression(node, context, treeRewriter);
        }

        @Override
        public RowExpression rewriteCall(CallExpression node, Void context, RowExpressionTreeRewriter<Void> treeRewriter)
        {
            LOG.info("rewriteCall " + node.toString() + " " +  node.getType());
            // Handles enum_key function separately, does the following rewrite:
            // ENUM_KEY(EnumType<T>) -> ELEMENT_AT(MAP(<T>, VARCHAR))
            if (Objects.equals(node.getDisplayName(), FUNCTION_ENUM_KEY)) {
                List<RowExpression> rewrittenArguments = new ArrayList<>();
                List<RowExpression> arguments = node.getArguments();
                RowExpression argument = arguments.get(0);
                // rewrite arguments as well
                argument = treeRewriter.rewrite(argument, context);
                Type argType = argument.getType();
                // i think i can just work with constant or call
                if (argType instanceof TypeWithName) {
                    argType = ((TypeWithName) argType).getType();

                    List<RowExpression> keyExpressions = new ArrayList<>();
                    List<RowExpression> valueExpressions = new ArrayList<>();;
                    switch (argType.getTypeSignature().getBase()) {
                        case BIGINT_ENUM:
                            for (Map.Entry<String, Long> entry : ((BigintEnumType) argType).getEnumMap().entrySet()) {
                                keyExpressions.add(new ConstantExpression(entry.getValue(), BigintType.BIGINT));
                                valueExpressions.add(new ConstantExpression(Slices.utf8Slice(String.valueOf(entry.getKey())), VarcharType.VARCHAR));
                            }
                            break;
                        case VARCHAR_ENUM:
                            for (Map.Entry<String, String> entry : ((VarcharEnumType) argType).getEnumMap().entrySet()) {
                                keyExpressions.add(new ConstantExpression(Slices.utf8Slice(String.valueOf(entry.getValue())), VarcharType.VARCHAR));
                                valueExpressions.add(new ConstantExpression(Slices.utf8Slice(String.valueOf(entry.getKey())), VarcharType.VARCHAR));
                            }
                            break;
                        default:
                            throw new SemanticException(TYPE_MISMATCH, "Unknown type: " + argType);
                    }
                    Type keyType = keyExpressions.get(0).getType();
                    Type valueType = valueExpressions.get(0).getType();
                    MethodHandle keyEquals = functionAndTypeManager.getJavaScalarFunctionImplementation(
                            functionAndTypeManager.resolveOperator(OperatorType.EQUAL, fromTypes(keyType, keyType))).getMethodHandle();
                    MethodHandle keyHashcode = functionAndTypeManager.getJavaScalarFunctionImplementation(
                            functionAndTypeManager.resolveOperator(OperatorType.HASH_CODE, fromTypes(keyType))).getMethodHandle();
                    MapType mapType = new MapType(keyType, valueType, keyEquals, keyHashcode);
                    RowExpression keyArray = call(FUNCTION_ARRAY, functionResolution.arrayConstructor(keyExpressions.stream().map(x -> x.getType()).collect(Collectors.toList())), new ArrayType(keyExpressions.get(0).getType()), keyExpressions);
                    RowExpression valueArray = call(FUNCTION_ARRAY, functionResolution.arrayConstructor(valueExpressions.stream().map(x -> x.getType()).collect(Collectors.toList())), new ArrayType(valueExpressions.get(0).getType()), valueExpressions);
                    RowExpression map = call(functionAndTypeManager, FUNCTION_MAP, mapType, keyArray, valueArray);
                    RowExpression elementAt = call(functionAndTypeManager, FUNCTION_ELEMENT_AT, valueType, map, argument);
                    LOG.info("rewriteCall rewritten elementAt" + elementAt.toString() + " " +  elementAt.getType());
                    return elementAt;
                } else {
                    LOG.info("shoulda thrown, enum_key function called with argument that is not enum type");
                }
            }
            Type rewrittenType = rewriteEnumTypeToBaseType(node.getType());
            if (rewrittenType == node.getType()) {
                return node;
            }
            LOG.info("type rewritten" + node.getType() + rewrittenType);
            return new CallExpression(node.getDisplayName(), node.getFunctionHandle(), rewrittenType, node.getArguments());
        }

        @Override
        public RowExpression rewriteConstant(ConstantExpression node, Void context, RowExpressionTreeRewriter<Void> treeRewriter)
        {
            LOG.info("rewriteConstant " + node.toString() + " " +  node.getType());
            Type rewrittenType = rewriteEnumTypeToBaseType(node.getType());
            if (rewrittenType == node.getType()) {
                return node;
            }
            LOG.info("type rewritten" + node.getType() + rewrittenType);
            return ConstantExpression.createConstantExpression(node.getValueBlock(), rewrittenType);
        }

        @Override
        public RowExpression rewriteLambda(LambdaDefinitionExpression node, Void context, RowExpressionTreeRewriter<Void> treeRewriter)
        {
            LOG.info("rewriteLambda " + node.toString() + " " +  node.getType());
            return rewriteRowExpression(node, context, treeRewriter);
        }

        @Override
        public RowExpression rewriteVariableReference(VariableReferenceExpression node, Void context, RowExpressionTreeRewriter<Void> treeRewriter)
        {
            LOG.info("rewriteVariableReference " + node.getName() + " " +  node.getType());

            // not sure if we need to rewrite VariableReferenceExpressions, commenting out for now
            // Type rewrittenType = rewriteEnumTypeToBaseType(node.getType());
            // if (rewrittenType == node.getType()) {
            //     return node;
            // }
            // LOG.info("type rewritten" + node.getType() + rewrittenType);
            // return new VariableReferenceExpression(node.getSourceLocation(), node.getName(), rewrittenType);
            
           return rewriteRowExpression(node, context, treeRewriter);
        }

        @Override
        public RowExpression rewriteSpecialForm(SpecialFormExpression node, Void context, RowExpressionTreeRewriter<Void> treeRewriter)
        {
            LOG.info("rewriteSpecialForm " + node.toString() + " " +  node.getType());
            Type rewrittenType = rewriteEnumTypeToBaseType(node.getType());
            if (node.getType() == rewrittenType) {
                return node;
            }
            LOG.info("type rewritten" + node.getType() + rewrittenType);

            return new SpecialFormExpression(node.getSourceLocation(), node.getForm(), rewrittenType, node.getArguments());
        }
    }
}
