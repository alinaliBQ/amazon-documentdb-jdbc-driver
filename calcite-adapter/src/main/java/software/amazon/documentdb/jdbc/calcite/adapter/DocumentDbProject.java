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
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.documentdb.jdbc.calcite.adapter.DocumentDbRules.Operand;
import software.amazon.documentdb.jdbc.metadata.DocumentDbMetadataColumn;
import software.amazon.documentdb.jdbc.metadata.DocumentDbMetadataTable;
import software.amazon.documentdb.jdbc.metadata.DocumentDbSchemaColumn;
import software.amazon.documentdb.jdbc.metadata.DocumentDbSchemaTable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Implementation of {@link Project}
 * relational expression in MongoDB.
 */
public class DocumentDbProject extends Project implements DocumentDbRel {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(DocumentDbProject.class.getName());
    private static final String ID_FIELD = "_id";

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
        final RelOptCost relOptCost = super.computeSelfCost(planner, mq);
        return relOptCost != null
                ? relOptCost.multiplyBy(DocumentDbRules.PROJECT_COST_FACTOR)
                : null;
    }

    @Override public void implement(final Implementor implementor) {
        implementor.visitChild(0, getInput());

        // DocumentDB: modified - start
        final DocumentDbRel.Implementor mongoImplementor =
                new DocumentDbRel.Implementor(implementor.getRexBuilder());
        mongoImplementor.visitChild(0, getInput());
        final List<String> inNames = getInput().getRowType().getFieldNames();
        final DocumentDbRules.RexToMongoTranslator translator =
                new DocumentDbRules.RexToMongoTranslator(
                        (JavaTypeFactory) getCluster().getTypeFactory(),
                        DocumentDbRules.mongoFieldNames(
                                getInput().getRowType(),
                                mongoImplementor.getMetadataTable()),
                        inNames, mongoImplementor.getMetadataTable(),
                        implementor.getCurrentTime());
        final List<String> items = new ArrayList<>();
        final LinkedHashMap<String, DocumentDbSchemaColumn> columnMap = new LinkedHashMap<>(implementor.getMetadataTable().getColumnMap());
        for (Pair<RexNode, String> pair : getNamedProjects()) {
            final String outName = DocumentDbRules.getNormalizedIdentifier(pair.right);
            final RexNode expandedNode = RexUtil.expandSearch(
                    implementor.getRexBuilder(), null, pair.left);
            final Operand expr = expandedNode.accept(translator);

            // Check if we are projecting an existing field or generating a new expression.
            if (pair.left instanceof RexInputRef) {
                final RexInputRef ref = (RexInputRef) pair.left;
                final String inName = inNames.get(ref.getIndex());
                final DocumentDbSchemaColumn oldColumn = implementor.getMetadataTable().getColumnMap().get(inName);

                columnMap.remove(inName);
                if (implementor.isJoin() || getRowType().getFieldList().size() > DocumentDbRules.MAX_PROJECT_FIELDS) {
                    // If doing a join or project list is too large (greater than max),
                    // replace the metadata entry but do not project the underlying data.
                    // Path stays the same.
                    columnMap.put(outName, oldColumn);
                } else {
                    // If not joining, replace the metadata entry and project. Path is updated.
                    final DocumentDbMetadataColumn newColumn = DocumentDbMetadataColumn.builder()
                            .fieldPath(oldColumn.getFieldPath())
                            .sqlName(oldColumn.getSqlName())
                            .sqlType(oldColumn.getSqlType())
                            .dbType(oldColumn.getDbType())
                            .isIndex(oldColumn.isIndex())
                            .isPrimaryKey(oldColumn.isPrimaryKey())
                            .foreignKeyTableName(oldColumn.getForeignKeyTableName())
                            .foreignKeyColumnName(oldColumn.getForeignKeyColumnName())
                            .resolvedPath(outName)
                            .build();
                    columnMap.put(outName, newColumn);
                    items.add(DocumentDbRules.maybeQuote(outName) + ": " + expr);
                }
            } else {
                items.add(DocumentDbRules.maybeQuote(outName) + ": " + expr);
                columnMap.put(outName,
                        DocumentDbMetadataColumn.builder()
                                .isGenerated(true)
                                .fieldPath(outName)
                                .sqlName(outName)
                                .build());
            }
        }
        if (!items.isEmpty()) {
            // If we are doing a join, we want to preserve all fields. Use $addFields only.
            // Else, use $project.
            final String stageString;
            if (implementor.isJoin() || getRowType().getFieldList().size() > DocumentDbRules.MAX_PROJECT_FIELDS) {
                stageString = "$addFields";
            } else {
                stageString = "$project";

                // Explicitly remove _id field to reduce document size if it is not in output.
                if (!getRowType().getFieldNames().contains(ID_FIELD)) {
                    items.add(ID_FIELD + ": 0");
                }
            }
            final String findString = Util.toString(items, "{", ", ", "}");
            final String aggregateString = "{" + stageString + ": " + findString + "}";
            final Pair<String, String> op = Pair.of(findString, aggregateString);
            implementor.add(op.left, op.right);
        }
        LOGGER.info("Created projection stages of pipeline.");
        LOGGER.debug("Pipeline stages added: {}",
                implementor.getList().stream()
                        .map(c -> c.right)
                        .toArray());

        // Set the metadata table with the updated column map.
        final DocumentDbSchemaTable metadata = DocumentDbMetadataTable.builder()
                .sqlName(implementor.getMetadataTable().getSqlName())
                .collectionName(implementor.getMetadataTable().getCollectionName())
                .columns(columnMap)
                .build();
        implementor.setMetadataTable(metadata);
        implementor.setDocumentDbTable(
                new DocumentDbTable(implementor.getDocumentDbTable().getCollectionName(), metadata));
        // DocumentDB: modified - end
    }
}
