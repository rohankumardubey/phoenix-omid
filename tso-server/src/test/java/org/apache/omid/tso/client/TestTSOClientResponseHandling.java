/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.omid.tso.client;

import org.apache.omid.tso.ProgrammableTSOServer;
import org.apache.omid.tso.ProgrammableTSOServer.AbortResponse;
import org.apache.omid.tso.ProgrammableTSOServer.CommitResponse;
import org.apache.omid.tso.ProgrammableTSOServer.TimestampResponse;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.ExecutionException;

import static org.testng.Assert.assertEquals;

public class TestTSOClientResponseHandling {

    private static final int TSO_PORT = 4321;
    private static final long START_TS = 1L;
    private static final long COMMIT_TS = 2L;

    private ProgrammableTSOServer tsoServer = new ProgrammableTSOServer(TSO_PORT);
    // Client under test
    private TSOClient tsoClient;

    @BeforeClass
    public void configureAndCreateClient() throws IOException, InterruptedException {

        OmidClientConfiguration tsoClientConf = new OmidClientConfiguration();
        tsoClientConf.setConnectionString("localhost:" + TSO_PORT);
        tsoClient = TSOClient.newInstance(tsoClientConf);
    }

    @BeforeMethod
    public void reset() {
        tsoServer.cleanResponses();
    }

    @Test
    public void testTimestampRequestReceivingASuccessfulResponse() throws Exception {
        // test request timestamp response returns a timestamp

        // Program the TSO to return an ad-hoc Timestamp response
        tsoServer.queueResponse(new TimestampResponse(START_TS));

        long startTS = tsoClient.getNewStartTimestamp().get();
        assertEquals(startTS, START_TS);
    }

    @Test
    public void testCommitRequestReceivingAnAbortResponse() throws Exception {
        // test commit request which is aborted on the server side
        // (e.g. due to conflicts with other transaction) throws an
        // execution exception with an AbortException as a cause

        // Program the TSO to return an Abort response
        tsoServer.queueResponse(new AbortResponse(START_TS));

        try {
            tsoClient.commit(START_TS, Collections.<CellId>emptySet()).get();
        } catch (ExecutionException ee) {
            assertEquals(ee.getCause().getClass(), AbortException.class);
        }
    }

    @Test
    public void testCommitRequestReceivingASuccessfulResponse() throws Exception {
        // test commit request which is successfully committed on the server
        // side returns a commit timestamp

        // Program the TSO to return an Commit response (with no required heuristic actions)
        tsoServer.queueResponse(new CommitResponse(START_TS, COMMIT_TS));

        long commitTS = tsoClient.commit(START_TS, Collections.<CellId>emptySet()).get();
        assertEquals(commitTS, COMMIT_TS);
    }

}
