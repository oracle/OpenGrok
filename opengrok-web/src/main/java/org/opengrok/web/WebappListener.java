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
 * Copyright (c) 2007, 2021, Oracle and/or its affiliates. All rights reserved.
 * Portions Copyright (c) 2018, 2019, Chris Fraire <cfraire@me.com>.
 */
package org.opengrok.web;

import io.micrometer.core.instrument.Timer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import org.opengrok.indexer.Info;
import org.opengrok.indexer.Metrics;
import org.opengrok.indexer.analysis.AnalyzerGuru;
import org.opengrok.indexer.authorization.AuthorizationFramework;
import org.opengrok.indexer.configuration.CommandTimeoutType;
import org.opengrok.indexer.configuration.Project;
import org.opengrok.indexer.configuration.RuntimeEnvironment;
import org.opengrok.indexer.index.IndexCheck;
import org.opengrok.indexer.index.IndexDatabase;
import org.opengrok.indexer.logger.LoggerFactory;
import org.opengrok.indexer.web.SearchHelper;
import org.opengrok.web.api.v1.suggester.provider.service.SuggesterServiceFactory;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Initialize webapp context.
 *
 * @author Trond Norbye
 */
public final class WebappListener
        implements ServletContextListener, ServletRequestListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebappListener.class);
    private final Timer startupTimer = Timer.builder("webapp.startup.latency").
                description("web application startup latency").
                register(Metrics.getPrometheusRegistry());

    /**
     * {@inheritDoc}
     */
    @Override
    public void contextInitialized(final ServletContextEvent servletContextEvent) {
        Instant start = Instant.now();

        ServletContext context = servletContextEvent.getServletContext();
        RuntimeEnvironment env = RuntimeEnvironment.getInstance();

        LOGGER.log(Level.INFO, "Starting webapp with version {0} ({1})",
                    new Object[]{Info.getVersion(), Info.getRevision()});
        
        String config = context.getInitParameter("CONFIGURATION");
        if (config == null) {
            throw new Error("CONFIGURATION parameter missing in the web.xml file");
        } else {
            try {
                env.readConfiguration(new File(config), CommandTimeoutType.WEBAPP_START);
            } catch (IOException ex) {
                LOGGER.log(Level.WARNING, "Configuration error. Failed to read config file: ", ex);
            }
        }

        /*
         * Create a new instance of authorization framework. If the code above
         * (reading the configuration) failed then the plugin directory is
         * possibly {@code null} causing the framework to allow every request.
         */
        env.setAuthorizationFramework(new AuthorizationFramework(env.getPluginDirectory(), env.getPluginStack()));
        env.getAuthorizationFramework().reload();

        if (env.isWebappCtags() && !env.validateUniversalCtags()) {
            LOGGER.warning("Didn't find Universal Ctags for --webappCtags");
        }

        String pluginDirectory = env.getPluginDirectory();
        if (pluginDirectory != null && env.isAuthorizationWatchdog()) {
            env.watchDog.start(new File(pluginDirectory));
        }

        // Check index(es).
        check_index(env);

        env.startExpirationTimer();
        startupTimer.record(Duration.between(start, Instant.now()));
    }

    /**
     * Checks the index(es). If projects are enabled then each project with invalid index
     * is marked as not being indexed.
     * @param env runtime environment
     */
    private void check_index(RuntimeEnvironment env) {
        if (env.isProjectsEnabled()) {
            LOGGER.log(Level.FINE, "Checking indexes for all projects");
            Map<String, Project> projects = env.getProjects();
            File indexRoot = new File(env.getDataRootPath(), IndexDatabase.INDEX_DIR);
            if (indexRoot.exists()) {
                for (String projectName : projects.keySet()) {
                    try {
                        IndexCheck.checkDir(new File(indexRoot, projectName));
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING,
                                String.format("Project %s index check failed, marking as not indexed", projectName), e);
                        projects.get(projectName).setIndexed(false);
                    }
                }
            }
            LOGGER.log(Level.FINER, "Index check for all projects done");
        } else {
            LOGGER.log(Level.FINE, "Checking index");
            try {
                IndexCheck.checkDir(new File(env.getDataRootPath(), IndexDatabase.INDEX_DIR));
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "index check failed", e);
            }
            LOGGER.log(Level.FINER, "Index check done");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void contextDestroyed(final ServletContextEvent servletContextEvent) {
        RuntimeEnvironment env = RuntimeEnvironment.getInstance();
        env.getIndexerParallelizer().bounce();
        env.watchDog.stop();
        env.stopExpirationTimer();
        try {
            env.shutdownRevisionExecutor();
        } catch (InterruptedException e) {
            LOGGER.log(Level.WARNING, "Could not shutdown revision executor", e);
        }

        // need to explicitly close the suggester service because it might have scheduled rebuild which could prevent
        // the web application from closing
        SuggesterServiceFactory.getDefault().close();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void requestInitialized(ServletRequestEvent e) {
        // pass
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void requestDestroyed(ServletRequestEvent e) {
        PageConfig.cleanup(e.getServletRequest());
        SearchHelper sh = (SearchHelper) e.getServletRequest().getAttribute(SearchHelper.REQUEST_ATTR);
        if (sh != null) {
            sh.destroy();
        }

        AnalyzerGuru.returnAnalyzers();
    }
}
