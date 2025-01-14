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
 *
 */

package org.apache.skywalking.oap.server.core.analysis.worker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.library.datacarrier.DataCarrier;
import org.apache.skywalking.oap.server.library.datacarrier.consumer.IConsumer;
import org.apache.skywalking.oap.server.core.analysis.data.LimitedSizeBufferedData;
import org.apache.skywalking.oap.server.core.analysis.data.ReadWriteSafeCache;
import org.apache.skywalking.oap.server.core.analysis.topn.TopN;
import org.apache.skywalking.oap.server.core.storage.IRecordDAO;
import org.apache.skywalking.oap.server.core.storage.model.Model;
import org.apache.skywalking.oap.server.library.client.request.PrepareRequest;
import org.apache.skywalking.oap.server.library.module.ModuleDefineHolder;

/**
 * Top N worker is a persistence worker. Cache and order the data, flush in longer period.
 */
@Slf4j
public class TopNWorker extends PersistenceWorker<TopN> {
    private final IRecordDAO recordDAO;
    private final Model model;
    private final DataCarrier<TopN> dataCarrier;
    private long reportPeriod; //报告期,也就是说这么多时间才能用
    private volatile long lastReportTimestamp;

    TopNWorker(ModuleDefineHolder moduleDefineHolder, Model model, int topNSize, long reportPeriod,
               IRecordDAO recordDAO) {
        super(
            moduleDefineHolder,
            new ReadWriteSafeCache<>(new LimitedSizeBufferedData<>(topNSize), new LimitedSizeBufferedData<>(topNSize))
        );
        this.recordDAO = recordDAO;
        this.model = model;
        this.dataCarrier = new DataCarrier<>("TopNWorker", 1, 1000);
        this.dataCarrier.consume(new TopNWorker.TopNConsumer(), 1);
        this.lastReportTimestamp = System.currentTimeMillis();
        // Top N persistent works per 10 minutes default.
        this.reportPeriod = reportPeriod;
    }

    /**
     * Force overriding the parent buildBatchRequests. Use its own report period.
     */
    @Override
    public List<PrepareRequest> buildBatchRequests() {
        long now = System.currentTimeMillis();
        //没过报告期就返回空list
        if (now - lastReportTimestamp <= reportPeriod) {
            // Only do report in its own report period.
            return Collections.EMPTY_LIST;
        }
        lastReportTimestamp = now;

        final List<TopN> lastCollection = getCache().read();

        //read出来的数据写入持久化框架里面去，一堆一堆的insert语句
        List<PrepareRequest> prepareRequests = new ArrayList<>(lastCollection.size());
        lastCollection.forEach(record -> {
            try {
                prepareRequests.add(recordDAO.prepareBatchInsert(model, record));
            } catch (Throwable t) {
                log.error(t.getMessage(), t);
            }
        });
        return prepareRequests;
    }

    /**
     * This method used to clear the expired cache, but TopN is not following it.
     * 这个方法是用来清除过期缓存
     */
    @Override
    public void endOfRound() {
    }

    @Override
    public void in(TopN n) {
        dataCarrier.produce(n);
    }

    private class TopNConsumer implements IConsumer<TopN> {
        @Override
        public void init(final Properties properties) {
        }

        @Override
        public void consume(List<TopN> data) {
            TopNWorker.this.onWork(data);
        }

        @Override
        public void onError(List<TopN> data, Throwable t) {
            log.error(t.getMessage(), t);
        }

        @Override
        public void onExit() {

        }
    }
}
