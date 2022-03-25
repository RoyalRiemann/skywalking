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

package org.apache.skywalking.oap.server.core;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.apache.skywalking.oap.server.core.source.ScopeDefaultColumn;
import org.apache.skywalking.oap.server.library.module.ModuleConfig;

@Getter
public class CoreModuleConfig extends ModuleConfig {
    //默认聚合
    private String role = "Mixed";
    private String namespace;
    private String restHost;
    private int restPort;
    private String restContextPath;
    //RESTFull参数
    private int restMinThreads = 1;
    private int restMaxThreads = 200;
    private long restIdleTimeOut = 30000;
    private int restAcceptorPriorityDelta = 0;
    private int restAcceptQueueSize = 0;

    //GRPC参数
    private String gRPCHost;
    private int gRPCPort;
    private boolean gRPCSslEnabled = false;
    private String gRPCSslKeyPath;
    private String gRPCSslCertChainPath;
    private String gRPCSslTrustedCAPath;
    private int maxConcurrentCallsPerConnection;
    private int maxMessageSize;
    private int topNReportPeriod;
    /**
     * The period of L1 aggregation flush. Unit is ms.
     * L1聚合期
     */
    private long l1FlushPeriod = 500;
    /**
     * Enable database flush session.
     */
    private boolean enableDatabaseSession;
    /**
     * The threshold of session time. Unit is ms. Default value is 70s.
     */
    private long storageSessionTimeout = 70_000;

    //采样
    private final List<String> downsampling;
    /**
     * The period of doing data persistence. Unit is second.
     * 持久化时间
     */
    @Setter
    private long persistentPeriod = 25;

    private boolean enableDataKeeperExecutor = true;

    private int dataKeeperExecutePeriod = 5;
    /**
     * The time to live of all metrics data. Unit is day.
     * 所有的指标数据留存时间
     */

    private int metricsDataTTL = 3;
    /**
     * The time to live of all record data, including tracing. Unit is Day.
     */

    private int recordDataTTL = 7;

    private int gRPCThreadPoolSize;

    private int gRPCThreadPoolQueueSize;
    /**
     * Timeout for cluster internal communication, in seconds.
     * 远程超时
     */

    private int remoteTimeout = 20;
    /**
     * The size of network address alias.
     * 网络地址的大小
     */
    private long maxSizeOfNetworkAddressAlias = 1_000_000L;
    /**
     * Following are cache setting for none stream(s)
     * 配置任务最大值
     */
    private long maxSizeOfProfileTask = 10_000L;
    /**
     * Analyze profile snapshots paging size.
     * 分析快照文件分页大小
     */
    private int maxPageSizeOfQueryProfileSnapshot = 500;
    /**
     * Analyze profile snapshots max size.
     * 分析快照文件最大值
     */
    private int maxSizeOfAnalyzeProfileSnapshot = 12000;
    /**
     * Extra model column are the column defined by {@link ScopeDefaultColumn.DefinedByField#requireDynamicActive()} ==
     * true. These columns of model are not required logically in aggregation or further query, and it will cause more
     * load for memory, network of OAP and storage.
     *
     * But, being activated, user could see the name in the storage entities, which make users easier to use 3rd party
     * tool, such as Kibana->ES, to query the data by themselves.
     * 本身自己不需要的列，但是可以方便第三方插件
     */
    private boolean activeExtraModelColumns = false;
    /**
     * The max length of the service name.
     */
    private int serviceNameMaxLength = 70;
    /**
     * The max length of the service instance name.
     */
    private int instanceNameMaxLength = 70;
    /**
     * The max length of the endpoint name.
     * 终端长度
     *
     * <p>NOTICE</p>
     * In the current practice, we don't recommend the length over 190.
     */
    private int endpointNameMaxLength = 150;
    /**
     * Define the set of span tag keys, which should be searchable through the GraphQL.
     * 设置默认的key
     *
     * @since 8.2.0
     */
    @Setter
    @Getter
    private String searchableTracesTags = DEFAULT_SEARCHABLE_TAG_KEYS;
    /**
     * Define the set of logs tag keys, which should be searchable through the GraphQL.
     *
     * @since 8.4.0
     */
    @Setter
    @Getter
    private String searchableLogsTags = "";
    /**
     * Define the set of Alarm tag keys, which should be searchable through the GraphQL.
     *
     * @since 8.6.0
     */
    @Setter
    @Getter
    private String searchableAlarmTags = "";

    /**
     * The number of threads used to prepare metrics data to the storage.
     *
     * @since 8.7.0
     */
    @Setter
    @Getter
    private int prepareThreads = 2;

    @Getter
    @Setter
    private boolean enableEndpointNameGroupingByOpenapi = true;

    /**
     * The maximum size in bytes allowed for request headers.
     * 请求头钟的最大字节数
     * Use -1 to disable it.
     */
    private int httpMaxRequestHeaderSize = 8192;

    public CoreModuleConfig() {
        this.downsampling = new ArrayList<>();
    }

    /**
     * OAP server could work in different roles.
     */
    public enum Role {
        /**
         * Default role. OAP works as the {@link #Receiver} and {@link #Aggregator}
         * 既能分析又能聚合
         */
        Mixed,
        /**
         * Receiver mode OAP open the service to the agents, analysis and aggregate the results and forward the results
         * to {@link #Mixed} and {@link #Aggregator} roles OAP. The only exception is for {@link
         * org.apache.skywalking.oap.server.core.analysis.record.Record}, they don't require 2nd round distributed
         * aggregation, is being pushed into the storage from the receiver OAP directly.
         * 数据接收分析的
         */
        Receiver,
        /**
         * Aggregator mode OAP receives data from {@link #Mixed} and {@link #Aggregator} OAP nodes, and do 2nd round
         * aggregation. Then save the final result to the storage.
         * 数据二次聚合
         */
        Aggregator;

        public static Role fromName(String name) {
            for (Role role : Role.values()) {
                if (role.name().equalsIgnoreCase(name)) {
                    return role;
                }
            }
            return Mixed;
        }
    }

    /**
     * SkyWalking Java Agent provides the recommended tag keys for other language agents or SDKs. This field declare the
     * recommended keys should be searchable.
     */
    private static final String DEFAULT_SEARCHABLE_TAG_KEYS = String.join(
        Const.COMMA,
        "http.method",
        "status_code",
        "db.type",
        "db.instance",
        "mq.queue",
        "mq.topic",
        "mq.broker"
    );
}
