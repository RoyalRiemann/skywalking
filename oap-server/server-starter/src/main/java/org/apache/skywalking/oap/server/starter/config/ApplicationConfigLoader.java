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

package org.apache.skywalking.oap.server.starter.config;

import java.io.FileNotFoundException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.oap.server.library.util.PropertyPlaceholderHelper;
import org.apache.skywalking.oap.server.library.module.ApplicationConfiguration;
import org.apache.skywalking.oap.server.library.module.ProviderNotFoundException;
import org.apache.skywalking.oap.server.library.util.CollectionUtils;
import org.apache.skywalking.oap.server.library.util.ResourceUtils;
import org.yaml.snakeyaml.Yaml;

/**
 * Initialize collector settings with following sources. Use application.yml as primary setting, and fix missing setting
 * by default settings in application-default.yml.
 * <p>
 * At last, override setting by system.properties and system.envs if the key matches moduleName.provideName.settingKey.
 */
@Slf4j
public class ApplicationConfigLoader implements ConfigLoader<ApplicationConfiguration> {
    private static final String DISABLE_SELECTOR = "-";
    private static final String SELECTOR = "selector";

    private final Yaml yaml = new Yaml();

    @Override
    public ApplicationConfiguration load() throws ConfigFileNotFoundException {
        ApplicationConfiguration configuration = new ApplicationConfiguration();
        this.loadConfig(configuration);//读取application.yaml配置，写入到applicationConfiguration里面
        this.overrideConfigBySystemEnv(configuration);
        return configuration;
    }

    @SuppressWarnings("unchecked")
    private void loadConfig(ApplicationConfiguration configuration) throws ConfigFileNotFoundException {
        try {
            //本来结构就是三层的，eg.key-core,map为selector:value|default:value.
            Reader applicationReader = ResourceUtils.read("application.yml");
            Map<String, Map<String, Object>> moduleConfig = yaml.loadAs(applicationReader, Map.class);
            if (CollectionUtils.isNotEmpty(moduleConfig)) {
                selectConfig(moduleConfig);
                moduleConfig.forEach((moduleName, providerConfig) -> {
                    //说明至少有一个selector
                    if (providerConfig.size() > 0) {
                        log.info("Get a module define from application.yml, module name: {}", moduleName);
                        //将模块加入到modules中
                        ApplicationConfiguration.ModuleConfiguration moduleConfiguration = configuration.addModule(
                            moduleName);
                        //循环provier里面的参数
                        providerConfig.forEach((providerName, config) -> {
                            log.info(
                                "Get a provider define belong to {} module, provider name: {}", moduleName,
                                providerName
                            );
                            final Map<String, ?> propertiesConfig = (Map<String, ?>) config;
                            final Properties properties = new Properties();
                            if (propertiesConfig != null) {
                                propertiesConfig.forEach((propertyName, propertyValue) -> {
                                    //map还是string
                                    if (propertyValue instanceof Map) {
                                        Properties subProperties = new Properties();
                                        ((Map) propertyValue).forEach((key, value) -> {
                                            subProperties.put(key, value);
                                            replacePropertyAndLog(key, value, subProperties, providerName);
                                        });
                                        properties.put(propertyName, subProperties);
                                    } else {
                                        properties.put(propertyName, propertyValue);
                                        replacePropertyAndLog(propertyName, propertyValue, properties, providerName);
                                    }
                                });
                            }
                            //add provider
                            moduleConfiguration.addProviderConfiguration(providerName, properties);
                        });
                    } else {
                        log.warn(
                            "Get a module define from application.yml, but no provider define, use default, module name: {}",
                            moduleName
                        );
                    }
                });
            }
        } catch (FileNotFoundException e) {
            throw new ConfigFileNotFoundException(e.getMessage(), e);
        }
    }

    private void replacePropertyAndLog(final Object propertyName, final Object propertyValue, final Properties target,
                                       final Object providerName) {
        final String valueString = PropertyPlaceholderHelper.INSTANCE
            .replacePlaceholders(propertyValue + "", target);
        if (valueString != null) {
            if (valueString.trim().length() == 0) {
                target.replace(propertyName, valueString);
                log.info("Provider={} config={} has been set as an empty string", providerName, propertyName);
            } else {
                // Use YAML to do data type conversion.
                final Object replaceValue = convertValueString(valueString);
                if (replaceValue != null) {
                    target.replace(propertyName, replaceValue);
                    log.info(
                        "Provider={} config={} has been set as {}",
                        providerName,
                        propertyName,
                        replaceValue.toString()
                    );
                }
            }
        }
    }

