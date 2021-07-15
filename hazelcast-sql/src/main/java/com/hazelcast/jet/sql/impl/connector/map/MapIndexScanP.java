/*
 * Copyright 2021 Hazelcast Inc.
 *
 * Licensed under the Hazelcast Community License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://hazelcast.com/hazelcast-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.sql.impl.connector.map;

import com.hazelcast.cluster.Address;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.internal.iteration.IndexIterationPointer;
import com.hazelcast.internal.partition.InternalPartitionService;
import com.hazelcast.internal.serialization.InternalSerializationService;
import com.hazelcast.internal.util.collection.PartitionIdSet;
import com.hazelcast.jet.core.AbstractProcessor;
import com.hazelcast.jet.core.Processor;
import com.hazelcast.jet.core.ProcessorSupplier;
import com.hazelcast.jet.impl.connector.AbstractIndexReader;
import com.hazelcast.jet.sql.impl.SimpleExpressionEvalContext;
import com.hazelcast.map.impl.operation.MapFetchIndexOperation;
import com.hazelcast.map.impl.operation.MapFetchIndexOperation.MapFetchIndexOperationResult;
import com.hazelcast.map.impl.operation.MapFetchIndexOperation.MissingPartitionException;
import com.hazelcast.map.impl.operation.MapOperationProvider;
import com.hazelcast.map.impl.proxy.MapProxyImpl;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.DataSerializable;
import com.hazelcast.query.impl.QueryableEntry;
import com.hazelcast.query.impl.getters.Extractors;
import com.hazelcast.spi.impl.InternalCompletableFuture;
import com.hazelcast.spi.impl.operationservice.Operation;
import com.hazelcast.sql.impl.exec.scan.MapIndexScanMetadata;
import com.hazelcast.sql.impl.exec.scan.MapScanRow;
import com.hazelcast.sql.impl.exec.scan.index.IndexFilter;
import com.hazelcast.sql.impl.expression.ExpressionEvalContext;
import com.hazelcast.sql.impl.expression.predicate.TernaryLogic;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PrimitiveIterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.IntStream;

import static com.hazelcast.jet.impl.util.Util.getNodeEngine;
import static com.hazelcast.jet.sql.impl.ExpressionUtil.evaluate;
import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;

/**
 * Implementation of migration-tolerant IMap index scan processor.
 * <p>
 * The key component of migration-tolerant index scan is so-called 'split'.
 * It's the basic unit of execution that is attached to a concrete member
 * and there exists a single instance during normal execution.
 * <p>
 * Scan algorithm initially assumes that all assigned partitions are local.
 * Processor sends {@link MapFetchIndexOperation} to the local member
 * during normal execution, and to all members which own partitions
 * assigned to the processor after migration. After response is received,
 * processor saves the new `pointers` to `lastPointers`, emits to
 * processor's outbox, and sends another operation with the new pointers.
 * <p>
 * During partition migration processor will split the partitions in the
 * original `split` into disjoint parts, according to the owner, and retry
 * with the new owners. If all partitions in a `split` were read, the
 * `split` is removed from execution.
 */
public final class MapIndexScanP extends AbstractProcessor {

    private final MapIndexScanMetadata metadata;

    private HazelcastInstance hazelcastInstance;
    private ExpressionEvalContext evalContext;
    private AbstractIndexReader<MapFetchIndexOperationResult, QueryableEntry<?, ?>> reader;

    private final ArrayList<Split> splits = new ArrayList<>();
    private MapScanRow row;
    private Object[] pendingItem;
    private boolean isIndexSorted;

    private MapIndexScanP(@Nonnull MapIndexScanMetadata indexScanMetadata) {
        this.metadata = indexScanMetadata;
    }

    @Override
    protected void init(@Nonnull Context context) throws Exception {
        super.init(context);

        hazelcastInstance = context.hazelcastInstance();
        evalContext = SimpleExpressionEvalContext.from(context);
        reader = new LocalMapIndexReader(hazelcastInstance, evalContext.getSerializationService(), metadata);

        int[] memberPartitions = context.processorPartitions();
        splits.add(new Split(
                new PartitionIdSet(hazelcastInstance.getPartitionService().getPartitions().size(), memberPartitions),
                hazelcastInstance.getCluster().getLocalMember().getAddress(),
                filtersToPointers(metadata.getFilter(), metadata.isDescending(), evalContext)
        ));

        row = MapScanRow.create(
                metadata.getKeyDescriptor(),
                metadata.getValueDescriptor(),
                metadata.getFieldPaths(),
                metadata.getFieldTypes(),
                Extractors.newBuilder(evalContext.getSerializationService()).build(),
                evalContext.getSerializationService()
        );
        isIndexSorted = metadata.getComparator() != null;
    }

