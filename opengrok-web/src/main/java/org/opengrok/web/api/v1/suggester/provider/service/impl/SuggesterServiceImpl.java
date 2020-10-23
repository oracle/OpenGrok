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
 * Copyright (c) 2018 Oracle and/or its affiliates. All rights reserved.
 */
package org.opengrok.web.api.v1.suggester.provider.service.impl;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinition;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.BytesRef;
import org.opengrok.indexer.Metrics;
import org.opengrok.suggest.Suggester;
import org.opengrok.suggest.Suggester.NamedIndexDir;
import org.opengrok.suggest.Suggester.NamedIndexReader;
import org.opengrok.suggest.Suggester.Suggestions;
import org.opengrok.suggest.query.SuggesterQuery;
import org.opengrok.indexer.configuration.Project;
import org.opengrok.indexer.configuration.RuntimeEnvironment;
import org.opengrok.indexer.configuration.SuggesterConfig;
import org.opengrok.indexer.configuration.SuperIndexSearcher;
import org.opengrok.indexer.index.IndexDatabase;
import org.opengrok.indexer.logger.LoggerFactory;
import org.opengrok.web.api.v1.suggester.provider.service.SuggesterService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Implementation of {@link SuggesterService}.
 */
public class SuggesterServiceImpl implements SuggesterService {

    private static final Logger logger = LoggerFactory.getLogger(SuggesterServiceImpl.class);

    private static SuggesterServiceImpl instance;

    private Suggester suggester;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private ScheduledFuture<?> future;

    private final RuntimeEnvironment env = RuntimeEnvironment.getInstance();

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private SuggesterServiceImpl() {
    }

    public static SuggesterService getInstance() {
        if (instance == null) {
            instance = new SuggesterServiceImpl();
            instance.initSuggester();
        }
        return instance;
    }

    /** {@inheritDoc} */
    @Override
    public Suggestions getSuggestions(
            final Collection<String> projects,
            final SuggesterQuery suggesterQuery,
            final Query query
    ) {
        List<SuperIndexSearcher> superIndexSearchers = new LinkedList<>();
        lock.readLock().lock();
        try {
            if (suggester == null) {
                return new Suggestions(Collections.emptyList(), true);
            }
            List<NamedIndexReader> namedReaders = getNamedIndexReaders(projects, superIndexSearchers);

            return suggester.search(namedReaders, suggesterQuery, query);
        } finally {
            lock.readLock().unlock();

            for (SuperIndexSearcher s : superIndexSearchers) {
                try {
                    s.getSearcherManager().release(s);
                } catch (IOException e) {
                    logger.log(Level.WARNING, "Could not release " + s, e);
                }
            }
        }
    }

