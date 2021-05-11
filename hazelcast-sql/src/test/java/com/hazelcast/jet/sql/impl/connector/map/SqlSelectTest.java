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

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.jet.sql.SqlTestSupport;
import com.hazelcast.map.IMap;
import com.hazelcast.test.annotation.ParallelJVMTest;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;

@Category({QuickTest.class, ParallelJVMTest.class})
public class SqlSelectTest extends SqlTestSupport {
    @BeforeClass
    public static void setUpClass() {
        initialize(1, null);
    }

    private static List<Row> fillIMapAndGetData(IMap<Integer, String> map, final int count) {
        assert count >= 0;
        List<Row> rows = new ArrayList<>(count);
        int i;
        for (i = 0; i < count; ++i) {
            char ch = (char) (65 + i);
            String s = String.valueOf(ch);
            map.put(i, s);
            rows.add(new Row(i, s));
        }
        assertEquals(i, count);
        return rows;
    }

    @Test
    @Ignore
    public void test_basicSelect() {
        HazelcastInstance hazelcastInstance = instance().getHazelcastInstance();
        String name = randomName();
        IMap<Integer, String> map = hazelcastInstance.getMap(name);

        List<Row> rows = fillIMapAndGetData(map, 20);

        assertRowsAnyOrder("SELECT * FROM " + name, rows);
    }

    @Test
    @Ignore
    public void test_selectWithEqFilter() {
        HazelcastInstance hazelcastInstance = instance().getHazelcastInstance();
        String name = randomName();
        IMap<Integer, String> map = hazelcastInstance.getMap(name);

        fillIMapAndGetData(map, 14);
        List<Row> filteredRows = Collections.singletonList(new Row(5, "F"));

        assertRowsAnyOrder("SELECT * FROM " + name + " AS I WHERE I.__key = 5", filteredRows);
    }

    @Test
    @Ignore
    public void test_selectWithEvenNumbersFilter() {
        HazelcastInstance hazelcastInstance = instance().getHazelcastInstance();
        String name = randomName();
        IMap<Integer, String> map = hazelcastInstance.getMap(name);

        List<Row> rows = fillIMapAndGetData(map, 14);
        List<Row> filteredRows = rows.stream()
                .filter(row -> ((int) row.getValues()[0] % 2 == 0))
                .collect(toList());

        assertRowsAnyOrder("SELECT * FROM " + name + " WHERE ( __key % 2 ) = 0", filteredRows);
    }

    @Test
    @Ignore
    public void test_selectWithProjection() {
        final int thisProjection = 1;
        HazelcastInstance hazelcastInstance = instance().getHazelcastInstance();
        String name = randomName();
        IMap<Integer, String> map = hazelcastInstance.getMap(name);

        List<Row> rows = fillIMapAndGetData(map, 20);
        List<Row> projected = rows.stream()
                .map(row -> new Row(row.getValues()[thisProjection]))
                .collect(toList());

        assertRowsAnyOrder("SELECT this FROM " + name, projected);
    }
}
