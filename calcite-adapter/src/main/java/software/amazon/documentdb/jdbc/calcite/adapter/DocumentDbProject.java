/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package software.amazon.documentdb.jdbc.calcite.adapter;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;
import org.checkerframework.checker.nullness.qual.Nullable;
import software.amazon.documentdb.jdbc.metadata.DocumentDbMetadataColumn;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation of {@link Project}
 * relational expression in MongoDB.
 */
public class DocumentDbProject extends Project implements DocumentDbRel {

    /**
     * Creates a new {@link DocumentDbProject}
     * @param cluster the cluster.
     * @param traitSet the trait set.
     * @param input the input.
     * @param projects the projections
     * @param rowType the raw relational type.
     */
    public DocumentDbProject(final RelOptCluster cluster, final RelTraitSet traitSet,
            final RelNode input, final List<? extends RexNode> projects, final RelDataType rowType) {
        super(cluster, traitSet, ImmutableList.of(), input, projects, rowType);
        assert getConvention() == DocumentDbRel.CONVENTION;
        assert getConvention() == input.getConvention();
    }

    /**
     * DEPRECATED - Creates a new {@link DocumentDbProject}
     * @param cluster the cluster.
     * @param traitSet the trait set.
     * @param input the input.
     * @param projects the projections.
     * @param rowType the row type.
     * @param flags the flags.
     */
    @Deprecated // to be removed before 2.0
    public DocumentDbProject(final RelOptCluster cluster, final RelTraitSet traitSet,
            final RelNode input, final List<RexNode> projects, final RelDataType rowType, final int flags) {
        this(cluster, traitSet, input, projects, rowType);
        Util.discard(flags);
    }

    @Override public Project copy(final RelTraitSet traitSet, final RelNode input,
            final List<RexNode> projects, final RelDataType rowType) {
        return new DocumentDbProject(getCluster(), traitSet, input, projects,
                rowType);
    }

    @Override public @Nullable RelOptCost computeSelfCost(final RelOptPlanner planner,
            final RelMetadataQuery mq) {
        return super.computeSelfCost(planner, mq).multiplyBy(DocumentDbRules.PROJECT_COST_FACTOR);
    }

    @Override public void implement(final Implementor implementor) {
        implementor.visitChild(0, getInput());

        // DocumentDB: modified - start
        final DocumentDbRel.Implementor mongoImplementor =
                new DocumentDbRel.Implementor(implementor.getRexBuilder());
        mongoImplementor.visitChild(0, getInput());
        final DocumentDbRules.RexToMongoTranslator translator =
                new DocumentDbRules.RexToMongoTranslator(
                        (JavaTypeFactory) getCluster().getTypeFactory(),
                        DocumentDbRules.mongoFieldNames(getInput().getRowType(),
                                mongoImplementor.getMetadataTable()));
        final List<String> items = new ArrayList<>();
        final List<String> inNames = getInput().getRowType().getFieldNames();

        for (Pair<RexNode, String> pair : getNamedProjects()) {
            final String outName = pair.right;
            final String expr = pair.left.accept(translator);

            // Check if we are projecting an existing field or generating a new expression.
            if (pair.left instanceof RexInputRef) {
                final RexInputRef ref = (RexInputRef) pair.left;
                final String inName = inNames.get(ref.getIndex());

                // If projecting an existing field as is, do nothing.
                // If renaming, replace metadata column with new name but keep reference to original path.
                if (!inName.equals(outName)) {
                    final Map<String, DocumentDbMetadataColumn> columnMap = new LinkedHashMap<>(implementor.getMetadataTable().getColumns());
                    columnMap.put(outName, columnMap.get(inName));
                    columnMap.remove(inName);
                    implementor.setMetadataTable(implementor.getMetadataTable()
                            .toBuilder()
                            .columns(ImmutableMap.copyOf(columnMap))
                            .build());
                }
            } else {
                items.add(DocumentDbRules.maybeQuote(outName) + ": " + expr);
            }
        }
        if (!items.isEmpty()) {
            final String findString = Util.toString(items, "{", ", ", "}");
            final String aggregateString = "{$addFields: " + findString + "}";
            final Pair<String, String> op = Pair.of(findString, aggregateString);
            implementor.add(op.left, op.right);
        }
        // DocumentDB: modified - end
    }
}
