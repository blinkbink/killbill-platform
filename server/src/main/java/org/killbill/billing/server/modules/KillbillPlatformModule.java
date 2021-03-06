/*
 * Copyright 2010-2013 Ning, Inc.
 * Copyright 2014-2017 Groupon, Inc
 * Copyright 2014-2017 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.server.modules;

import javax.servlet.ServletContext;
import javax.sql.DataSource;

import org.killbill.billing.lifecycle.glue.BusModule;
import org.killbill.billing.lifecycle.glue.LifecycleModule;
import org.killbill.billing.osgi.glue.DefaultOSGIModule;
import org.killbill.billing.osgi.glue.OSGIDataSourceConfig;
import org.killbill.billing.platform.api.KillbillConfigSource;
import org.killbill.billing.platform.config.DefaultKillbillConfigSource;
import org.killbill.billing.platform.glue.KillBillPlatformModuleBase;
import org.killbill.billing.platform.glue.NotificationQueueModule;
import org.killbill.billing.platform.glue.ReferenceableDataSourceSpyProvider;
import org.killbill.billing.platform.jndi.JNDIManager;
import org.killbill.billing.server.config.KillbillServerConfig;
import org.killbill.clock.Clock;
import org.killbill.clock.ClockMock;
import org.killbill.clock.DefaultClock;
import org.killbill.commons.embeddeddb.EmbeddedDB;
import org.killbill.commons.jdbi.guice.DBIProvider;
import org.killbill.commons.jdbi.guice.DaoConfig;
import org.killbill.commons.jdbi.notification.DatabaseTransactionNotificationApi;
import org.killbill.commons.jdbi.transaction.NotificationTransactionHandler;
import org.killbill.commons.jdbi.transaction.RestartTransactionRunner;
import org.killbill.queue.DefaultQueueLifecycle;
import org.skife.config.ConfigSource;
import org.skife.config.ConfigurationObjectFactory;
import org.skife.jdbi.v2.IDBI;
import org.skife.jdbi.v2.TimingCollector;
import org.skife.jdbi.v2.tweak.TransactionHandler;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.jdbi.InstrumentedTimingCollector;
import com.google.inject.Injector;
import com.google.inject.Provider;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.google.inject.name.Names;

public class KillbillPlatformModule extends KillBillPlatformModuleBase {

    protected final ServletContext servletContext;

    protected final KillbillServerConfig serverConfig;

    protected DaoConfig daoConfig;
    protected EmbeddedDB mainEmbeddedDB;
    protected EmbeddedDB shiroEmbeddedDB;

    public KillbillPlatformModule(final ServletContext servletContext, final KillbillServerConfig serverConfig, final KillbillConfigSource configSource) {
        super(configSource);
        this.servletContext = servletContext;
        this.serverConfig = serverConfig;
    }

    @Override
    protected void configure() {
        configureClock();
        configureDao();
        configureConfig();
        configureEmbeddedDBs();
        configureLifecycle();
        configureBuses();
        configureNotificationQ();
        configureOSGI();
        configureJNDI();
    }

    protected void configureClock() {
        if (serverConfig.isTestModeEnabled()) {
            bind(Clock.class).to(ClockMock.class).asEagerSingleton();
        } else {
            bind(Clock.class).to(DefaultClock.class).asEagerSingleton();
        }
    }

    protected void configureDao() {
        daoConfig = new ConfigurationObjectFactory(skifeConfigSource).build(DaoConfig.class);
        bind(DaoConfig.class).toInstance(daoConfig);

        final DatabaseTransactionNotificationApi databaseTransactionNotificationApi = new DatabaseTransactionNotificationApi();
        bind(DatabaseTransactionNotificationApi.class).toInstance(databaseTransactionNotificationApi);

        final TransactionHandler notificationTransactionHandler = new NotificationTransactionHandler(databaseTransactionNotificationApi);
        final TransactionHandler ourSuperTunedTransactionHandler = new RestartTransactionRunner(notificationTransactionHandler);
        bind(TransactionHandler.class).toInstance(ourSuperTunedTransactionHandler);

        bind(IDBI.class).toProvider(DBIProvider.class).asEagerSingleton();
        bind(IDBI.class).annotatedWith(Names.named(DefaultQueueLifecycle.QUEUE_NAME)).toProvider(DBIProvider.class).asEagerSingleton();
    }

    // https://code.google.com/p/google-guice/issues/detail?id=627
    // https://github.com/google/guice/issues/627
    // https://github.com/google/guice/commit/6b7e7187bd074d3f2df9b04e17fa01e7592f295c
    @Provides
    @Singleton
    protected DataSource provideDataSourceInAComplicatedWayBecauseOf627(final Injector injector) {
        final Provider<DataSource> dataSourceSpyProvider = new ReferenceableDataSourceSpyProvider(daoConfig, mainEmbeddedDB, MAIN_DATA_SOURCE_ID);
        injector.injectMembers(dataSourceSpyProvider);
        return dataSourceSpyProvider.get();
    }

    @Provides
    @Named(SHIRO_DATA_SOURCE_ID_NAMED)
    @Singleton
    protected DataSource provideShiroDataSourceInAComplicatedWayBecauseOf627(final Injector injector) {
        final Provider<DataSource> dataSourceSpyProvider = new ReferenceableDataSourceSpyProvider(daoConfig, shiroEmbeddedDB, SHIRO_DATA_SOURCE_ID);
        injector.injectMembers(dataSourceSpyProvider);
        return dataSourceSpyProvider.get();
    }

    @Provides
    @Singleton
    protected TimingCollector provideTimingCollector(final MetricRegistry metricRegistry) {
        // Metrics / jDBI integration
        return new InstrumentedTimingCollector(metricRegistry);
    }

    protected void configureConfig() {
        bind(ConfigSource.class).toInstance(skifeConfigSource);
        bind(KillbillServerConfig.class).toInstance(serverConfig);
    }

    protected void configureEmbeddedDBs() {
        mainEmbeddedDB = new EmbeddedDBProvider(daoConfig).get();
        bind(EmbeddedDB.class).toInstance(mainEmbeddedDB);

        // Same database, but different pool: clone the object so the shutdown sequence cleans the pool properly
        shiroEmbeddedDB = new EmbeddedDBProvider(daoConfig).get();
        bind(EmbeddedDB.class).annotatedWith(Names.named(SHIRO_DATA_SOURCE_ID_NAMED)).toInstance(shiroEmbeddedDB);
    }

    protected void configureLifecycle() {
        install(new LifecycleModule());
    }

    protected void configureBuses() {
        install(new BusModule(BusModule.BusType.PERSISTENT, false, configSource));
        install(new BusModule(BusModule.BusType.PERSISTENT, true, configSource));
    }

    protected void configureNotificationQ() {
        install(new NotificationQueueModule(configSource));
    }

    protected void configureOSGI() {
        final OSGIDataSourceConfig osgiDataSourceConfig = new ConfigurationObjectFactory(skifeConfigSource).build(OSGIDataSourceConfig.class);
        final EmbeddedDB osgiEmbeddedDB = new EmbeddedDBProvider(osgiDataSourceConfig).get();
        bind(EmbeddedDB.class).annotatedWith(Names.named(OSGI_DATA_SOURCE_ID_NAMED)).toInstance(osgiEmbeddedDB);
        install(new DefaultOSGIModule(configSource, (DefaultKillbillConfigSource) configSource, osgiDataSourceConfig, osgiEmbeddedDB));
    }

    protected void configureJNDI() {
        bind(JNDIManager.class).asEagerSingleton();
    }
}
