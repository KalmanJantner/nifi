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
package org.apache.nifi.provenance;

import org.apache.nifi.authorization.user.NiFiUser;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.provenance.search.Query;
import org.apache.nifi.provenance.search.QuerySubmission;
import org.apache.nifi.provenance.search.SearchTerms;
import org.apache.nifi.util.NiFiProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Timeout(value = 5)
public class TestVolatileProvenanceRepository {

    private VolatileProvenanceRepository repo;

    @BeforeAll
    public static void setup() {
        System.setProperty(NiFiProperties.PROPERTIES_FILE_PATH, TestVolatileProvenanceRepository.class.getResource("/nifi.properties").getFile());
    }

    @Test
    public void testAddAndGet() throws IOException {
        repo = new VolatileProvenanceRepository(NiFiProperties.createBasicNiFiProperties(null));

        final Map<String, String> attributes = new HashMap<>();
        attributes.put("abc", "xyz");
        attributes.put("xyz", "abc");
        attributes.put("uuid", UUID.randomUUID().toString());

        final ProvenanceEventBuilder builder = new StandardProvenanceEventRecord.Builder();
        builder.setEventTime(System.currentTimeMillis());
        builder.setEventType(ProvenanceEventType.RECEIVE);
        builder.setTransitUri("nifi://unit-test");
        builder.fromFlowFile(createFlowFile(3L, 3000L, attributes));
        builder.setComponentId("1234");
        builder.setComponentType("dummy processor");

        for (int i = 0; i < 10; i++) {
            repo.registerEvent(builder.build());
        }

        final List<ProvenanceEventRecord> retrieved = repo.getEvents(0L, 12);

        assertEquals(10, retrieved.size());
        for (int i = 0; i < 10; i++) {
            final ProvenanceEventRecord recovered = retrieved.get(i);
            assertEquals(i, recovered.getEventId());
            assertEquals("nifi://unit-test", recovered.getTransitUri());
            assertEquals(ProvenanceEventType.RECEIVE, recovered.getEventType());
            assertEquals(attributes, recovered.getAttributes());
        }
    }

    @Test
    public void testIndexAndCompressOnRolloverAndSubsequentSearchAsync() throws InterruptedException {
        repo = new VolatileProvenanceRepository(NiFiProperties.createBasicNiFiProperties(null));

        final String uuid = "00000000-0000-0000-0000-000000000000";
        final Map<String, String> attributes = new HashMap<>();
        attributes.put("abc", "xyz");
        attributes.put("xyz", "abc");
        attributes.put("filename", "file-" + uuid);

        final ProvenanceEventBuilder builder = new StandardProvenanceEventRecord.Builder();
        builder.setEventTime(System.currentTimeMillis());
        builder.setEventType(ProvenanceEventType.RECEIVE);
        builder.setTransitUri("nifi://unit-test");
        builder.fromFlowFile(createFlowFile(3L, 3000L, attributes));
        builder.setComponentId("1234");
        builder.setComponentType("dummy processor");

        for (int i = 0; i < 10; i++) {
            attributes.put("uuid", "00000000-0000-0000-0000-00000000000" + i);
            builder.fromFlowFile(createFlowFile(i, 3000L, attributes));
            repo.registerEvent(builder.build());
        }

        final Query query = new Query(UUID.randomUUID().toString());
        query.addSearchTerm(SearchTerms.newSearchTerm(SearchableFields.FlowFileUUID, "00000*", null));
        query.addSearchTerm(SearchTerms.newSearchTerm(SearchableFields.Filename, "file-*", null));
        query.addSearchTerm(SearchTerms.newSearchTerm(SearchableFields.ComponentID, "12?4", null));
        query.addSearchTerm(SearchTerms.newSearchTerm(SearchableFields.TransitURI, "nifi://*", null));
        query.setMaxResults(100);

        final QuerySubmission submission = repo.submitQuery(query, createUser());
        while (!submission.getResult().isFinished()) {
            Thread.sleep(100L);
        }

        assertEquals(10, submission.getResult().getMatchingEvents().size());
    }

