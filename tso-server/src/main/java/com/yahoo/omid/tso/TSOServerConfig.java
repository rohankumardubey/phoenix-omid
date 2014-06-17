package com.yahoo.omid.tso;

import static com.yahoo.omid.tso.PersistenceProcessorImpl.DEFAULT_MAX_BATCH_SIZE;
import static com.yahoo.omid.tso.PersistenceProcessorImpl.TSO_MAX_BATCH_SIZE_KEY;
import static com.yahoo.omid.tso.RequestProcessorImpl.DEFAULT_MAX_ITEMS;
import static com.yahoo.omid.tso.RequestProcessorImpl.TSO_MAX_ITEMS_KEY;

import javax.inject.Named;
import javax.inject.Singleton;

import com.google.inject.Inject;

@Singleton
public class TSOServerConfig {

    private int maxBatchSize = DEFAULT_MAX_BATCH_SIZE;
    private int maxItems = DEFAULT_MAX_ITEMS;

    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    @Inject(optional=true)
    public void setMaxBatchSize(@Named(TSO_MAX_BATCH_SIZE_KEY) int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
    }

    public int getMaxItems() {
        return maxItems;
    }

    @Inject(optional=true)
    public void setMaxItems(@Named(TSO_MAX_ITEMS_KEY) int maxItems) {
        this.maxItems = maxItems;
    }

}
