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

package org.apache.skywalking.oap.server.library.module;

import java.lang.reflect.Field;
import java.util.Enumeration;
import java.util.Properties;
import java.util.ServiceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A module definition. module和provider基本上是一一对应的,他的service是操作的类
 */
public abstract class ModuleDefine implements ModuleProviderHolder {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModuleDefine.class);

    //对应的provider
    private ModuleProvider loadedProvider = null;

    //module的Name
    private final String name;

    public ModuleDefine(String name) {
        this.name = name;
    }

    /**
     * @return the module name
     *
     */
    public final String name() {
        return name;
    }

    /**
     * @return the {@link Service} provided by this module.
     * 这个模块提供的服务
     */
    public abstract Class[] services();

    /**
     * Run the prepare stage for the module, including finding all potential providers, and asking them to prepare.
     * 在prepare阶段，加载provider并初始化,这里基本上都是大对象
     * @param moduleManager of this module
     * @param configuration of this module
     * @throws ProviderNotFoundException when even don't find a single one providers.
     */
    void prepare(ModuleManager moduleManager, ApplicationConfiguration.ModuleConfiguration configuration,
        ServiceLoader<ModuleProvider> moduleProviderLoader) throws ProviderNotFoundException, ServiceNotProvidedException, ModuleConfigException, ModuleStartException {
        for (ModuleProvider provider : moduleProviderLoader) {
            if (!configuration.has(provider.name())) {
                continue;
            }

            if (provider.module().equals(getClass())) {
                if (loadedProvider == null) {
                    loadedProvider = provider;
                    loadedProvider.setManager(moduleManager);
                    loadedProvider.setModuleDefine(this);
                } else {
                    throw new DuplicateProviderException(this.name() + " module has one " + loadedProvider.name() + "[" + loadedProvider
                        .getClass()
                        .getName() + "] provider already, " + provider.name() + "[" + provider.getClass()
                                                                                              .getName() + "] is defined as 2nd provider.");
                }
            }

        }

        //没有provider也是会挂
        if (loadedProvider == null) {
            throw new ProviderNotFoundException(this.name() + " module no provider found.");
        }

        LOGGER.info("Prepare the {} provider in {} module.", loadedProvider.name(), this.name());
        try {
            copyProperties(loadedProvider.createConfigBeanIfAbsent(), configuration.getProviderConfiguration(loadedProvider
                .name()), this.name(), loadedProvider.name());
        } catch (IllegalAccessException e) {
            throw new ModuleConfigException(this.name() + " module config transport to config bean failure.", e);
        }
        loadedProvider.prepare();
    }

    private void copyProperties(ModuleConfig dest, Properties src, String moduleName,
        String providerName) throws IllegalAccessException {
        if (dest == null) {
            return;
        }
        Enumeration<?> propertyNames = src.propertyNames();
        while (propertyNames.hasMoreElements()) {
            String propertyName = (String) propertyNames.nextElement();
            Class<? extends ModuleConfig> destClass = dest.getClass();
            try {
                Field field = getDeclaredField(destClass, propertyName);
                field.setAccessible(true);
                field.set(dest, src.get(propertyName));
            } catch (NoSuchFieldException e) {
                LOGGER.warn(propertyName + " setting is not supported in " + providerName + " provider of " + moduleName + " module");
            }
        }
    }

    private Field getDeclaredField(Class<?> destClass, String fieldName) throws NoSuchFieldException {
        if (destClass != null) {
            Field[] fields = destClass.getDeclaredFields();
            for (Field field : fields) {
                if (field.getName().equals(fieldName)) {
                    return field;
                }
            }
            return getDeclaredField(destClass.getSuperclass(), fieldName);
        }

        throw new NoSuchFieldException();
    }

    @Override
    public final ModuleProvider provider() throws DuplicateProviderException, ProviderNotFoundException {
        if (loadedProvider == null) {
            throw new ProviderNotFoundException("There is no module provider in " + this.name() + " module!");
        }

        return loadedProvider;
    }
}