    @Test
    public void testSearchForInverseValue() throws InterruptedException {
        repo = new VolatileProvenanceRepository(NiFiProperties.createBasicNiFiProperties(null));

        final Map<String, String> attributes = new HashMap<>();
        attributes.put("abc", "xyz");

        final ProvenanceEventBuilder builder = new StandardProvenanceEventRecord.Builder();
        builder.setEventTime(System.currentTimeMillis());
        builder.setEventType(ProvenanceEventType.RECEIVE);
        builder.setTransitUri("nifi://unit-test");
        builder.setComponentId("1234");
        builder.setComponentType("dummy processor");

        final String uuid_prefix = "00000000-0000-0000-0000-000000000000";

        for (int i = 0; i < 2; i++) {
            attributes.put("uuid", uuid_prefix + i);
            attributes.put("file.owner", "testOwner1");
            builder.fromFlowFile(createFlowFile(i, 3000L, attributes));
            repo.registerEvent(builder.build());
        }

        for (int i = 2; i < 10; i++) {
            attributes.put("uuid", uuid_prefix + i);
            attributes.put("file.owner", "testOwner2");
            builder.fromFlowFile(createFlowFile(i, 3000L, attributes));
            repo.registerEvent(builder.build());
        }

        final Query query = new Query(UUID.randomUUID().toString());
        query.addSearchTerm(SearchTerms.newSearchTerm(SearchableFields.ComponentID, "1234", null));
        query.addSearchTerm(SearchTerms.newSearchTerm(SearchableFields.newSearchableAttribute("abc"), "x?z", null));

        // set up query to search for event with uuid NOT ending in *000 and file.owner NOT testOwner2 and testAttribute NOT fitting pattern of testAttributeValu?
        query.addSearchTerm(SearchTerms.newSearchTerm(SearchableFields.FlowFileUUID, "*000", Boolean.TRUE));
        query.addSearchTerm(SearchTerms.newSearchTerm(SearchableFields.newSearchableAttribute("file.owner"), "testOwner2", Boolean.TRUE));
        query.addSearchTerm(SearchTerms.newSearchTerm(SearchableFields.newSearchableAttribute("testAttribute"), "testAttributeValu?", Boolean.TRUE));

        query.setMaxResults(100);

        final QuerySubmission submission = repo.submitQuery(query, createUser());
        while (!submission.getResult().isFinished()) {
            Thread.sleep(100L);
        }

        assertEquals(1, submission.getResult().getMatchingEvents().size());
        assertEquals("00000000-0000-0000-0000-0000000000001", submission.getResult().getMatchingEvents().get(0).getFlowFileUuid());
    }

    private FlowFile createFlowFile(final long id, final long fileSize, final Map<String, String> attributes) {
        final Map<String, String> attrCopy = new HashMap<>(attributes);

        return new FlowFile() {
            @Override
            public long getId() {
                return id;
            }

            @Override
            public long getEntryDate() {
                return System.currentTimeMillis();
            }

            @Override
            public long getLineageStartDate() {
                return System.currentTimeMillis();
            }

            @Override
            public boolean isPenalized() {
                return false;
            }

            @Override
            public String getAttribute(final String s) {
                return attrCopy.get(s);
            }

            @Override
            public long getSize() {
                return fileSize;
            }

            @Override
            public Map<String, String> getAttributes() {
                return attrCopy;
            }

            @Override
            public int compareTo(final FlowFile o) {
                return 0;
            }

            @Override
            public Long getLastQueueDate() {
                return System.currentTimeMillis();
            }

            @Override
            public long getLineageStartIndex() {
                return 0;
            }

            @Override
            public long getQueueDateIndex() {
                return 0;
            }
        };
    }

    private NiFiUser createUser() {
        return new NiFiUser() {
            @Override
            public String getIdentity() {
                return "unit-test";
            }

            @Override
            public Set<String> getGroups() {
                return Collections.emptySet();
            }

            @Override
            public Set<String> getIdentityProviderGroups() {
                return Collections.emptySet();
            }

            @Override
            public Set<String> getAllGroups() {
                return Collections.emptySet();
            }

            @Override
            public NiFiUser getChain() {
                return null;
            }

            @Override
            public boolean isAnonymous() {
                return false;
            }

            @Override
            public String getClientAddress() {
                return null;
            }
        };
    }
}