    private Object convertValueString(String valueString) {
        try {
            Object replaceValue = yaml.load(valueString);
            if (replaceValue instanceof String || replaceValue instanceof Integer || replaceValue instanceof Long || replaceValue instanceof Boolean || replaceValue instanceof ArrayList) {
                return replaceValue;
            } else {
                return valueString;
            }
        } catch (Exception e) {
            log.warn("yaml convert value type error, use origin values string. valueString={}", valueString, e);
            return valueString;
        }
    }

    private void overrideConfigBySystemEnv(ApplicationConfiguration configuration) {
        for (Map.Entry<Object, Object> prop : System.getProperties().entrySet()) {
            overrideModuleSettings(configuration, prop.getKey().toString(), prop.getValue().toString());
        }
    }

    private void selectConfig(final Map<String, Map<String, Object>> moduleConfiguration) {
        Iterator<Map.Entry<String, Map<String, Object>>> moduleIterator = moduleConfiguration.entrySet().iterator();
        while (moduleIterator.hasNext()) {
            Map.Entry<String, Map<String, Object>> entry = moduleIterator.next();
            final String moduleName = entry.getKey();
            final Map<String, Object> providerConfig = entry.getValue();
            //如果配置中没有selector，则说明不需要continue
            if (!providerConfig.containsKey(SELECTOR)) {
                continue;
            }
            final String selector = (String) providerConfig.get(SELECTOR);
            //找到selector对应的属性
            final String resolvedSelector = PropertyPlaceholderHelper.INSTANCE.replacePlaceholders(
                selector, System.getProperties()
            );
            //移除所有不属于selector的key，包括如果这个参数时"-"，也可以进行移除
            providerConfig.entrySet().removeIf(e -> !resolvedSelector.equals(e.getKey()));

            if (!providerConfig.isEmpty()) {
                continue;
            }

            if (!DISABLE_SELECTOR.equals(resolvedSelector)) {
                throw new ProviderNotFoundException(
                    "no provider found for module " + moduleName + ", " +
                        "if you're sure it's not required module and want to remove it, " +
                        "set the selector to -"
                );
            }

            // now the module can be safely removed
            moduleIterator.remove();
            log.info("Remove module {} without any provider", moduleName);
        }
    }

    private void overrideModuleSettings(ApplicationConfiguration configuration, String key, String value) {
        int moduleAndConfigSeparator = key.indexOf('.');
        if (moduleAndConfigSeparator <= 0) {
            return;
        }
        String moduleName = key.substring(0, moduleAndConfigSeparator);
        String providerSettingSubKey = key.substring(moduleAndConfigSeparator + 1);
        ApplicationConfiguration.ModuleConfiguration moduleConfiguration = configuration.getModuleConfiguration(
            moduleName);
        if (moduleConfiguration == null) {
            return;
        }
        int providerAndConfigSeparator = providerSettingSubKey.indexOf('.');
        if (providerAndConfigSeparator <= 0) {
            return;
        }
        String providerName = providerSettingSubKey.substring(0, providerAndConfigSeparator);
        String settingKey = providerSettingSubKey.substring(providerAndConfigSeparator + 1);
        if (!moduleConfiguration.has(providerName)) {
            return;
        }
        Properties providerSettings = moduleConfiguration.getProviderConfiguration(providerName);
        if (!providerSettings.containsKey(settingKey)) {
            return;
        }
        Object originValue = providerSettings.get(settingKey);
        Class<?> type = originValue.getClass();
        if (type.equals(int.class) || type.equals(Integer.class))
            providerSettings.put(settingKey, Integer.valueOf(value));
        else if (type.equals(String.class))
            providerSettings.put(settingKey, value);
        else if (type.equals(long.class) || type.equals(Long.class))
            providerSettings.put(settingKey, Long.valueOf(value));
        else if (type.equals(boolean.class) || type.equals(Boolean.class)) {
            providerSettings.put(settingKey, Boolean.valueOf(value));
        } else {
            return;
        }

        log.info(
            "The setting has been override by key: {}, value: {}, in {} provider of {} module through {}", settingKey,
            value, providerName, moduleName, "System.properties"
        );
    }
}
