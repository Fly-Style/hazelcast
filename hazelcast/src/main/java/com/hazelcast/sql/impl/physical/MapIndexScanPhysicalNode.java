/*
 * Copyright (c) 2008-2019, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.sql.impl.physical;

import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.sql.impl.exec.index.IndexFilter;
import com.hazelcast.sql.impl.expression.Expression;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Node to scan a partitioned map.
 */
public class MapIndexScanPhysicalNode extends AbstractMapScanPhysicalNode {
    /** Index name. */
    private String indexName;

    /** Index filter. */
    private IndexFilter indexFilter;

    public MapIndexScanPhysicalNode() {
        // No-op.
    }

    public MapIndexScanPhysicalNode(
        String mapName,
        List<String> fieldNames,
        List<Integer> projects,
        String indexName,
        IndexFilter indexFilter,
        Expression<Boolean> remainderFilter
    ) {
        super(mapName, fieldNames, projects, remainderFilter);

        this.indexName = indexName;
        this.indexFilter = indexFilter;
    }

    public String getIndexName() {
        return indexName;
    }

    public IndexFilter getIndexFilter() {
        return indexFilter;
    }

    @Override
    public void visit(PhysicalNodeVisitor visitor) {
        visitor.onMapIndexScanNode(this);
    }

    @Override
    public void writeData(ObjectDataOutput out) throws IOException {
        super.writeData(out);

        out.writeUTF(indexName);
        indexFilter.writeData(out);
    }

    @Override
    public void readData(ObjectDataInput in) throws IOException {
        super.readData(in);

        indexName = in.readUTF();

        indexFilter = new IndexFilter();
        indexFilter.readData(in);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mapName, fieldNames, projects, indexName, indexFilter, filter);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        MapIndexScanPhysicalNode that = (MapIndexScanPhysicalNode) o;

        return mapName.equals(that.mapName)
           && fieldNames.equals(that.fieldNames)
           && projects.equals(that.projects)
           && indexName.equals(that.indexName)
           && indexFilter.equals(that.indexFilter)
           && Objects.equals(filter, that.filter);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{mapName=" + mapName + ", fieldNames=" + fieldNames + ", projects=" + projects
           + ", indexName=" + indexName + ", indexFilter=" + indexFilter + ", remainderFilter=" + filter + '}';
    }
}