    private List<NamedIndexReader> getNamedIndexReaders(
            final Collection<String> projects,
            final List<SuperIndexSearcher> superIndexSearchers
    ) {
        if (env.isProjectsEnabled()) {
            return projects.stream().map(project -> {
                try {
                    SuperIndexSearcher searcher = env.getIndexSearcher(project);
                    superIndexSearchers.add(searcher);
                    return new NamedIndexReader(project, searcher.getIndexReader());
                } catch (IOException e) {
                    logger.log(Level.WARNING, "Could not get index reader for " + project, e);
                }
                return null;
            }).filter(Objects::nonNull).collect(Collectors.toList());
        } else {
            SuperIndexSearcher searcher;
            try {
                searcher = env.getIndexSearcher("");
                superIndexSearchers.add(searcher);
                return Collections.singletonList(new NamedIndexReader("", searcher.getIndexReader()));
            } catch (IOException e) {
                logger.log(Level.WARNING, "Could not get index reader", e);
            }
            return Collections.emptyList();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void refresh() {
        logger.log(Level.FINE, "Refreshing suggester for new configuration {0}", env.getSuggesterConfig());
        lock.writeLock().lock();
        try {
            // close and init from scratch because many things may have changed in the configuration
            // e.g. sourceRoot
            cancelScheduledRebuild();
            if (suggester != null) {
                suggester.close();
            }
            suggester = null;
            initSuggester();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void rebuild() {
        lock.readLock().lock();
        try {
            if (suggester == null) {
                logger.log(Level.FINE, "Cannot perform rebuild because suggester is not initialized");
                return;
            }
            suggester.rebuild(getAllProjectIndexDirs());
        } finally {
            lock.readLock().unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void rebuild(final String project) {
        Project p = env.getProjects().get(project);
        if (p == null) {
            logger.log(Level.WARNING, "Cannot rebuild suggester because project for name {0} was not found",
                    project);
            return;
        }
        if (!p.isIndexed()) {
            logger.log(Level.WARNING, "Cannot rebuild project {0} because it is not indexed yet", project);
            return;
        }
        lock.readLock().lock();
        try {
            if (suggester == null) {
                logger.log(Level.FINE, "Cannot rebuild {0} because suggester is not initialized", project);
                return;
            }
            suggester.rebuild(Collections.singleton(getNamedIndexDir(p)));
        } finally {
            lock.readLock().unlock();
        }
    }

    private NamedIndexDir getNamedIndexDir(final Project p) {
        Path indexPath = Paths.get(env.getDataRootPath(), IndexDatabase.INDEX_DIR, p.getPath());
        return new NamedIndexDir(p.getName(), indexPath);
    }

    /** {@inheritDoc} */
    @Override
    public void delete(final String project) {
        lock.readLock().lock();
        try {
            if (suggester == null) {
                logger.log(Level.FINE, "Cannot remove {0} because suggester is not initialized", project);
                return;
            }
            suggester.remove(Collections.singleton(project));
        } finally {
            lock.readLock().unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onSearch(final Iterable<String> projects, final Query q) {
        lock.readLock().lock();
        try {
            if (suggester == null) {
                logger.log(Level.FINEST, "Suggester not initialized, ignoring query {0} in onSearch event", q);
                return;
            }
            suggester.onSearch(projects, q);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean increaseSearchCount(final String project, final Term term, final int value) {
        lock.readLock().lock();
        try {
            if (suggester == null) {
                return false;
            }
            return suggester.increaseSearchCount(project, term, value, false);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean increaseSearchCount(final String project, final Term term, final int value, boolean waitForLock) {
        lock.readLock().lock();
        try {
            if (suggester == null) {
                return false;
            }
            return suggester.increaseSearchCount(project, term, value, waitForLock);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<Entry<BytesRef, Integer>> getPopularityData(
            final String project,
            final String field,
            final int page,
            final int pageSize
    ) {
        lock.readLock().lock();
        try {
            if (suggester == null) {
                logger.log(Level.FINE, "Cannot retrieve popularity data because suggester is not initialized");
                return Collections.emptyList();
            }
            return suggester.getSearchCounts(project, field, page, pageSize);
        } finally {
            lock.readLock().unlock();
        }
    }

    private void initSuggester() {
        SuggesterConfig suggesterConfig = env.getSuggesterConfig();
        if (!suggesterConfig.isEnabled()) {
            logger.log(Level.INFO, "Suggester disabled");
            return;
        }

        File suggesterDir = new File(env.getDataRootPath(), IndexDatabase.SUGGESTER_DIR);
        int rebuildParalleismLevel = (int) (((float) suggesterConfig.getRebuildThreadPoolSizeInNcpuPercent() / 100)
                * Runtime.getRuntime().availableProcessors());
        if (rebuildParalleismLevel == 0) {
            rebuildParalleismLevel = 1;
        }
        logger.log(Level.FINER, "Suggester rebuild parallelism level: " + rebuildParalleismLevel);
        suggester = new Suggester(suggesterDir,
                suggesterConfig.getMaxResults(),
                Duration.ofSeconds(suggesterConfig.getBuildTerminationTime()),
                suggesterConfig.isAllowMostPopular(),
                env.isProjectsEnabled(),
                suggesterConfig.getAllowedFields(),
                suggesterConfig.getTimeThreshold(),
                rebuildParalleismLevel,
                Metrics.getInstance().getRegistry());

        new Thread(() -> {
            suggester.init(getAllProjectIndexDirs());
            scheduleRebuild();
        }).start();
    }

    private Collection<NamedIndexDir> getAllProjectIndexDirs() {
        if (env.isProjectsEnabled()) {
            return env.getProjectList().stream()
                    .filter(Project::isIndexed)
                    .map(this::getNamedIndexDir)
                    .collect(Collectors.toList());
        } else {
            Path indexDir = Paths.get(env.getDataRootPath(), IndexDatabase.INDEX_DIR);
            return Collections.singleton(new NamedIndexDir("", indexDir));
        }
    }

    private Runnable getRebuildAllProjectsRunnable() {
        return () -> {
            lock.readLock().lock();
            try {
                if (suggester == null) {
                    return;
                }
                suggester.rebuild(getAllProjectIndexDirs());
                scheduleRebuild();
            } finally {
                lock.readLock().unlock();
            }
        };
    }

    private void scheduleRebuild() {
        cancelScheduledRebuild();

        if (!env.getSuggesterConfig().isAllowMostPopular()) { // no need to rebuild
            logger.log(Level.INFO, "Suggester rebuild not scheduled");
            return;
        }

        Duration timeToNextRebuild = getTimeToNextRebuild();
        if (timeToNextRebuild == null) {
            logger.log(Level.INFO, "Suggester rebuild not scheduled");
            return;
        }

        logger.log(Level.INFO, "Scheduling suggester rebuild in {0}", timeToNextRebuild);

        future = instance.scheduler.schedule(instance.getRebuildAllProjectsRunnable(), timeToNextRebuild.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private void cancelScheduledRebuild() {
        if (future != null && !future.isDone()) {
            future.cancel(false);
        }
    }

    private Duration getTimeToNextRebuild() {
        String cronDefinition = env.getSuggesterConfig().getRebuildCronConfig();
        if (cronDefinition == null) {
            return null;
        }

        ZonedDateTime now = ZonedDateTime.now();

        CronDefinition def = CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX);

        CronParser parser = new CronParser(def);

        ExecutionTime executionTime = ExecutionTime.forCron(parser.parse(cronDefinition));

        Optional<Duration> d = executionTime.timeToNextExecution(now);
        if (!d.isPresent()) {
            throw new IllegalStateException("Cannot determine time to next execution");
        }

        return d.get();
    }

    public void waitForRebuild(long timeout, TimeUnit unit) throws InterruptedException {
        suggester.waitForRebuild(timeout, unit);
    }

    public void waitForInit(long timeout, TimeUnit unit) throws InterruptedException {
        suggester.waitForInit(timeout, unit);
    }

    /** {@inheritDoc} */
    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            scheduler.shutdownNow();
            if (suggester != null) {
                suggester.close();
                suggester = null;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

}
