/*
 * Copyright (c) 2008-2021, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.sql.impl.connector.map;

import com.hazelcast.config.InMemoryFormat;
import com.hazelcast.internal.serialization.InternalSerializationService;
import com.hazelcast.jet.core.DAG;
import com.hazelcast.jet.core.Vertex;
import com.hazelcast.jet.core.processor.SinkProcessors;
import com.hazelcast.jet.sql.impl.JetJoinInfo;
import com.hazelcast.jet.sql.impl.connector.SqlConnector;
import com.hazelcast.jet.sql.impl.connector.keyvalue.KvMetadata;
import com.hazelcast.jet.sql.impl.connector.keyvalue.KvMetadataJavaResolver;
import com.hazelcast.jet.sql.impl.connector.keyvalue.KvMetadataResolvers;
import com.hazelcast.jet.sql.impl.connector.keyvalue.KvProcessors;
import com.hazelcast.jet.sql.impl.connector.keyvalue.KvRowProjector;
import com.hazelcast.jet.sql.impl.inject.UpsertTargetDescriptor;
import com.hazelcast.jet.sql.impl.schema.MappingField;
import com.hazelcast.map.impl.MapContainer;
import com.hazelcast.map.impl.MapService;
import com.hazelcast.map.impl.MapServiceContext;
import com.hazelcast.spi.impl.NodeEngine;
import com.hazelcast.sql.impl.expression.Expression;
import com.hazelcast.sql.impl.extract.QueryPath;
import com.hazelcast.sql.impl.extract.QueryTargetDescriptor;
import com.hazelcast.sql.impl.plan.node.JetMapScanMetadata;
import com.hazelcast.sql.impl.plan.node.PlanNodeSchema;
import com.hazelcast.sql.impl.schema.ConstantTableStatistics;
import com.hazelcast.sql.impl.schema.Table;
import com.hazelcast.sql.impl.schema.TableField;
import com.hazelcast.sql.impl.schema.map.MapTableField;
import com.hazelcast.sql.impl.schema.map.PartitionedMapTable;
import com.hazelcast.sql.impl.type.QueryDataType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hazelcast.jet.core.Edge.between;
import static com.hazelcast.sql.impl.schema.map.MapTableUtils.estimatePartitionedMapRowCount;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Stream.concat;

public class IMapSqlConnector implements SqlConnector {

    public static final IMapSqlConnector INSTANCE = new IMapSqlConnector();

    public static final String TYPE_NAME = "IMap";

    private static final KvMetadataResolvers METADATA_RESOLVERS = new KvMetadataResolvers(
            KvMetadataJavaResolver.INSTANCE,
            MetadataPortableResolver.INSTANCE,
            MetadataJsonResolver.INSTANCE
    );

    private final AtomicInteger id = new AtomicInteger(0);

    @Override
    public String typeName() {
        return TYPE_NAME;
    }

    @Override
    public boolean isStream() {
        return false;
    }

    @Nonnull @Override
    public List<MappingField> resolveAndValidateFields(
            @Nonnull NodeEngine nodeEngine,
            @Nonnull Map<String, String> options,
            @Nonnull List<MappingField> userFields
    ) {
        return METADATA_RESOLVERS.resolveAndValidateFields(userFields, options, nodeEngine);
    }

    @Nonnull @Override
    public Table createTable(
            @Nonnull NodeEngine nodeEngine,
            @Nonnull String schemaName,
            @Nonnull String mappingName,
            @Nonnull String externalName,
            @Nonnull Map<String, String> options,
            @Nonnull List<MappingField> resolvedFields
    ) {
        InternalSerializationService ss = (InternalSerializationService) nodeEngine.getSerializationService();

        KvMetadata keyMetadata = METADATA_RESOLVERS.resolveMetadata(true, resolvedFields, options, ss);
        KvMetadata valueMetadata = METADATA_RESOLVERS.resolveMetadata(false, resolvedFields, options, ss);
        List<TableField> fields = concat(keyMetadata.getFields().stream(), valueMetadata.getFields().stream())
                .collect(toList());

        MapService service = nodeEngine.getService(MapService.SERVICE_NAME);
        MapServiceContext context = service.getMapServiceContext();
        MapContainer container = context.getMapContainer(externalName);

        long estimatedRowCount = estimatePartitionedMapRowCount(nodeEngine, context, externalName);
        boolean hd = container != null && container.getMapConfig().getInMemoryFormat() == InMemoryFormat.NATIVE;

        return new PartitionedMapTable(
                schemaName,
                mappingName,
                externalName,
                fields,
                new ConstantTableStatistics(estimatedRowCount),
                keyMetadata.getQueryTargetDescriptor(),
                valueMetadata.getQueryTargetDescriptor(),
                keyMetadata.getUpsertTargetDescriptor(),
                valueMetadata.getUpsertTargetDescriptor(),
                Collections.emptyList(),
                hd
        );
    }

    @Override
    public boolean supportsNestedLoopReader() {
        return true;
    }

    @Nonnull @Override
    public VertexWithInputConfig nestedLoopReader(
            @Nonnull DAG dag,
            @Nonnull Table table0,
            @Nullable Expression<Boolean> predicate,
            @Nonnull List<Expression<?>> projections,
            @Nonnull JetJoinInfo joinInfo
    ) {
        PartitionedMapTable table = (PartitionedMapTable) table0;

        String name = table.getMapName();
        List<TableField> fields = table.getFields();
        QueryPath[] paths = fields.stream().map(field -> ((MapTableField) field).getPath()).toArray(QueryPath[]::new);
        QueryDataType[] types = fields.stream().map(TableField::getType).toArray(QueryDataType[]::new);
        QueryTargetDescriptor keyDescriptor = table.getKeyDescriptor();
        QueryTargetDescriptor valueDescriptor = table.getValueDescriptor();

        KvRowProjector.Supplier rightRowProjectorSupplier =
                KvRowProjector.supplier(paths, types, keyDescriptor, valueDescriptor, predicate, projections);

        return IMapJoiner.join(dag, name, toString(table), joinInfo, rightRowProjectorSupplier);
    }

    @Override
    public boolean supportsFullScanReader() {
        return true;
    }

    @Nonnull
    @Override
    public Vertex fullScanReader(
            @Nonnull DAG dag,
            @Nonnull Table table0,
            @Nullable Expression<Boolean> predicate,
            @Nonnull List<Expression<?>> projection) {
        PartitionedMapTable table = (PartitionedMapTable) table0;
        PlanNodeSchema schemaBefore = getScanSchemaBeforeProject(table);

        JetMapScanMetadata mapScanPlanNode = new JetMapScanMetadata(
                table.getMapName(),
                table.getKeyDescriptor(),
                table.getValueDescriptor(),
                getScanFieldPaths(table),
                schemaBefore.getTypes(),
                getProjections(table, projection),
                predicate
        );

        Vertex vStart = dag.newUniqueVertex(
                table.toString(),
                OnHeapMapScanP.onHeapMapScanP(
                        table.getMapName(),
                        mapScanPlanNode
                )
        );
        return vStart;

    }

    @Override
    public boolean supportsSink() {
        return true;
    }

    @Override
    public boolean supportsInsert() {
        return false;
    }

    @Nonnull
    @Override
    public Vertex sink(
            @Nonnull DAG dag,
            @Nonnull Table table0
    ) {
        PartitionedMapTable table = (PartitionedMapTable) table0;

        List<TableField> fields = table.getFields();
        QueryPath[] paths = fields.stream().map(field -> ((MapTableField) field).getPath()).toArray(QueryPath[]::new);
        QueryDataType[] types = fields.stream().map(TableField::getType).toArray(QueryDataType[]::new);

        Vertex vStart = dag.newUniqueVertex(
                "Project(" + toString(table) + ")",
                KvProcessors.entryProjector(
                        paths,
                        types,
                        (UpsertTargetDescriptor) table.getKeyJetMetadata(),
                        (UpsertTargetDescriptor) table.getValueJetMetadata()
                )
        );

        Vertex vEnd = dag.newUniqueVertex(
                toString(table),
                SinkProcessors.writeMapP(table.getMapName())
        );

        dag.edge(between(vStart, vEnd));
        return vStart;
    }

    private static String toString(PartitionedMapTable table) {
        return TYPE_NAME + "[" + table.getSchemaName() + "." + table.getSqlName() + "]";
    }

    private static List<QueryPath> getScanFieldPaths(PartitionedMapTable table) {
        List<QueryPath> res = new ArrayList<>(table.getFieldCount());

        for (int i = 0; i < table.getFieldCount(); i++) {
            MapTableField field = table.getField(i);

            res.add(field.getPath());
        }

        return res;
    }

    private static PlanNodeSchema getScanSchemaBeforeProject(PartitionedMapTable table) {
        List<QueryDataType> types = new ArrayList<>(table.getFieldCount());

        for (int i = 0; i < table.getFieldCount(); i++) {
            MapTableField field = table.getField(i);

            types.add(field.getType());
        }

        return new PlanNodeSchema(types);
    }

    private List<Integer> getProjections(PartitionedMapTable table, List<Expression<?>> projections) {
        // TODO: APPLY NEEDED PROJECTIONS
        int fieldCount = table.getFieldCount();
        List<Integer> res = new ArrayList<>(fieldCount);
        for (int i = 0; i < fieldCount; i++) {
            res.add(i);
        }
        return res;
    }

}
