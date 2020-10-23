/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License (the "License").
 * You may not use this file except in compliance with the License.
 *
 * See LICENSE.txt included in this distribution for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at LICENSE.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information: Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 */

/*
 * Copyright (c) 2020 Oracle and/or its affiliates. All rights reserved.
 */
package org.opengrok.indexer;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.graphite.GraphiteProtocol;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.micrometer.graphite.GraphiteConfig;
import io.micrometer.graphite.GraphiteMeterRegistry;
import io.micrometer.statsd.StatsdConfig;
import io.micrometer.statsd.StatsdMeterRegistry;
import io.micrometer.statsd.StatsdFlavor;
import org.opengrok.indexer.configuration.BaseGraphiteConfig;
import org.opengrok.indexer.configuration.BaseStatsdConfig;
import org.opengrok.indexer.configuration.MeterRegistryType;
import org.opengrok.indexer.configuration.RuntimeEnvironment;
import org.opengrok.indexer.index.Indexer;
import org.opengrok.indexer.logger.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Encapsulates logic of meter registry setup and handling.
 * Generally, the web application publishes metrics to Prometheus and the Indexer to StatsD.
 */
public final class Metrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(Metrics.class);

    private StatsdConfig getStatsdConfig(BaseStatsdConfig baseStatsdConfig) {
        return new StatsdConfig() {
            @Override
            public String get(String k) {
                return null;
            }

            @Override
            public StatsdFlavor flavor() {
                return baseStatsdConfig.getFlavor();
            }

            @Override
            public int port() {
                return baseStatsdConfig.getPort();
            }

            @Override
            public String host() {
                return baseStatsdConfig.getHost();
            }

            @Override
            public boolean buffered() {
                return true;
            }
        };
    }

    private GraphiteConfig getGraphiteConfig(BaseGraphiteConfig baseGraphiteConfig) {
        return new GraphiteConfig() {
            @Override
            public String get(String k) {
                return null;
            }

            @Override
            public int port() {
                return baseGraphiteConfig.getPort();
            }

            @Override
            public String host() {
                return baseGraphiteConfig.getHost();
            }

            @Override
            public GraphiteProtocol protocol() {
                return baseGraphiteConfig.getProtocol();
            }
        };
    }

    private PrometheusMeterRegistry prometheusRegistry;

    private MeterRegistry activeRegistry;

    private static final Metrics instance = new Metrics();

    private Metrics() {
    }

    /**
     * @return the only instance of Metrics
     */
    public static Metrics getInstance() {
        return instance;
    }

    /**
     * Configure meter registry.
     * @param type type of meter registry
     */
    public void configure(MeterRegistryType type) {
        switch (type) {
            case PROMETHEUS:
                LOGGER.log(Level.INFO, "configuring Prometheus registry with default config");
                prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
                activeRegistry = prometheusRegistry;
                break;
            case GRAPHITE:
                BaseGraphiteConfig baseGraphiteConfig = RuntimeEnvironment.getInstance().getBaseGraphiteConfig();
                if (baseGraphiteConfig.isEnabled()) {
                    LOGGER.log(Level.INFO, "configuring Graphite registry with {0}", baseGraphiteConfig.toString());
                    activeRegistry = new GraphiteMeterRegistry(getGraphiteConfig(baseGraphiteConfig), Clock.SYSTEM);
                }
                break;
            case STATSD:
                BaseStatsdConfig baseStatsdConfig = RuntimeEnvironment.getInstance().getBaseStatsdConfig();
                if (baseStatsdConfig.isEnabled()) {
                    LOGGER.log(Level.INFO, "configuring Statsd registry with {0}", baseStatsdConfig.toString());
                    activeRegistry = new StatsdMeterRegistry(getStatsdConfig(baseStatsdConfig), Clock.SYSTEM);
                }
                break;
            case NONE:
                activeRegistry = null;
                break;
            default:
                throw new IllegalArgumentException("unsupported registry type");
        }

        if (activeRegistry != null) {
            new ClassLoaderMetrics().bindTo(activeRegistry);
            new JvmMemoryMetrics().bindTo(activeRegistry);
            new JvmGcMetrics().bindTo(activeRegistry);
            new ProcessorMetrics().bindTo(activeRegistry);
            new JvmThreadMetrics().bindTo(activeRegistry);
        }
    }

    /**
     * Set common tag according to list of files.
     * @param subFiles list of files
     * @return Tag object or null
     */
    public Tag updateSubFiles(List<String> subFiles) {
        // Add tag for per-project reindex.
        Tag commonTag = null;
        if (activeRegistry != null && !subFiles.isEmpty()) {
            String projects = subFiles.stream().
                    map(s -> s.startsWith(Indexer.PATH_SEPARATOR_STRING) ? s.substring(1) : s).
                    collect(Collectors.joining(","));
            commonTag = Tag.of("projects", projects);
            LOGGER.log(Level.FINE, "updating active registry with common tag: {}", commonTag);
            activeRegistry.config().commonTags(Collections.singleton(commonTag));
        }

        return commonTag;
    }

    public PrometheusMeterRegistry getPrometheusRegistry() {
        return prometheusRegistry;
    }

    /**
     * Get registry based on running context.
     * @return MeterRegistry instance
     */
    public MeterRegistry getRegistry() {
        return activeRegistry;
    }
}