    private static IndexIterationPointer[] filtersToPointers(
            @Nonnull IndexFilter filter,
            boolean descending,
            ExpressionEvalContext evalContext
    ) {
        return IndexIterationPointer.createFromIndexFilter(filter, descending, evalContext);
    }

    @Override
    public boolean complete() {
        return isIndexSorted ? runSortedIndex() : runHashIndex();
    }

    @Override
    public boolean isCooperative() {
        return true;
    }

    @Override
    public boolean closeIsCooperative() {
        return true;
    }

    private boolean runSortedIndex() {
        for (; ; ) {
            if (pendingItem != null && !tryEmit(pendingItem)) {
                return false;
            } else {
                pendingItem = null;
            }

            Object[] extreme = null;
            int extremeIndex = -1;
            for (int i = 0; i < splits.size(); ++i) {
                Split s = splits.get(i);
                try {
                    s.peek();
                } catch (MissingPartitionException e) {
                    splits.addAll(splitOnMigration(s));
                    splits.remove(i--);
                    continue;
                }
                if (s.currentRow == null) {
                    if (s.done()) {
                        // No more items to read, remove finished split.
                        splits.remove(i--);
                        continue;
                    }
                    // waiting for more rows from this split
                    return false;
                }
                if (extremeIndex < 0
                        || metadata.getComparator().compare(s.currentRow, splits.get(extremeIndex).currentRow) < 0) {
                    extremeIndex = i;
                    extreme = s.currentRow;
                }
            }

            if (extremeIndex < 0) {
                assert splits.isEmpty();
                return true;
            }

            pendingItem = extreme;
            splits.get(extremeIndex).remove();
        }
    }

    private boolean runHashIndex() {
        for (; ; ) {
            for (int i = 0; i < splits.size(); ++i) {
                Split s = splits.get(i);
                try {
                    s.peek();
                } catch (MissingPartitionException e) {
                    splits.addAll(splitOnMigration(s));
                    splits.remove(i--);
                    continue;
                }
                if (s.currentRow == null) {
                    if (s.done()) {
                        // No more items to read, remove finished split.
                        splits.remove(i--);
                        if (splits.isEmpty()) {
                            return true;
                        }
                    }
                } else {
                    if (tryEmit(s.currentRow)) {
                        s.remove();
                    } else {
                        return false;
                    }
                }
            }
        }
    }

    /**
     * Perform splitting of a {@link Split} after receiving {@link MissingPartitionException}.
     * Method gets current partition table and proceeds with following procedure:
     * <p>
     * It splits the partitions assigned to the split according to the new
     * partition owner into disjoint sets, one for each owner.
     *
     * @param split the split to split
     * @return collection of new split units
     */
    private List<Split> splitOnMigration(Split split) {
        IndexIterationPointer[] lastPointers = split.pointers;
        InternalPartitionService partitionService = getNodeEngine(hazelcastInstance).getPartitionService();
        PrimitiveIterator.OfInt partitionIterator = split.partitions.intIterator();
        Map<Address, Split> newSplits = new HashMap<>();
        while (partitionIterator.hasNext()) {
            int partitionId = partitionIterator.nextInt();
            Address owner = partitionService.getPartition(partitionId).getOwnerOrNull();
            // TODO: does it possible that owner is NULL?
            newSplits.computeIfAbsent(owner, x -> new Split(
                    new PartitionIdSet(partitionService.getPartitionCount()), owner, lastPointers)
            ).partitions.add(partitionId);
        }
        return new ArrayList<>(newSplits.values());
    }

    private Object[] projectAndFilter(@Nonnull QueryableEntry<?, ?> entry) {
        row.setKeyValue(entry.getKey(), entry.getKeyData(), entry.getValue(), entry.getValueData());
        if (metadata.getRemainingFilter() != null
                && TernaryLogic.isNotTrue(metadata.getRemainingFilter().evalTop(row, evalContext))) {
            return null;
        }

        Object[] row = new Object[metadata.getProjection().size()];
        for (int j = 0; j < metadata.getProjection().size(); j++) {
            row[j] = evaluate(metadata.getProjection().get(j), this.row, evalContext);
        }
        return row;
    }

    /**
     * Basic unit of index scan execution bounded to concrete member and partitions set.
     * Can be split into smaller splits after migration is detected.
     */
    private final class Split {
        private final PartitionIdSet partitions;
        private final Address owner;
        private IndexIterationPointer[] pointers;
        private List<QueryableEntry<?, ?>> currentBatch = emptyList();
        private Object[] currentRow;
        private int currentBatchPosition;
        private CompletableFuture<MapFetchIndexOperationResult> future;

