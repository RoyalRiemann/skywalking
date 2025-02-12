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

package org.apache.skywalking.oap.server.core.storage;

import com.google.common.annotations.VisibleForTesting;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.core.CoreModuleConfig;
import org.apache.skywalking.oap.server.core.analysis.worker.MetricsStreamProcessor;
import org.apache.skywalking.oap.server.core.analysis.worker.PersistenceWorker;
import org.apache.skywalking.oap.server.core.analysis.worker.TopNStreamProcessor;
import org.apache.skywalking.oap.server.library.client.request.PrepareRequest;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.library.util.CollectionUtils;
import org.apache.skywalking.oap.server.library.util.RunnableWithExceptionProtection;
import org.apache.skywalking.oap.server.telemetry.TelemetryModule;
import org.apache.skywalking.oap.server.telemetry.api.CounterMetrics;
import org.apache.skywalking.oap.server.telemetry.api.HistogramMetrics;
import org.apache.skywalking.oap.server.telemetry.api.MetricsCreator;
import org.apache.skywalking.oap.server.telemetry.api.MetricsTag;

@Slf4j
public enum PersistenceTimer {
    INSTANCE;
    @VisibleForTesting
    boolean isStarted = false;
    private CounterMetrics errorCounter;
    private HistogramMetrics prepareLatency;
    private HistogramMetrics executeLatency;
    private HistogramMetrics allLatency;
    private ExecutorService prepareExecutorService;

    PersistenceTimer() {
    }

    public void start(ModuleManager moduleManager, CoreModuleConfig moduleConfig) {
        log.info("persistence timer start");
        IBatchDAO batchDAO =
            moduleManager.find(StorageModule.NAME).provider().getService(IBatchDAO.class);

        //指标分析
        MetricsCreator metricsCreator = moduleManager.find(TelemetryModule.NAME)
                                                     .provider()
                                                     .getService(MetricsCreator.class);
        //空指标
        errorCounter = metricsCreator.createCounter(
            "persistence_timer_bulk_error_count",
            "Error execution of the prepare stage in persistence timer",
            MetricsTag.EMPTY_KEY, MetricsTag.EMPTY_VALUE
        );
        //准备阶段直方图指标
        prepareLatency = metricsCreator.createHistogramMetric(
            "persistence_timer_bulk_prepare_latency",
            "Latency of the prepare stage in persistence timer",
            MetricsTag.EMPTY_KEY, MetricsTag.EMPTY_VALUE
        );
        //执行阶段的
        executeLatency = metricsCreator.createHistogramMetric(
            "persistence_timer_bulk_execute_latency",
            "Latency of the execute stage in persistence timer",
            MetricsTag.EMPTY_KEY, MetricsTag.EMPTY_VALUE
        );
        allLatency = metricsCreator.createHistogramMetric(
            "persistence_timer_bulk_all_latency", "Latency of the all stage in persistence timer",
            MetricsTag.EMPTY_KEY, MetricsTag.EMPTY_VALUE
        );

        prepareExecutorService = Executors.newFixedThreadPool(moduleConfig.getPrepareThreads());
        if (!isStarted) {
            Executors.newSingleThreadScheduledExecutor()
                     .scheduleWithFixedDelay(
                         new RunnableWithExceptionProtection(
                             () -> extractDataAndSave(batchDAO).join(),
                             t -> log.error("Extract data and save failure.", t)
                         ), 5, moduleConfig.getPersistentPeriod(), TimeUnit.SECONDS
                     );

            this.isStarted = true;
        }
    }

    //组装保存数据
    private CompletableFuture<Void> extractDataAndSave(IBatchDAO batchDAO) {
        if (log.isDebugEnabled()) {
            log.debug("Extract data and save");
        }

        long startTime = System.currentTimeMillis();

        HistogramMetrics.Timer allTimer = allLatency.createTimer();
        List<PersistenceWorker<? extends StorageData>> workers = new ArrayList<>();
        workers.addAll(TopNStreamProcessor.getInstance().getPersistentWorkers());
        workers.addAll(MetricsStreamProcessor.getInstance().getPersistentWorkers());

        final CompletableFuture<Void> future =
            CompletableFuture.allOf(workers.stream().map(worker -> {
                return CompletableFuture.runAsync(() -> {
                    List<PrepareRequest> innerPrepareRequests;
                    // Prepare stage
                    try (HistogramMetrics.Timer ignored = prepareLatency.createTimer()) {
                        if (log.isDebugEnabled()) {
                            log.debug(
                                "extract {} worker data and save",
                                worker.getClass().getName()
                            );
                        }

                        //内部准备请求,把相关的数据的sql准备起来
                        innerPrepareRequests = worker.buildBatchRequests();

                        //清除过期缓存
                        worker.endOfRound();
                    }

                    if (CollectionUtils.isEmpty(innerPrepareRequests)) {
                        return;
                    }

                    // Execution stage,持久化的执行步骤,延时计时器
                    HistogramMetrics.Timer executeLatencyTimer = executeLatency.createTimer();
                    batchDAO.flush(innerPrepareRequests)
                            .whenComplete(($1, $2) -> executeLatencyTimer.close());
                }, prepareExecutorService);
            }).toArray(CompletableFuture[]::new));
        future.whenComplete((unused, throwable) -> {
            allTimer.close();
            if (log.isDebugEnabled()) {
                log.debug(
                    "Batch persistence duration: {} ms",
                    System.currentTimeMillis() - startTime
                );
            }
            if (throwable != null) {
                errorCounter.inc();
                log.error(throwable.getMessage(), throwable);
            }
        });
        return future;
    }
}
