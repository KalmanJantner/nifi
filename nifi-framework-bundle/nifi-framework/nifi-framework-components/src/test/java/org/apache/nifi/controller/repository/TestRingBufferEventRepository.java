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
package org.apache.nifi.controller.repository;

import org.apache.nifi.controller.repository.metrics.RingBufferEventRepository;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestRingBufferEventRepository {

    @Test
    public void testAdd() throws IOException {
        final RingBufferEventRepository repo = new RingBufferEventRepository(5);
        long insertNanos = 0L;
        for (int i = 0; i < 1000000; i++) {
            final FlowFileEvent event = generateEvent();

            final long insertStart = System.nanoTime();
            repo.updateRepository(event, "ABC");
            insertNanos += System.nanoTime() - insertStart;
        }

        final long queryStart = System.nanoTime();
        final StandardRepositoryStatusReport report = repo.reportTransferEvents(System.currentTimeMillis());
        final long queryNanos = System.nanoTime() - queryStart;
        assertNotNull(report);
        assertTrue(TimeUnit.MILLISECONDS.convert(insertNanos, TimeUnit.NANOSECONDS) > 0L);
        assertTrue(TimeUnit.MILLISECONDS.convert(queryNanos, TimeUnit.NANOSECONDS) >= 0L);

        repo.close();
    }

    @Test
    public void testPurge() throws IOException {
        final FlowFileEventRepository repo = new RingBufferEventRepository(5);
        String id1 = "component1";
        String id2 = "component2";
        repo.updateRepository(generateEvent(), id1);
        repo.updateRepository(generateEvent(), id2);
        RepositoryStatusReport report = repo.reportTransferEvents(System.currentTimeMillis());
        FlowFileEvent entry = report.getReportEntry(id1);
        assertNotNull(entry);
        entry = report.getReportEntry(id2);
        assertNotNull(entry);

        repo.purgeTransferEvents(id1);
        report = repo.reportTransferEvents(System.currentTimeMillis());
        entry = report.getReportEntry(id1);
        assertNull(entry);
        entry = report.getReportEntry(id2);
        assertNotNull(entry);

        repo.purgeTransferEvents(id2);
        report = repo.reportTransferEvents(System.currentTimeMillis());
        entry = report.getReportEntry(id2);
        assertNull(entry);

        repo.close();
    }

    private FlowFileEvent generateEvent() {
        return new FlowFileEvent() {
            @Override
            public int getFlowFilesIn() {
                return 1;
            }

            @Override
            public int getFlowFilesOut() {
                return 1;
            }

            @Override
            public long getContentSizeIn() {
                return 1024L;
            }

            @Override
            public long getContentSizeOut() {
                return 1024 * 1024L;
            }

            @Override
            public long getBytesRead() {
                return 1024L;
            }

            @Override
            public long getBytesWritten() {
                return 1024L * 1024L;
            }

            @Override
            public long getContentSizeRemoved() {
                return 1024;
            }

            @Override
            public int getFlowFilesRemoved() {
                return 1;
            }

            @Override
            public long getProcessingNanoseconds() {
                return 234782;
            }

            @Override
            public long getCpuNanoseconds() {
                return 0;
            }

            @Override
            public long getContentReadNanoseconds() {
                return 0;
            }

            @Override
            public long getContentWriteNanoseconds() {
                return 0;
            }

            @Override
            public long getSessionCommitNanoseconds() {
                return 0;
            }

            @Override
            public long getGargeCollectionMillis() {
                return 0;
            }

            @Override
            public int getInvocations() {
                return 1;
            }

            @Override
            public long getAggregateLineageMillis() {
                return 783L;
            }

            @Override
            public long getAverageLineageMillis() {
                return getAggregateLineageMillis() / (getFlowFilesRemoved() + getFlowFilesOut());
            }

            @Override
            public int getFlowFilesReceived() {
                return 0;
            }

            @Override
            public long getBytesReceived() {
                return 0;
            }

            @Override
            public int getFlowFilesSent() {
                return 0;
            }

            @Override
            public long getBytesSent() {
                return 0;
            }

            @Override
            public Map<String, Long> getCounters() {
                return Collections.emptyMap();
            }
        };
    }
}
