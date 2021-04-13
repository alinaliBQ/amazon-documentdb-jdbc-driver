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

import com.google.common.collect.ImmutableMap;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import lombok.SneakyThrows;
import org.apache.calcite.adapter.java.AbstractQueryableTable;
import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.linq4j.QueryProvider;
import org.apache.calcite.linq4j.Queryable;
import org.apache.calcite.linq4j.function.Function1;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.TranslatableTable;
import org.apache.calcite.schema.impl.AbstractTableQueryable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.documentdb.jdbc.common.utilities.SqlError;
import software.amazon.documentdb.jdbc.common.utilities.SqlState;
import software.amazon.documentdb.jdbc.metadata.DocumentDbMetadataColumn;
import software.amazon.documentdb.jdbc.metadata.DocumentDbMetadataTable;

import java.sql.Types;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class DocumentDbTable extends AbstractQueryableTable
        implements TranslatableTable {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentDbTable.class);
    private static volatile Map<Integer, RelDataType> jdbcTypeToRelDataType = null;

    private final String collectionName;
    private final DocumentDbMetadataTable tableMetadata;

    protected DocumentDbTable(
            final String collectionName,
            final DocumentDbMetadataTable tableMetadata) {
        super(Object[].class);
        this.collectionName = collectionName;
        this.tableMetadata = tableMetadata;
    }

    @Override public String toString() {
        return "DocumentDbTable {" + tableMetadata.getName() + "}";
    }

    public String getCollectionName() {
        return this.collectionName;
    }

    @SneakyThrows
    @Override public RelDataType getRowType(final RelDataTypeFactory typeFactory) {
        final List<Map.Entry<String, RelDataType>> fieldList = new ArrayList<>();
        SimpleEntry<String, RelDataType> field;
        int sqlType;
        RelDataType relDataType;
        boolean nullable;

        if (jdbcTypeToRelDataType == null) {
            initializeRelDataTypeMap(typeFactory);
        }

        for (Entry<String, DocumentDbMetadataColumn> entry :
                tableMetadata.getColumns().entrySet()) {

            sqlType = entry.getValue().getSqlType();
            if (sqlType == Types.ARRAY || sqlType == Types.JAVA_OBJECT) {
                continue;
            }

            relDataType = jdbcTypeToRelDataType.get(sqlType);
            if (relDataType == null) {
                throw SqlError.createSQLException(
                        LOGGER,
                        SqlState.DATA_TYPE_TRANSFORM_VIOLATION,
                        SqlError.UNSUPPORTED_TYPE, sqlType);
            }

            nullable = entry.getValue().getPrimaryKey() == 0;
            field = new SimpleEntry<>(entry.getKey(),
                    typeFactory.createTypeWithNullability(relDataType, nullable));
            fieldList.add(field);
        }

        return typeFactory.createStructType(fieldList);
    }

    @Override public <T> Queryable<T> asQueryable(final QueryProvider queryProvider,
            final SchemaPlus schema, final String tableName) {
        return new DocumentDbQueryable<T>(queryProvider, schema, this, tableName);
    }

    @Override public RelNode toRel(
            final RelOptTable.ToRelContext context,
            final RelOptTable relOptTable) {
        final RelOptCluster cluster = context.getCluster();
        return new DocumentDbTableScan(cluster, cluster.traitSetOf(DocumentDbRel.CONVENTION),
                relOptTable, this, null, tableMetadata);
    }

    /** Executes a "find" operation on the underlying collection.
     *
     * <p>For example,
     * <code>zipsTable.find("{state: 'OR'}", "{city: 1, zipcode: 1}")</code></p>
     *
     * @param mongoDb MongoDB connection
     * @param filterJson Filter JSON string, or null
     * @param projectJson Project JSON string, or null
     * @param fields List of fields to project; or null to return map
     * @return Enumerator of results
     */
    private Enumerable<Object> find(final MongoDatabase mongoDb, final String filterJson,
            final String projectJson, final List<Entry<String, Class>> fields) {
        final MongoCollection collection =
                mongoDb.getCollection(collectionName);
        final Bson filter =
                filterJson == null ? null : BsonDocument.parse(filterJson);
        final Bson project =
                projectJson == null ? null : BsonDocument.parse(projectJson);
        final Function1<Document, Object> getter = DocumentDbEnumerator.getter(fields, tableMetadata);
        return new AbstractEnumerable<Object>() {
            @Override public Enumerator<Object> enumerator() {
                @SuppressWarnings("unchecked") final FindIterable<Document> cursor =
                        collection.find(filter).projection(project);
                return new DocumentDbEnumerator(cursor.iterator(), getter);
            }
        };
    }

    /** Executes an "aggregate" operation on the underlying collection.
     *
     * <p>For example:
     * <code>zipsTable.aggregate(
     * "{$filter: {state: 'OR'}",
     * "{$group: {_id: '$city', c: {$sum: 1}, p: {$sum: '$pop'}}}")
     * </code></p>
     *
     * @param mongoDb MongoDB connection
     * @param fields List of fields to project; or null to return map
     * @param operations One or more JSON strings
     * @return Enumerator of results
     */
    Enumerable<Object> aggregate(final MongoDatabase mongoDb,
            final List<Entry<String, Class>> fields,
            final List<String> operations) {
        final List<Bson> list = new ArrayList<>();

        for (String operation : operations) {
            list.add(BsonDocument.parse(operation));
        }
        final Function1<Document, Object> getter =
                DocumentDbEnumerator.getter(fields, tableMetadata);
        // Return this instead of the anonymous class to get more information from CalciteSignature.
        return new DocumentDbEnumerable(mongoDb, tableMetadata, collectionName, list, operations, fields, getter);
    }

    /** Implementation of {@link org.apache.calcite.linq4j.Queryable} based on
     * a {@link DocumentDbTable}.
     *
     * @param <T> element type */
    public static class DocumentDbQueryable<T> extends AbstractTableQueryable<T> {
        DocumentDbQueryable(final QueryProvider queryProvider, final SchemaPlus schema,
                final DocumentDbTable table, final String tableName) {
            super(queryProvider, schema, table, tableName);
        }

        @SuppressWarnings("unchecked")
        @Override public Enumerator<T> enumerator() {
            //noinspection unchecked
            final Enumerable<T> enumerable =
                    (Enumerable<T>) getTable().find(getMongoDb(), null, null, null);
            return enumerable.enumerator();
        }

        private MongoDatabase getMongoDb() {
            return schema.unwrap(DocumentDbSchema.class).getMongoDatabase();
        }

        private DocumentDbTable getTable() {
            return (DocumentDbTable) table;
        }

        /** Called via code-generation.
         *
         * @see DocumentDbMethod#MONGO_QUERYABLE_AGGREGATE
         * @return an enumerable of the aggregate pipeline
         */
        @SuppressWarnings("UnusedDeclaration")
        public Enumerable<Object> aggregate(final List<Entry<String, Class>> fields,
                final List<String> operations) {
            return getTable().aggregate(getMongoDb(), fields, operations);
        }

        /** Called via code-generation.
         *
         * @param filterJson Filter document
         * @param projectJson Projection document
         * @param fields List of expected fields (and their types)
         * @return result of mongo query
         *
         * @see DocumentDbMethod#MONGO_QUERYABLE_FIND
         */
        @SuppressWarnings("UnusedDeclaration")
        public Enumerable<Object> find(final String filterJson,
                final String projectJson, final List<Entry<String, Class>> fields) {
            return getTable().find(getMongoDb(), filterJson, projectJson, fields);
        }
    }

    private static synchronized void initializeRelDataTypeMap(final RelDataTypeFactory typeFactory) {
        if (jdbcTypeToRelDataType == null) {
            jdbcTypeToRelDataType =
                    ImmutableMap.<Integer, RelDataType>builder()
                            .put(Types.BIGINT, typeFactory.createSqlType(SqlTypeName.BIGINT))
                            .put(Types.BOOLEAN, typeFactory.createSqlType(SqlTypeName.BOOLEAN))
                            .put(
                                    Types.DECIMAL,
                                    typeFactory.createSqlType(
                                            SqlTypeName.DECIMAL,
                                            typeFactory.getTypeSystem().getMaxPrecision(SqlTypeName.DECIMAL),
                                            typeFactory.getTypeSystem().getMaxScale(SqlTypeName.DECIMAL)))
                            .put(Types.DOUBLE, typeFactory.createSqlType(SqlTypeName.DOUBLE))
                            .put(Types.INTEGER, typeFactory.createSqlType(SqlTypeName.INTEGER))
                            .put(Types.NULL, typeFactory.createSqlType(SqlTypeName.VARCHAR))
                            .put(Types.TIMESTAMP, typeFactory.createSqlType(SqlTypeName.TIMESTAMP))
                            .put(
                                    Types.VARCHAR,
                                    typeFactory.createSqlType(
                                            SqlTypeName.VARCHAR,
                                            typeFactory.getTypeSystem().getMaxPrecision(SqlTypeName.VARCHAR)))
                            .put(
                                    Types.VARBINARY,
                                    typeFactory.createSqlType(
                                            SqlTypeName.VARBINARY,
                                            typeFactory.getTypeSystem().getMaxPrecision(SqlTypeName.VARBINARY)))
                            .build();
        }
    }
}
