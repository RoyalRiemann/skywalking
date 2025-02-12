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

package org.apache.skywalking.oap.server.core.storage.model;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.library.util.StringUtil;
import org.apache.skywalking.oap.server.core.analysis.FunctionCategory;
import org.apache.skywalking.oap.server.core.source.DefaultScopeDefine;
import org.apache.skywalking.oap.server.core.storage.StorageException;
import org.apache.skywalking.oap.server.core.storage.annotation.Column;
import org.apache.skywalking.oap.server.core.storage.annotation.MultipleQueryUnifiedIndex;
import org.apache.skywalking.oap.server.core.storage.annotation.QueryUnifiedIndex;
import org.apache.skywalking.oap.server.core.storage.annotation.Storage;
import org.apache.skywalking.oap.server.core.storage.annotation.SuperDataset;
import org.apache.skywalking.oap.server.core.storage.annotation.ValueColumnMetadata;

/**
 * StorageModels manages all models detected by the core.
 */
@Slf4j
public class StorageModels implements IModelManager, ModelCreator, ModelManipulator {
    private final List<Model> models;//模型列表
    private final HashMap<String, String> columnNameOverrideRule; //重写列？
    private final List<CreatingListener> listeners;//监听，主要是存储初始化的时候创建监听

    public StorageModels() {
        this.models = new ArrayList<>();
        this.columnNameOverrideRule = new HashMap<>();
        this.listeners = new ArrayList<>();
    }

    @Override
    public Model add(Class<?> aClass, int scopeId, Storage storage, boolean record) throws StorageException {
        // Check this scope id is valid.
        DefaultScopeDefine.nameOf(scopeId);

        List<ModelColumn> modelColumns = new ArrayList<>();
        List<ExtraQueryIndex> extraQueryIndices = new ArrayList<>();
        retrieval(aClass, storage.getModelName(), modelColumns, extraQueryIndices, scopeId);

        Model model = new Model(
            storage.getModelName(), modelColumns, extraQueryIndices, scopeId,
            storage.getDownsampling(), record,
            isSuperDatasetModel(aClass),
            FunctionCategory.uniqueFunctionName(aClass),
            storage.isTimeRelativeID()
        );

        this.followColumnNameRules(model);
        models.add(model);

        for (final CreatingListener listener : listeners) {
            listener.whenCreating(model);
        }
        return model;
    }

    private boolean isSuperDatasetModel(Class<?> aClass) {
        return aClass.isAnnotationPresent(SuperDataset.class);
    }

    /**
     * CreatingListener listener could react when {@link #add(Class, int, Storage, boolean)} model happens. Also, the
     * added models are being notified in this add operation.
     */
    @Override
    public void addModelListener(final CreatingListener listener) throws StorageException {
        listeners.add(listener);
        for (Model model : models) {
            listener.whenCreating(model);
        }
    }

    /**
     * Read model column metadata based on the class level definition.
     * 恢复、检索
     */
    private void retrieval(final Class<?> clazz,
                           final String modelName,
                           final List<ModelColumn> modelColumns,
                           final List<ExtraQueryIndex> extraQueryIndices,
                           final int scopeId) {
        if (log.isDebugEnabled()) {
            log.debug("Analysis {} to generate Model.", clazz.getName());
        }

        Field[] fields = clazz.getDeclaredFields();

        for (Field field : fields) {
            if (field.isAnnotationPresent(Column.class)) {
                Column column = field.getAnnotation(Column.class);
                // Use the column#length as the default column length, as read the system env as the override mechanism.
                // Log the error but don't block the startup sequence.
                int columnLength = column.length();
                final String lengthEnvVariable = column.lengthEnvVariable();
                if (StringUtil.isNotEmpty(lengthEnvVariable)) {
                    //优先从环境变量中取字段长度
                    final String envValue = System.getenv(lengthEnvVariable);
                    if (StringUtil.isNotEmpty(envValue)) {
                        try {
                            columnLength = Integer.parseInt(envValue);
                        } catch (NumberFormatException e) {
                            log.error("Model [{}] Column [{}], illegal value {} of column length from system env [{}]",
                                      modelName, column.columnName(), envValue, lengthEnvVariable
                            );
                        }
                    }
                }
                modelColumns.add(
                    new ModelColumn(
                        new ColumnName(modelName, column.columnName()), field.getType(), field.getGenericType(),
                        column.matchQuery(), column.storageOnly(), column.dataType().isValue(), columnLength,
                        column.analyzer()
                    ));
                if (log.isDebugEnabled()) {
                    log.debug("The field named {} with the {} type", column.columnName(), field.getType());
                }
                if (column.dataType().isValue()) {
                    ValueColumnMetadata.INSTANCE.putIfAbsent(
                        modelName, column.columnName(), column.dataType(), column.function(),
                        column.defaultValue(), scopeId
                    );
                }

                //索引定义
                List<QueryUnifiedIndex> indexDefinitions = new ArrayList<>();
                if (field.isAnnotationPresent(QueryUnifiedIndex.class)) {
                    indexDefinitions.add(field.getAnnotation(QueryUnifiedIndex.class));
                }

                //多级索引定义
                if (field.isAnnotationPresent(MultipleQueryUnifiedIndex.class)) {
                    Collections.addAll(indexDefinitions, field.getAnnotation(MultipleQueryUnifiedIndex.class).value());
                }

                //查询字段为，列名和索引列
                indexDefinitions.forEach(indexDefinition -> extraQueryIndices.add(new ExtraQueryIndex(
                    column.columnName(),
                    indexDefinition.withColumns()
                )));
            }
        }

        //如果超类不为空，对超类也进行类似操作。
        if (Objects.nonNull(clazz.getSuperclass())) {
            retrieval(clazz.getSuperclass(), modelName, modelColumns, extraQueryIndices, scopeId);
        }
    }

    @Override
    public void overrideColumnName(String columnName, String newName) {
        columnNameOverrideRule.put(columnName, newName);
        models.forEach(this::followColumnNameRules);
        ValueColumnMetadata.INSTANCE.overrideColumnName(columnName, newName);
    }

    //重写列名
    private void followColumnNameRules(Model model) {
        columnNameOverrideRule.forEach((oldName, newName) -> {
            model.getColumns().forEach(column -> column.getColumnName().overrideName(oldName, newName));
            model.getExtraQueryIndices().forEach(extraQueryIndex -> extraQueryIndex.overrideName(oldName, newName));
        });
    }

    @Override
    public List<Model> allModels() {
        return models;
    }
}