        private Split(PartitionIdSet partitions, Address owner, IndexIterationPointer[] pointers) {
            this.partitions = partitions;
            this.owner = owner;
            this.pointers = pointers;
            this.currentBatchPosition = 0;
            this.future = null;
        }

        /**
         * After this call, {@link #currentRow} will return the next entry to emit.
         * They are set to null, if there's no row available
         * because we're either done or waiting for more data.
         */
        private void peek() {
            // start a new async call, if we're not done and one isn't in flight
            if (future == null && pointers.length > 0) {
                future = reader.readBatch(owner, partitions, pointers);
            }

            // get the next batch from the future, if the current batch is done and one is in flight
            if (currentBatch.size() == currentBatchPosition && future != null && future.isDone()) {
                MapFetchIndexOperationResult result;
                try {
                    result = reader.toBatchResult(future);
                } catch (ExecutionException e) {
                    // unwrap the MissingPartitionException, throw other exceptions as is
                    if (e.getCause() instanceof MissingPartitionException) {
                        throw (MissingPartitionException) e.getCause();
                    }
                    throw new RuntimeException(e);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                currentBatch = reader.toRecordSet(result);
                currentBatchPosition = 0;
                pointers = result.getPointers();
                future = null;
            }

            // project the next item
            while (currentRow == null && currentBatchPosition < currentBatch.size()) {
                // Sometimes scan query may not include indexed field.
                // So, additional projection is required to ability to merge-sort an output.
                currentRow = projectAndFilter(currentBatch.get(currentBatchPosition));
                if (currentRow == null) {
                    currentBatchPosition++;
                }
            }
        }

        private void remove() {
            currentBatchPosition++;
            currentRow = null;
        }

        private boolean done() {
            return currentBatchPosition == currentBatch.size() && pointers.length == 0;
        }
    }

    private static final class LocalMapIndexReader
            extends AbstractIndexReader<MapFetchIndexOperationResult, QueryableEntry<?, ?>> {

        private static final int FETCH_SIZE_HINT = 128;

        private final HazelcastInstance hazelcastInstance;
        private final String indexName;

        private LocalMapIndexReader(
                @Nonnull HazelcastInstance hzInstance,
                @Nonnull InternalSerializationService serializationService,
                @Nonnull MapIndexScanMetadata indexScanMetadata
        ) {
            super(indexScanMetadata.getMapName(), MapFetchIndexOperationResult::getEntries);

            this.hazelcastInstance = hzInstance;
            this.indexName = indexScanMetadata.getIndexName();
            this.serializationService = serializationService;
        }

        @Override
        @Nonnull
        public InternalCompletableFuture<MapFetchIndexOperationResult> readBatch(
                Address address,
                PartitionIdSet partitions,
                IndexIterationPointer[] pointers
        ) {
            MapProxyImpl<?, ?> mapProxyImpl = (MapProxyImpl<?, ?>) hazelcastInstance.getMap(objectName);
            MapOperationProvider operationProvider = mapProxyImpl.getOperationProvider();
            Operation op = operationProvider.createFetchIndexOperation(
                    mapProxyImpl.getName(),
                    indexName,
                    pointers,
                    partitions,
                    FETCH_SIZE_HINT
            );
            return mapProxyImpl.getOperationService().invokeOnTarget(mapProxyImpl.getServiceName(), op, address);
        }
    }

    static ProcessorSupplier readMapIndexSupplier(MapIndexScanMetadata indexScanMetadata) {
        return new MapIndexScanProcessorSupplier(indexScanMetadata);
    }

    private static final class MapIndexScanProcessorSupplier implements ProcessorSupplier, DataSerializable {

        private MapIndexScanMetadata indexScanMetadata;

        @SuppressWarnings("unused")
        private MapIndexScanProcessorSupplier() {
        }

        private MapIndexScanProcessorSupplier(@Nonnull MapIndexScanMetadata indexScanMetadata) {
            this.indexScanMetadata = indexScanMetadata;
        }

        @Override
        @Nonnull
        public List<Processor> get(int count) {
            return IntStream.range(0, count)
                    .mapToObj(i -> new MapIndexScanP(indexScanMetadata))
                    .collect(toList());
        }

        @Override
        public void writeData(ObjectDataOutput out) throws IOException {
            out.writeObject(indexScanMetadata);
        }

        @Override
        public void readData(ObjectDataInput in) throws IOException {
            indexScanMetadata = in.readObject();
        }
    }
}
