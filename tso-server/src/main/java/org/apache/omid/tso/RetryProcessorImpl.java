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
package org.apache.omid.tso;

import com.google.common.base.Optional;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.lmax.disruptor.BatchEventProcessor;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.SequenceBarrier;
import com.lmax.disruptor.YieldingWaitStrategy;
import org.apache.commons.pool2.ObjectPool;
import org.apache.omid.committable.CommitTable;
import org.apache.omid.committable.CommitTable.CommitTimestamp;
import org.apache.omid.metrics.Meter;
import org.apache.omid.metrics.MetricsRegistry;

import org.jboss.netty.channel.Channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import static com.codahale.metrics.MetricRegistry.name;

/**
 * Manages the disambiguation of the retry requests that clients send when they did not received a response in the
 * specified timeout. It replies directly to the client with the outcome identified.
 */
class RetryProcessorImpl implements EventHandler<RetryProcessorImpl.RetryEvent>, RetryProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(RetryProcessor.class);

    // Disruptor chain stuff
    final ReplyProcessor replyProc;
    final RingBuffer<RetryEvent> retryRing;

    final CommitTable.Client commitTableClient;
    final ObjectPool<Batch> batchPool;

    // Metrics
    final Meter retriesMeter;

    @Inject
    RetryProcessorImpl(MetricsRegistry metrics,
                       CommitTable commitTable,
                       ReplyProcessor replyProc,
                       Panicker panicker,
                       ObjectPool<Batch> batchPool)
            throws InterruptedException, ExecutionException, IOException {

        this.commitTableClient = commitTable.getClient();
        this.replyProc = replyProc;
        this.batchPool = batchPool;

        retryRing = RingBuffer.createSingleProducer(RetryEvent.EVENT_FACTORY, 1 << 12, new YieldingWaitStrategy());
        SequenceBarrier retrySequenceBarrier = retryRing.newBarrier();
        BatchEventProcessor<RetryEvent> retryProcessor = new BatchEventProcessor<>(retryRing, retrySequenceBarrier, this);
        retryProcessor.setExceptionHandler(new FatalExceptionHandler(panicker));
        retryRing.addGatingSequences(retryProcessor.getSequence());

        ThreadFactory threadFactory = new ThreadFactoryBuilder().setNameFormat("retry-%d").build();
        ExecutorService retryExec = Executors.newSingleThreadExecutor(threadFactory);
        retryExec.submit(retryProcessor);

        // Metrics configuration
        retriesMeter = metrics.meter(name("tso", "retries"));

    }

    @Override
    public void onEvent(final RetryEvent event, final long sequence, final boolean endOfBatch)
            throws Exception {

        switch (event.getType()) {
            case COMMIT:
                // TODO: What happens when the IOException is thrown?
                handleCommitRetry(event);
                break;
            default:
                assert (false);
                break;
        }

    }

    private void handleCommitRetry(RetryEvent event) throws Exception {

        long startTimestamp = event.getStartTimestamp();
        try {
            Optional<CommitTimestamp> commitTimestamp = commitTableClient.getCommitTimestamp(startTimestamp).get();
            if(commitTimestamp.isPresent()) {
                if (commitTimestamp.get().isValid()) {
                    LOG.trace("Valid commit TS found in Commit Table. Replying Commit to the client...");
                    replyProc.sendCommitResponse(startTimestamp, commitTimestamp.get().getValue(), event.getChannel());
                } else {
                    LOG.trace("Invalid commit TS found in Commit Table. Replying Abort to the client...");
                    replyProc.sendAbortResponse(startTimestamp, event.getChannel());
                }
            } else {
                LOG.trace("No commit TS found in Commit Table. Replying Abort to the client..");
                replyProc.sendAbortResponse(startTimestamp, event.getChannel());
            }
        } catch (InterruptedException e) {
            LOG.error("Interrupted reading from commit table");
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            LOG.error("Error reading from commit table", e);
        }

        retriesMeter.mark();
    }

    @Override
    public void disambiguateRetryRequestHeuristically(long startTimestamp, Channel c, MonitoringContext monCtx) {
        long seq = retryRing.next();
        RetryEvent e = retryRing.get(seq);
        RetryEvent.makeCommitRetry(e, startTimestamp, c, monCtx);
        retryRing.publish(seq);
    }

    public final static class RetryEvent {

        enum Type {
            COMMIT
        }

        private Type type = null;

        private long startTimestamp = 0;
        private Channel channel = null;
        private MonitoringContext monCtx;

        static void makeCommitRetry(RetryEvent e, long startTimestamp, Channel c, MonitoringContext monCtx) {
            e.monCtx = monCtx;
            e.type = Type.COMMIT;
            e.startTimestamp = startTimestamp;
            e.channel = c;
        }

        MonitoringContext getMonCtx() {
            return monCtx;
        }

        Type getType() {
            return type;
        }

        Channel getChannel() {
            return channel;
        }

        long getStartTimestamp() {
            return startTimestamp;
        }

        public final static EventFactory<RetryEvent> EVENT_FACTORY = new EventFactory<RetryEvent>() {
            @Override
            public RetryEvent newInstance() {
                return new RetryEvent();
            }
        };

    }

}
