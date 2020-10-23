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
  * Copyright (c) 2006, 2020, Oracle and/or its affiliates. All rights reserved.
  * Portions Copyright (c) 2017-2020, Chris Fraire <cfraire@me.com>.
  */
package org.opengrok.indexer.configuration;

import static org.opengrok.indexer.configuration.Configuration.makeXMLStringAsConfiguration;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Response;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.NamedThreadFactory;
import org.opengrok.indexer.authorization.AuthorizationFramework;
import org.opengrok.indexer.authorization.AuthorizationStack;
import org.opengrok.indexer.history.HistoryGuru;
import org.opengrok.indexer.history.RepositoryInfo;
import org.opengrok.indexer.index.IndexDatabase;
import org.opengrok.indexer.index.IndexerParallelizer;
import org.opengrok.indexer.logger.LoggerFactory;
import org.opengrok.indexer.util.CloseableReentrantReadWriteLock;
import org.opengrok.indexer.util.CtagsUtil;
import org.opengrok.indexer.util.ForbiddenSymlinkException;
import org.opengrok.indexer.util.LazilyInstantiate;
import org.opengrok.indexer.util.PathUtils;
import org.opengrok.indexer.util.ResourceLock;
import org.opengrok.indexer.web.Prefix;
import org.opengrok.indexer.web.Util;
import org.opengrok.indexer.web.messages.Message;
import org.opengrok.indexer.web.messages.MessagesContainer;
import org.opengrok.indexer.web.messages.MessagesContainer.AcceptedMessage;

/**
 * The RuntimeEnvironment class is used as a placeholder for the current
 * configuration this execution context (classloader) is using.
 */
public final class RuntimeEnvironment {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeEnvironment.class);

    private static final String URL_PREFIX = "/source" + Prefix.SEARCH_R + "?";

    private Configuration configuration;
    private final CloseableReentrantReadWriteLock configLock;
    private final LazilyInstantiate<IndexerParallelizer> lzIndexerParallelizer;
    private final LazilyInstantiate<ExecutorService> lzSearchExecutor;
    private final LazilyInstantiate<ExecutorService> lzRevisionExecutor;
    private static final RuntimeEnvironment instance = new RuntimeEnvironment();

    private final Map<Project, List<RepositoryInfo>> repository_map = new ConcurrentHashMap<>();
    private final Map<String, SearcherManager> searcherManagerMap = new ConcurrentHashMap<>();

    private String configURI;
    IncludeFiles includeFiles = new IncludeFiles();
    private final MessagesContainer messagesContainer = new MessagesContainer();

    private static final IndexTimestamp indexTime = new IndexTimestamp();

    /**
     * Stores a transient value when
     * {@link #setCtags(java.lang.String)} is called -- i.e. the
     * value is not mediated to {@link Configuration}.
     */
    private String ctags;
    /**
     * Stores a transient value when
     * {@link #setMandoc(java.lang.String)} is called -- i.e. the
     * value is not mediated to {@link Configuration}.
     */
    private String mandoc;

    private transient File dtagsEftar = null;

    private transient volatile Boolean ctagsFound;
    private final transient Set<String> ctagsLanguages = new HashSet<>();

    public WatchDogService watchDog;

    public List<String> getSubFiles() {
        return subFiles;
    }

    private List<String> subFiles = new ArrayList<>();

    /**
     * Creates a new instance of RuntimeEnvironment. Private to ensure a
     * singleton anti-pattern.
     */
    private RuntimeEnvironment() {
        configuration = new Configuration();
        configLock = new CloseableReentrantReadWriteLock();
        watchDog = new WatchDogService();
        lzIndexerParallelizer = LazilyInstantiate.using(() ->
                new IndexerParallelizer(this));
        lzSearchExecutor = LazilyInstantiate.using(() -> newSearchExecutor());
        lzRevisionExecutor = LazilyInstantiate.using(() -> newRevisionExecutor());
    }

    // Instance of authorization framework and its lock.
    private AuthorizationFramework authFramework;
    private final Object authFrameworkLock = new Object();

    /** Gets the thread pool used for multi-project searches. */
    public ExecutorService getSearchExecutor() {
        return lzSearchExecutor.get();
    }

    private ExecutorService newSearchExecutor() {
        return Executors.newFixedThreadPool(
                this.getMaxSearchThreadCount(),
                new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = Executors.defaultThreadFactory().newThread(runnable);
                    thread.setName("search-" + thread.getId());
                    return thread;
                }
            });
    }

    public ExecutorService getRevisionExecutor() {
        return lzRevisionExecutor.get();
    }

    private ExecutorService newRevisionExecutor() {
        return Executors.newFixedThreadPool(this.getMaxRevisionThreadCount(),
                new NamedThreadFactory("get-revision"));
    }

    public void shutdownRevisionExecutor() throws InterruptedException {
        getRevisionExecutor().shutdownNow();
        getRevisionExecutor().awaitTermination(getIndexerCommandTimeout(), TimeUnit.SECONDS);
    }

    /**
     * Get the one and only instance of the RuntimeEnvironment.
     *
     * @return the one and only instance of the RuntimeEnvironment
     */
    public static RuntimeEnvironment getInstance() {
        return instance;
    }

    public IndexerParallelizer getIndexerParallelizer() {
        return lzIndexerParallelizer.get();
    }

    /**
     * Gets an instance associated to this environment.
     */
    public PathAccepter getPathAccepter() {
        return new PathAccepter(getIgnoredNames(), getIncludedNames());
    }

    private String getCanonicalPath(String s) {
        if (s == null) {
            return null;
        }
        try {
            File file = new File(s);
            if (!file.exists()) {
                return s;
            }
            return file.getCanonicalPath();
        } catch (IOException ex) {
            LOGGER.log(Level.SEVERE, "Failed to get canonical path", ex);
            return s;
        }
    }

    public int getScanningDepth() {
        return syncReadConfiguration(Configuration::getScanningDepth);
    }

    public void setScanningDepth(int scanningDepth) {
        syncWriteConfiguration(scanningDepth, Configuration::setScanningDepth);
    }

    public int getNestingMaximum() {
        return syncReadConfiguration(Configuration::getNestingMaximum);
    }

    public void setNestingMaximum(int nestingMaximum) {
        syncWriteConfiguration(nestingMaximum, Configuration::setNestingMaximum);
    }

    public int getCommandTimeout(CommandTimeoutType cmdType) {
        switch (cmdType) {
            case INDEXER:
                return getIndexerCommandTimeout();
            case INTERACTIVE:
                return getInteractiveCommandTimeout();
            case WEBAPP_START:
                return getWebappStartCommandTimeout();
            case RESTFUL:
                return getRestfulCommandTimeout();
        }

        throw new IllegalArgumentException("invalid command timeout type");
    }

    public int getRestfulCommandTimeout() {
        return syncReadConfiguration(Configuration::getRestfulCommandTimeout);
    }

    public void setRestfulCommandTimeout(int timeout) {
        syncWriteConfiguration(timeout, Configuration::setWebappStartCommandTimeout);
    }

    public int getWebappStartCommandTimeout() {
        return syncReadConfiguration(Configuration::getWebappStartCommandTimeout);
    }

    public void setWebappStartCommandTimeout(int timeout) {
        syncWriteConfiguration(timeout, Configuration::setWebappStartCommandTimeout);
    }

    public int getIndexerCommandTimeout() {
        return syncReadConfiguration(Configuration::getIndexerCommandTimeout);
    }

    public void setIndexerCommandTimeout(int timeout) {
        syncWriteConfiguration(timeout, Configuration::setIndexerCommandTimeout);
    }

    public int getInteractiveCommandTimeout() {
        return syncReadConfiguration(Configuration::getInteractiveCommandTimeout);
    }

    public void setInteractiveCommandTimeout(int timeout) {
        syncWriteConfiguration(timeout, Configuration::setInteractiveCommandTimeout);
    }

    public long getCtagsTimeout() {
        return syncReadConfiguration(Configuration::getCtagsTimeout);
    }

    public void setCtagsTimeout(long timeout) {
        syncWriteConfiguration(timeout, Configuration::setCtagsTimeout);
    }

    public void setLastEditedDisplayMode(boolean lastEditedDisplayMode) {
        syncWriteConfiguration(lastEditedDisplayMode, Configuration::setLastEditedDisplayMode);
    }

    public boolean isLastEditedDisplayMode() {
        return syncReadConfiguration(Configuration::isLastEditedDisplayMode);
    }

    /**
     * Get the path to the where the web application includes are stored.
     *
     * @return the path to the web application include files
     */
    public String getIncludeRootPath() {
        return syncReadConfiguration(Configuration::getIncludeRoot);
    }

    /**
     * Set include root path.
     * @param includeRoot path
     */
    public void setIncludeRoot(String includeRoot) {
        syncWriteConfiguration(getCanonicalPath(includeRoot), Configuration::setIncludeRoot);
    }

    /**
     * Get the path to the where the index database is stored.
     *
     * @return the path to the index database
     */
    public String getDataRootPath() {
        return syncReadConfiguration(Configuration::getDataRoot);
    }

    /**
     * Get a file representing the index database.
     *
     * @return the index database
     */
    public File getDataRootFile() {
        File ret = null;
        String file = getDataRootPath();
        if (file != null) {
            ret = new File(file);
        }

        return ret;
    }

    /**
     * Set the path to where the index database is stored.
     *
     * @param dataRoot the index database
     */
    public void setDataRoot(String dataRoot) {
        syncWriteConfiguration(getCanonicalPath(dataRoot), Configuration::setDataRoot);
    }

    /**
     * Get the path to where the sources are located.
     *
     * @return path to where the sources are located
     */
    public String getSourceRootPath() {
        return syncReadConfiguration(Configuration::getSourceRoot);
    }

    /**
     * Get a file representing the directory where the sources are located.
     *
     * @return A file representing the directory where the sources are located
     */
    public File getSourceRootFile() {
        File ret = null;
        String file = getSourceRootPath();
        if (file != null) {
            ret = new File(file);
        }

        return ret;
    }

    /**
     * Specify the source root.
     *
     * @param sourceRoot the location of the sources
     */
    public void setSourceRoot(String sourceRoot) {
        syncWriteConfiguration(getCanonicalPath(sourceRoot), Configuration::setSourceRoot);
    }

    /**
     * Returns a path relative to source root. This would just be a simple
     * substring operation, except we need to support symlinks outside the
     * source root.
     *
     * @param file A file to resolve
     * @return Path relative to source root
     * @throws IOException If an IO error occurs
     * @throws FileNotFoundException if the file is not relative to source root
     * or if {@code sourceRoot} is not defined
     * @throws ForbiddenSymlinkException if symbolic-link checking encounters
     * an ineligible link
     */
    public String getPathRelativeToSourceRoot(File file)
            throws IOException, ForbiddenSymlinkException {
        String sourceRoot = getSourceRootPath();
        if (sourceRoot == null) {
            throw new FileNotFoundException("sourceRoot is not defined");
        }

        String maybeRelPath = PathUtils.getRelativeToCanonical(file.getPath(),
                sourceRoot, getAllowedSymlinks(), getCanonicalRoots());
        File maybeRelFile = new File(maybeRelPath);
        if (!maybeRelFile.isAbsolute()) {
            /*
             * N.b. OpenGrok has a weird convention that source-root "relative"
             * paths must start with a '/' as they are elsewhere directly
             * appended to getSourceRootPath() and also stored as such.
             */
            maybeRelPath = File.separator + maybeRelPath;
            return maybeRelPath;
        }

        throw new FileNotFoundException("Failed to resolve [" + file.getPath()
                + "] relative to source root [" + sourceRoot + "]");
    }

    /**
     * Do we have any projects ?
     *
     * @return true if we have projects
     */
    public boolean hasProjects() {
        return (this.isProjectsEnabled() && getProjects().size() > 0);
    }

    /**
     * Get list of projects.
     *
     * @return a list containing all of the projects
     */
    public List<Project> getProjectList() {
        return new ArrayList<>(getProjects().values());
    }

    /**
     * Get project map.
     *
     * @return a Map with all of the projects
     */
    public Map<String, Project> getProjects() {
        return syncReadConfiguration(Configuration::getProjects);
    }

    /**
     * Get names of all projects.
     *
     * @return a list containing names of all projects.
     */
    public List<String> getProjectNames() {
        return getProjectList().stream().map(Project::getName).collect(Collectors.toList());
    }

    /**
     * Set the list of the projects.
     *
     * @param projects the map of projects to use
     */
    public void setProjects(Map<String, Project> projects) {
        syncWriteConfiguration(projects, (c, p) -> {
            if (p != null) {
                populateGroups(getGroups(), new TreeSet<>(p.values()));
            }
            c.setProjects(p);
        });
    }

    /**
     * Do we have groups?
     *
     * @return true if we have groups
     */
    public boolean hasGroups() {
        return (getGroups() != null && !getGroups().isEmpty());
    }

    /**
     * Get all of the groups.
     *
     * @return a set containing all of the groups (may be null)
     */
    public Set<Group> getGroups() {
        return syncReadConfiguration(Configuration::getGroups);
    }

    /**
     * Set the list of the groups.
     *
     * @param groups the set of groups to use
     */
    public void setGroups(Set<Group> groups) {
        syncWriteConfiguration(groups, (c, g) -> {
            populateGroups(g, new TreeSet<>(getProjects().values()));
            c.setGroups(g);
        });
    }

    /**
     * Returns constructed project - repositories map.
     *
     * @return the map
     * @see #generateProjectRepositoriesMap
     */
    public Map<Project, List<RepositoryInfo>> getProjectRepositoriesMap() {
        return repository_map;
    }

    /**
     * Gets a static placeholder for the web application context name that is
     * translated to the true servlet {@code contextPath} on demand.
     * @return {@code "/source"} + {@link Prefix#SEARCH_R} + {@code "?"}
     */
    public String getUrlPrefix() {
        return URL_PREFIX;
    }

    /**
     * Gets the name of the ctags program to use: either the last value passed
     * successfully to {@link #setCtags(java.lang.String)}, or
     * {@link Configuration#getCtags()}, or the system property for
     * {@code "org.opengrok.indexer.analysis.Ctags"}, or "ctags" as a
     * default.
     * @return a defined value
     */
    public String getCtags() {
        if (ctags != null) {
            return ctags;
        }

        String value = syncReadConfiguration(Configuration::getCtags);
        return value != null ? value :
                System.getProperty(CtagsUtil.SYSTEM_CTAGS_PROPERTY, "ctags");
    }

    /**
     * Sets the name of the ctags program to use, or resets to use the fallbacks
     * documented for {@link #getCtags()}.
     * <p>
     * N.b. the value is not mediated to {@link Configuration}.
     *
     * @param ctags a defined value or {@code null} to reset to use the
     * {@link Configuration#getCtags()} fallbacks
     * @see #getCtags()
     */
    public void setCtags(String ctags) {
        this.ctags = ctags;
    }

    /**
     * Gets the name of the mandoc program to use: either the last value passed
     * successfully to {@link #setMandoc(java.lang.String)}, or
     * {@link Configuration#getMandoc()}, or the system property for
     * {@code "org.opengrok.indexer.analysis.Mandoc"}, or {@code null} as a
     * default.
     * @return a defined instance or {@code null}
     */
    public String getMandoc() {
        if (mandoc != null) {
            return mandoc;
        }

        String value = syncReadConfiguration(Configuration::getMandoc);
        return value != null ? value :
                System.getProperty("org.opengrok.indexer.analysis.Mandoc");
    }

    /**
     * Sets the name of the mandoc program to use, or resets to use the
     * fallbacks documented for {@link #getMandoc()}.
     * <p>
     * N.b. the value is not mediated to {@link Configuration}.
     *
     * @param value a defined value or {@code null} to reset to use the
     * {@link Configuration#getMandoc()} fallbacks
     * @see #getMandoc()
     */
    public void setMandoc(String value) {
        this.mandoc = value;
    }

    public int getCachePages() {
        return syncReadConfiguration(Configuration::getCachePages);
    }

    public void setCachePages(int cachePages) {
        syncWriteConfiguration(cachePages, Configuration::setCachePages);
    }

    public int getHitsPerPage() {
        return syncReadConfiguration(Configuration::getHitsPerPage);
    }

    public void setHitsPerPage(int hitsPerPage) {
        syncWriteConfiguration(hitsPerPage, Configuration::setHitsPerPage);
    }

    /**
     * Validate that there is a Universal ctags program.
     *
     * @return true if success, false otherwise
     */
    public boolean validateUniversalCtags() {
        if (ctagsFound == null) {
            String ctagsBinary = getCtags();
            try (ResourceLock resourceLock = configLock.writeLockAsResource()) {
                //noinspection ConstantConditions to avoid warning of no reference to auto-closeable
                assert resourceLock != null;
                if (ctagsFound == null) {
                    ctagsFound = CtagsUtil.validate(ctagsBinary);
                    if (ctagsFound) {
                        List<String> languages = CtagsUtil.getLanguages(ctagsBinary);
                        if (languages != null) {
                            ctagsLanguages.addAll(languages);
                        }
                    }
                }
            }
        }
        return ctagsFound;
    }

    /**
     * Gets the base set of supported Ctags languages.
     * @return a defined set which may be empty if
     * {@link #validateUniversalCtags()} has not yet been called or if the call
     * fails
     */
    public Set<String> getCtagsLanguages() {
        return Collections.unmodifiableSet(ctagsLanguages);
    }

    /**
     * Get the max time a SCM operation may use to avoid being cached.
     *
     * @return the max time
     */
    public int getHistoryReaderTimeLimit() {
        return syncReadConfiguration(Configuration::getHistoryCacheTime);
    }

    /**
     * Specify the maximum time a SCM operation should take before it will be
     * cached (in ms).
     *
     * @param historyCacheTime the max time in ms before it is cached
     */
    public void setHistoryReaderTimeLimit(int historyCacheTime) {
        syncWriteConfiguration(historyCacheTime, Configuration::setHistoryCacheTime);
    }

    /**
     * Is history cache currently enabled?
     *
     * @return true if history cache is enabled
     */
    public boolean useHistoryCache() {
        return syncReadConfiguration(Configuration::isHistoryCache);
    }

    /**
     * Specify if we should use history cache or not.
     *
     * @param useHistoryCache set false if you do not want to use history cache
     */
    public void setUseHistoryCache(boolean useHistoryCache) {
        syncWriteConfiguration(useHistoryCache, Configuration::setHistoryCache);
    }

    /**
     * Should we generate HTML or not during the indexing phase.
     *
     * @return true if HTML should be generated during the indexing phase
     */
    public boolean isGenerateHtml() {
        return syncReadConfiguration(Configuration::isGenerateHtml);
    }

    /**
     * Specify if we should generate HTML or not during the indexing phase.
     *
     * @param generateHtml set this to true to pregenerate HTML
     */
    public void setGenerateHtml(boolean generateHtml) {
        syncWriteConfiguration(generateHtml, Configuration::setGenerateHtml);
    }

    /**
     * Set if we should compress the xref files or not.
     *
     * @param compressXref set to true if the generated html files should be
     * compressed
     */
    public void setCompressXref(boolean compressXref) {
        syncWriteConfiguration(compressXref, Configuration::setCompressXref);
    }

    /**
     * Are we using compressed HTML files?
     *
     * @return {@code true} if the html-files should be compressed.
     */
    public boolean isCompressXref() {
        return syncReadConfiguration(Configuration::isCompressXref);
    }

    public boolean isQuickContextScan() {
        return syncReadConfiguration(Configuration::isQuickContextScan);
    }

    public void setQuickContextScan(boolean quickContextScan) {
        syncWriteConfiguration(quickContextScan, Configuration::setQuickContextScan);
    }

    public List<RepositoryInfo> getRepositories() {
        return syncReadConfiguration(Configuration::getRepositories);
    }

    /**
     * Set the list of repositories.
     *
     * @param repositories the repositories to use
     */
    public void setRepositories(List<RepositoryInfo> repositories) {
        syncWriteConfiguration(repositories, Configuration::setRepositories);
    }
    
    public void removeRepositories() {
        syncWriteConfiguration(null, Configuration::setRepositories);
    }
    
    /**
     * Search through the directory for repositories and use the result to replace
     * the lists of repositories in both RuntimeEnvironment/Configuration and HistoryGuru.
     *
     * @param dir the directories to start the search in
     */
    public void setRepositories(String... dir) {
        List<RepositoryInfo> repos = new ArrayList<>(HistoryGuru.getInstance().
                addRepositories(Arrays.stream(dir).map(File::new).toArray(File[]::new)));
        setRepositories(repos);
    }

    /**
     * Add repositories to the list.
     * @param repositories list of repositories
     */
    public void addRepositories(List<RepositoryInfo> repositories) {
        syncWriteConfiguration(repositories, Configuration::addRepositories);
    }

    /**
     * Set the specified projects as default in the configuration.
     * This method should be called only after projects were discovered and became part of the configuration,
     * i.e. after {@link org.opengrok.indexer.index.Indexer#prepareIndexer} was called.
     *
     * @param defaultProjects The default project to use
     * @see #setDefaultProjects
     */
    public void setDefaultProjectsFromNames(Set<String> defaultProjects) {
        if (defaultProjects != null && !defaultProjects.isEmpty()) {
            Set<Project> projects = new TreeSet<>();
            for (String projectPath : defaultProjects) {
                if (projectPath.equals("__all__")) {
                    projects.addAll(getProjects().values());
                    break;
                }
                for (Project p : getProjectList()) {
                    if (p.getPath().equals(Util.fixPathIfWindows(projectPath))) {
                        projects.add(p);
                        break;
                    }
                }
            }
            if (!projects.isEmpty()) {
                setDefaultProjects(projects);
            }
        }
    }

    /**
     * Set the projects that are specified to be the default projects to use.
     * The default projects are the projects you will search (from the web
     * application) if the page request didn't contain the cookie..
     *
     * @param defaultProjects The default project to use
     */
    public void setDefaultProjects(Set<Project> defaultProjects) {
        syncWriteConfiguration(defaultProjects, Configuration::setDefaultProjects);
    }

    /**
     * Get the projects that are specified to be the default projects to use.
     * The default projects are the projects you will search (from the web
     * application) if the page request didn't contain the cookie..
     *
     * @return the default projects (may be null if not specified)
     */
    public Set<Project> getDefaultProjects() {
        Set<Project> projects = syncReadConfiguration(Configuration::getDefaultProjects);
        if (projects == null) {
            return null;
        }
        return Collections.unmodifiableSet(projects);
    }

    /**
     *
     * @return at what size (in MB) we should flush the buffer
     */
    public double getRamBufferSize() {
        return syncReadConfiguration(Configuration::getRamBufferSize);
    }

    /**
     * Set the size of buffer which will determine when the docs are flushed to
     * disk. Specify size in MB please. 16MB is default note that this is per
     * thread (lucene uses 8 threads by default in 4.x)
     *
     * @param ramBufferSize the size(in MB) when we should flush the docs
     */
    public void setRamBufferSize(double ramBufferSize) {
        syncWriteConfiguration(ramBufferSize, Configuration::setRamBufferSize);
    }

    public void setPluginDirectory(String pluginDirectory) {
        syncWriteConfiguration(pluginDirectory, Configuration::setPluginDirectory);
    }

    public String getPluginDirectory() {
        return syncReadConfiguration(Configuration::getPluginDirectory);
    }

    public boolean isAuthorizationWatchdog() {
        return syncReadConfiguration(Configuration::isAuthorizationWatchdogEnabled);
    }

    public void setAuthorizationWatchdog(boolean authorizationWatchdogEnabled) {
        syncWriteConfiguration(authorizationWatchdogEnabled,
                Configuration::setAuthorizationWatchdogEnabled);
    }

    public AuthorizationStack getPluginStack() {
        return syncReadConfiguration(Configuration::getPluginStack);
    }

    public void setPluginStack(AuthorizationStack pluginStack) {
        syncWriteConfiguration(pluginStack, Configuration::setPluginStack);
    }

    /**
     * Is the progress print flag turned on?
     *
     * @return true if we can print per project progress %
     */
    public boolean isPrintProgress() {
        return syncReadConfiguration(Configuration::isPrintProgress);
    }

    /**
     * Set the printing of progress % flag (user convenience).
     *
     * @param printProgress new value
     */
    public void setPrintProgress(boolean printProgress) {
        syncWriteConfiguration(printProgress, Configuration::setPrintProgress);
    }

    /**
     * Specify if a search may start with a wildcard. Note that queries that
     * start with a wildcard will give a significant impact on the search
     * performance.
     *
     * @param allowLeadingWildcard set to true to activate (disabled by default)
     */
    public void setAllowLeadingWildcard(boolean allowLeadingWildcard) {
        syncWriteConfiguration(allowLeadingWildcard, Configuration::setAllowLeadingWildcard);
    }

    /**
     * Is leading wildcards allowed?
     *
     * @return true if a search may start with a wildcard
     */
    public boolean isAllowLeadingWildcard() {
        return syncReadConfiguration(Configuration::isAllowLeadingWildcard);
    }

    public IgnoredNames getIgnoredNames() {
        return syncReadConfiguration(Configuration::getIgnoredNames);
    }

    public void setIgnoredNames(IgnoredNames ignoredNames) {
        syncWriteConfiguration(ignoredNames, Configuration::setIgnoredNames);
    }

    public Filter getIncludedNames() {
        return syncReadConfiguration(Configuration::getIncludedNames);
    }

    public void setIncludedNames(Filter includedNames) {
        syncWriteConfiguration(includedNames, Configuration::setIncludedNames);
    }

    /**
     * Returns the user page for the history listing.
     *
     * @return the URL string fragment preceeding the username
     */
    public String getUserPage() {
        return syncReadConfiguration(Configuration::getUserPage);
    }

    /**
     * Get the client command to use to access the repository for the given
     * fully qualified classname.
     *
     * @param clazzName name of the targeting class
     * @return {@code null} if not yet set, the client command otherwise.
     */
    public String getRepoCmd(String clazzName) {
        return syncReadConfiguration(c -> c.getRepoCmd(clazzName));
    }

    /**
     * Set the client command to use to access the repository for the given
     * fully qualified classname.
     *
     * @param clazzName name of the targeting class. If {@code null} this method
     * does nothing.
     * @param cmd the client command to use. If {@code null} the corresponding
     * entry for the given clazzName get removed.
     * @return the client command previously set, which might be {@code null}.
     */
    public String setRepoCmd(String clazzName, String cmd) {
        syncWriteConfiguration(null, (c, ignored) -> c.setRepoCmd(clazzName, cmd));
        return cmd;
    }

    public void setRepoCmds(Map<String, String> cmds) {
        syncWriteConfiguration(cmds, Configuration::setCmds);
    }

    /**
     * Sets the user page for the history listing.
     *
     * @param userPage the URL fragment preceeding the username from history
     */
    public void setUserPage(String userPage) {
        syncWriteConfiguration(userPage, Configuration::setUserPage);
    }

    /**
     * Returns the user page suffix for the history listing.
     *
     * @return the URL string fragment following the username
     */
    public String getUserPageSuffix() {
        return syncReadConfiguration(Configuration::getUserPageSuffix);
    }

    /**
     * Sets the user page suffix for the history listing.
     *
     * @param userPageSuffix the URL fragment following the username from
     * history
     */
    public void setUserPageSuffix(String userPageSuffix) {
        syncWriteConfiguration(userPageSuffix, Configuration::setUserPageSuffix);
    }

    /**
     * Returns the bug page for the history listing.
     *
     * @return the URL string fragment preceeding the bug ID
     */
    public String getBugPage() {
        return syncReadConfiguration(Configuration::getBugPage);
    }

    /**
     * Sets the bug page for the history listing.
     *
     * @param bugPage the URL fragment preceeding the bug ID
     */
    public void setBugPage(String bugPage) {
        syncWriteConfiguration(bugPage, Configuration::setBugPage);
    }

    /**
     * Returns the bug regex for the history listing.
     *
     * @return the regex that is looked for in history comments
     */
    public String getBugPattern() {
        return syncReadConfiguration(Configuration::getBugPattern);
    }

    /**
     * Sets the bug regex for the history listing.
     *
     * @param bugPattern the regex to search history comments
     */
    public void setBugPattern(String bugPattern) {
        syncWriteConfiguration(bugPattern, Configuration::setBugPattern);
    }

    /**
     * Returns the review(ARC) page for the history listing.
     *
     * @return the URL string fragment preceeding the review page ID
     */
    public String getReviewPage() {
        return syncReadConfiguration(Configuration::getReviewPage);
    }

    /**
     * Sets the review(ARC) page for the history listing.
     *
     * @param reviewPage the URL fragment preceeding the review page ID
     */
    public void setReviewPage(String reviewPage) {
        syncWriteConfiguration(reviewPage, Configuration::setReviewPage);
    }

    /**
     * Returns the review(ARC) regex for the history listing.
     *
     * @return the regex that is looked for in history comments
     */
    public String getReviewPattern() {
        return syncReadConfiguration(Configuration::getReviewPattern);
    }

    /**
     * Sets the review(ARC) regex for the history listing.
     *
     * @param reviewPattern the regex to search history comments
     */
    public void setReviewPattern(String reviewPattern) {
        syncWriteConfiguration(reviewPattern, Configuration::setReviewPattern);
    }

    public String getWebappLAF() {
        return syncReadConfiguration(Configuration::getWebappLAF);
    }

    public void setWebappLAF(String webappLAF) {
        syncWriteConfiguration(webappLAF, Configuration::setWebappLAF);
    }

    /**
     * Gets a value indicating if the web app should run ctags as necessary.
     * @return the value of {@link Configuration#isWebappCtags()}
     */
    public boolean isWebappCtags() {
        return syncReadConfiguration(Configuration::isWebappCtags);
    }

    public Configuration.RemoteSCM getRemoteScmSupported() {
        return syncReadConfiguration(Configuration::getRemoteScmSupported);
    }

    public void setRemoteScmSupported(Configuration.RemoteSCM remoteScmSupported) {
        syncWriteConfiguration(remoteScmSupported, Configuration::setRemoteScmSupported);
    }

    public boolean isOptimizeDatabase() {
        return syncReadConfiguration(Configuration::isOptimizeDatabase);
    }

    public void setOptimizeDatabase(boolean optimizeDatabase) {
        syncWriteConfiguration(optimizeDatabase, Configuration::setOptimizeDatabase);
    }

    public LuceneLockName getLuceneLocking() {
        return syncReadConfiguration(Configuration::getLuceneLocking);
    }

    public boolean isIndexVersionedFilesOnly() {
        return syncReadConfiguration(Configuration::isIndexVersionedFilesOnly);
    }

    public void setIndexVersionedFilesOnly(boolean indexVersionedFilesOnly) {
        syncWriteConfiguration(indexVersionedFilesOnly, Configuration::setIndexVersionedFilesOnly);
    }

    /**
     * Gets the value of {@link Configuration#getIndexingParallelism()} -- or
     * if zero, then as a default gets the number of available processors.
     * @return a natural number &gt;= 1
     */
    public int getIndexingParallelism() {
        int parallelism = syncReadConfiguration(Configuration::getIndexingParallelism);
        return parallelism < 1 ? Runtime.getRuntime().availableProcessors() :
                parallelism;
    }

    /**
     * Gets the value of {@link Configuration#getHistoryParallelism()} -- or
     * if zero, then as a default gets the number of available processors.
     * @return a natural number &gt;= 1
     */
    public int getHistoryParallelism() {
        int parallelism = syncReadConfiguration(Configuration::getHistoryParallelism);
        return parallelism < 1 ? Runtime.getRuntime().availableProcessors() :
                parallelism;
    }

    /**
     * Gets the value of {@link Configuration#getHistoryRenamedParallelism()} -- or
     * if zero, then as a default gets the number of available processors.
     * @return a natural number &gt;= 1
     */
    public int getHistoryRenamedParallelism() {
        int parallelism = syncReadConfiguration(Configuration::getHistoryRenamedParallelism);
        return parallelism < 1 ? Runtime.getRuntime().availableProcessors() :
                parallelism;
    }

    public boolean isTagsEnabled() {
        return syncReadConfiguration(Configuration::isTagsEnabled);
    }

    public void setTagsEnabled(boolean tagsEnabled) {
        syncWriteConfiguration(tagsEnabled, Configuration::setTagsEnabled);
    }

    public boolean isScopesEnabled() {
        return syncReadConfiguration(Configuration::isScopesEnabled);
    }

    public void setScopesEnabled(boolean scopesEnabled) {
        syncWriteConfiguration(scopesEnabled, Configuration::setScopesEnabled);
    }

    public boolean isProjectsEnabled() {
        return syncReadConfiguration(Configuration::isProjectsEnabled);
    }

    public void setProjectsEnabled(boolean projectsEnabled) {
        syncWriteConfiguration(projectsEnabled, Configuration::setProjectsEnabled);
    }

    public boolean isFoldingEnabled() {
        return syncReadConfiguration(Configuration::isFoldingEnabled);
    }

    public void setFoldingEnabled(boolean foldingEnabled) {
        syncWriteConfiguration(foldingEnabled, Configuration::setFoldingEnabled);
    }

    public Date getDateForLastIndexRun() {
        return indexTime.getDateForLastIndexRun();
    }

    public String getCTagsExtraOptionsFile() {
        return syncReadConfiguration(Configuration::getCTagsExtraOptionsFile);
    }

    public void setCTagsExtraOptionsFile(String ctagsExtraOptionsFile) {
        syncWriteConfiguration(ctagsExtraOptionsFile, Configuration::setCTagsExtraOptionsFile);
    }

    public Set<String> getAllowedSymlinks() {
        return syncReadConfiguration(Configuration::getAllowedSymlinks);
    }

    public void setAllowedSymlinks(Set<String> allowedSymlinks) {
        syncWriteConfiguration(allowedSymlinks, Configuration::setAllowedSymlinks);
    }

    public Set<String> getCanonicalRoots() {
        return syncReadConfiguration(Configuration::getCanonicalRoots);
    }

    public void setCanonicalRoots(Set<String> canonicalRoots) {
        syncWriteConfiguration(canonicalRoots, Configuration::setCanonicalRoots);
    }

    /**
     * Return whether e-mail addresses should be obfuscated in the xref.
     * @return if we obfuscate emails
     */
    public boolean isObfuscatingEMailAddresses() {
        return syncReadConfiguration(Configuration::isObfuscatingEMailAddresses);
    }

    /**
     * Set whether e-mail addresses should be obfuscated in the xref.
     * @param obfuscatingEMailAddresses should we obfuscate emails?
     */
    public void setObfuscatingEMailAddresses(boolean obfuscatingEMailAddresses) {
        syncWriteConfiguration(obfuscatingEMailAddresses,
                Configuration::setObfuscatingEMailAddresses);
    }

    /**
     * Should status.jsp print internal settings, like paths and database URLs?
     *
     * @return {@code true} if status.jsp should show the configuration,
     * {@code false} otherwise
     */
    public boolean isChattyStatusPage() {
        return syncReadConfiguration(Configuration::isChattyStatusPage);
    }

    /**
     * Set whether status.jsp should print internal settings.
     *
     * @param chattyStatusPage {@code true} if internal settings should be printed,
     * {@code false} otherwise
     */
    public void setChattyStatusPage(boolean chattyStatusPage) {
        syncWriteConfiguration(chattyStatusPage, Configuration::setChattyStatusPage);
    }

    public void setFetchHistoryWhenNotInCache(boolean fetchHistoryWhenNotInCache) {
        syncWriteConfiguration(fetchHistoryWhenNotInCache,
                Configuration::setFetchHistoryWhenNotInCache);
    }

    public boolean isFetchHistoryWhenNotInCache() {
        return syncReadConfiguration(Configuration::isFetchHistoryWhenNotInCache);
    }

    public boolean isHistoryCache() {
        return syncReadConfiguration(Configuration::isHistoryCache);
    }

    public void setHandleHistoryOfRenamedFiles(boolean handleHistoryOfRenamedFiles) {
        syncWriteConfiguration(handleHistoryOfRenamedFiles,
                Configuration::setHandleHistoryOfRenamedFiles);
    }

    public boolean isHandleHistoryOfRenamedFiles() {
        return syncReadConfiguration(Configuration::isHandleHistoryOfRenamedFiles);
    }

    public void setNavigateWindowEnabled(boolean navigateWindowEnabled) {
        syncWriteConfiguration(navigateWindowEnabled, Configuration::setNavigateWindowEnabled);
    }

    public boolean isNavigateWindowEnabled() {
        return syncReadConfiguration(Configuration::isNavigateWindowEnabled);
    }

    public void setRevisionMessageCollapseThreshold(int revisionMessageCollapseThreshold) {
        syncWriteConfiguration(revisionMessageCollapseThreshold,
                Configuration::setRevisionMessageCollapseThreshold);
    }

    public int getRevisionMessageCollapseThreshold() {
        return syncReadConfiguration(Configuration::getRevisionMessageCollapseThreshold);
    }

    public void setMaxSearchThreadCount(int maxSearchThreadCount) {
        syncWriteConfiguration(maxSearchThreadCount, Configuration::setMaxSearchThreadCount);
    }

    public int getMaxSearchThreadCount() {
        return syncReadConfiguration(Configuration::getMaxSearchThreadCount);
    }

    public void setMaxRevisionThreadCount(int maxRevisionThreadCount) {
        syncWriteConfiguration(maxRevisionThreadCount, Configuration::setMaxRevisionThreadCount);
    }

    public int getMaxRevisionThreadCount() {
        return syncReadConfiguration(Configuration::getMaxRevisionThreadCount);
    }

    public int getCurrentIndexedCollapseThreshold() {
        return syncReadConfiguration(Configuration::getCurrentIndexedCollapseThreshold);
    }

    public void setCurrentIndexedCollapseThreshold(int currentIndexedCollapseThreshold) {
        syncWriteConfiguration(currentIndexedCollapseThreshold,
                Configuration::setCurrentIndexedCollapseThreshold);
    }

    public int getGroupsCollapseThreshold() {
        return syncReadConfiguration(Configuration::getGroupsCollapseThreshold);
    }

    // The URI is not necessary to be present in the configuration
    // (so that when -U option of the indexer is omitted, the config will not
    // be sent to the webapp) so store it only in the RuntimeEnvironment.
    public void setConfigURI(String host) {
        configURI = host;
    }

    public String getConfigURI() {
        return configURI;
    }

    public boolean isHistoryEnabled() {
        return syncReadConfiguration(Configuration::isHistoryEnabled);
    }

    public void setHistoryEnabled(boolean historyEnabled) {
        syncWriteConfiguration(historyEnabled, Configuration::setHistoryEnabled);
    }

    public boolean getDisplayRepositories() {
        return syncReadConfiguration(Configuration::getDisplayRepositories);
    }

    public void setDisplayRepositories(boolean displayRepositories) {
        syncWriteConfiguration(displayRepositories, Configuration::setDisplayRepositories);
    }

    public boolean getListDirsFirst() {
        return syncReadConfiguration(Configuration::getListDirsFirst);
    }

    public void setListDirsFirst(boolean listDirsFirst) {
        syncWriteConfiguration(listDirsFirst, Configuration::setListDirsFirst);
    }

    public void setTabSize(int tabSize) {
        syncWriteConfiguration(tabSize, Configuration::setTabSize);
    }

    public int getTabSize() {
        return syncReadConfiguration(Configuration::getTabSize);
    }

    /**
     * Gets the total number of context lines per file to show.
     * @return a value greater than zero
     */
    public short getContextLimit() {
        return syncReadConfiguration(Configuration::getContextLimit);
    }

    /**
     * Gets the number of context lines to show before or after any match.
     * @return a value greater than or equal to zero
     */
    public short getContextSurround() {
        return syncReadConfiguration(Configuration::getContextSurround);
    }

    public Set<String> getDisabledRepositories() {
        return syncReadConfiguration(Configuration::getDisabledRepositories);
    }

    public void setDisabledRepositories(Set<String> disabledRepositories) {
        syncWriteConfiguration(disabledRepositories, Configuration::setDisabledRepositories);
    }

    /**
     * Read an configuration file and set it as the current configuration.
     *
     * @param file the file to read
     * @throws IOException if an error occurs
     */
    public void readConfiguration(File file) throws IOException {
        // The following method handles the locking.
        setConfiguration(Configuration.read(file));
    }

    /**
     * Read configuration from a file and put it into effect.
     * @param file the file to read
     * @param cmdType command timeout type
     * @throws IOException I/O
     */
    public void readConfiguration(File file, CommandTimeoutType cmdType) throws IOException {
        // The following method handles the locking.
        setConfiguration(Configuration.read(file), null, cmdType);
    }

    /**
     * Write the current configuration to a file.
     *
     * @param file the file to write the configuration into
     * @throws IOException if an error occurs
     */
    public void writeConfiguration(File file) throws IOException {
        try (ResourceLock resourceLock = configLock.readLockAsResource()) {
            //noinspection ConstantConditions to avoid warning of no reference to auto-closeable
            assert resourceLock != null;
            configuration.write(file);
        }
    }

    public String getConfigurationXML() {
        return syncReadConfiguration(Configuration::getXMLRepresentationAsString);
    }

    /**
     * Write the current configuration to a socket.
     *
     * @param host the host address to receive the configuration
     * @throws IOException if an error occurs
     */
    public void writeConfiguration(String host) throws IOException {
        String configXML = syncReadConfiguration(Configuration::getXMLRepresentationAsString);

        Response r = ClientBuilder.newClient()
                .target(host)
                .path("api")
                .path("v1")
                .path("configuration")
                .queryParam("reindex", true)
                .request()
                .put(Entity.xml(configXML));

        if (r.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
            throw new IOException(r.toString());
        }
    }

    /**
     * Send message to webapp to refresh SearcherManagers for given projects.
     * This is used for partial reindex.
     *
     * @param subFiles list of directories to refresh corresponding SearcherManagers
     * @param host the host address to receive the configuration
     */
    public void signalTorefreshSearcherManagers(List<String> subFiles, String host) {
        // subFile entries start with path separator so get basename
        // to convert them to project names.

        subFiles.stream().map(proj -> new File(proj).getName()).forEach(project -> {
            Response r = ClientBuilder.newClient()
                    .target(host)
                    .path("api")
                    .path("v1")
                    .path("system")
                    .path("refresh")
                    .request()
                    .put(Entity.text(project));

            if (r.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
                LOGGER.log(Level.WARNING, "Could not refresh search manager for {0}", project);
            }
        });
    }

    /**
     * Generate a TreeMap of projects with corresponding repository information.
     * <p>
     * Project with some repository information is considered as a repository
     * otherwise it is just a simple project.
     */
    private void generateProjectRepositoriesMap() throws IOException {
        repository_map.clear();
        for (RepositoryInfo r : getRepositories()) {
            Project proj;
            String repoPath;
            try {
                repoPath = getPathRelativeToSourceRoot(new File(r.getDirectoryName()));
            } catch (ForbiddenSymlinkException e) {
                LOGGER.log(Level.FINER, e.getMessage());
                continue;
            }

            if ((proj = Project.getProject(repoPath)) != null) {
                List<RepositoryInfo> values = repository_map.computeIfAbsent(proj, k -> new ArrayList<>());
                // the map is held under the lock because the next call to
                // values.add(r) which should not be called from multiple threads at the same time
                values.add(r);
            }
        }
    }

    /**
     * Classifies projects and puts them in their groups.
     * <p>
     * If any of the groups contain some projects or repositories already,
     * these get discarded.
     *
     * @param groups   set of groups to be filled with matching projects
     * @param projects projects to classify
     */
    public void populateGroups(Set<Group> groups, Set<Project> projects) {
        if (projects == null || groups == null) {
            return;
        }

        // clear the groups first if they had something in them
        for (Group group : groups) {
            group.getRepositories().clear();
            group.getProjects().clear();
        }

        // now fill the groups with appropriate projects
        for (Project project : projects) {
            // clear the project's groups
            project.getGroups().clear();

            // filter projects only to groups which match project's name
            Set<Group> copy = Group.matching(project, groups);

            // add project to the groups
            for (Group group : copy) {
                if (repository_map.get(project) == null) {
                    group.addProject(project);
                } else {
                    group.addRepository(project);
                }
                project.addGroup(group);
            }
        }
    }

    /**
     * Sets the configuration and performs necessary actions.
     *
     * Mainly it classifies the projects in their groups and generates project -
     * repositories map
     *
     * @param configuration what configuration to use
     */
    public void setConfiguration(Configuration configuration) {
        setConfiguration(configuration, null, CommandTimeoutType.INDEXER);
    }

    /**
     * Sets the configuration and performs necessary actions.
     * @param configuration new configuration
     * @param cmdType command timeout type
     */
    public void setConfiguration(Configuration configuration, CommandTimeoutType cmdType) {
        setConfiguration(configuration, null, cmdType);
    }

    /**
     * Sets the configuration and performs necessary actions.
     *
     * @param configuration new configuration
     * @param subFileList   list of repositories
     * @param cmdType       command timeout type
     */
    public synchronized void setConfiguration(Configuration configuration, List<String> subFileList, CommandTimeoutType cmdType) {
        try (ResourceLock resourceLock = configLock.writeLockAsResource()) {
            //noinspection ConstantConditions to avoid warning of no reference to auto-closeable
            assert resourceLock != null;
            this.configuration = configuration;
        }

        // HistoryGuru constructor needs environment properties so no locking is done here.
        HistoryGuru histGuru = HistoryGuru.getInstance();

        // Set the working repositories in HistoryGuru.
        if (subFileList != null) {
            histGuru.invalidateRepositories(getRepositories(), subFileList, cmdType);
        } else {
            histGuru.invalidateRepositories(getRepositories(), cmdType);
        }

        // The invalidation of repositories above might have excluded some
        // repositories in HistoryGuru so the configuration needs to reflect that.
        setRepositories(new ArrayList<>(histGuru.getRepositories()));

        // generate repository map is dependent on getRepositories()
        try {
            generateProjectRepositoriesMap();
        } catch (IOException ex) {
            LOGGER.log(Level.SEVERE, "Cannot generate project - repository map", ex);
        }

        // populate groups is dependent on repositories map
        populateGroups(getGroups(), new TreeSet<>(getProjects().values()));

        includeFiles.reloadIncludeFiles();
    }

    public IncludeFiles getIncludeFiles() {
        return includeFiles;
    }

    /**
     * Return the authorization framework used in this environment.
     *
     * @return the framework
     */
    public AuthorizationFramework getAuthorizationFramework() {
        synchronized (authFrameworkLock) {
            if (authFramework == null) {
                authFramework = new AuthorizationFramework(getPluginDirectory(), getPluginStack());
            }
            return authFramework;
        }
    }

    /**
     * Set the authorization framework for this environment. Unload all
     * previously load plugins.
     *
     * @param fw the new framework
     */
    public void setAuthorizationFramework(AuthorizationFramework fw) {
        synchronized (authFrameworkLock) {
           if (this.authFramework != null) {
                this.authFramework.removeAll();
            }
            this.authFramework = fw;
        }
    }

    /**
     * Re-apply the configuration.
     * @param reindex is the message result of reindex
     * @param cmdType command timeout type
     */
    public void applyConfig(boolean reindex, CommandTimeoutType cmdType) {
        applyConfig(configuration, reindex, cmdType);
    }

    /**
     * Set configuration from a message. The message could have come from the
     * Indexer (in which case some extra work is needed) or is it just a request
     * to set new configuration in place.
     *
     * @param configuration XML configuration
     * @param reindex is the message result of reindex
     * @param cmdType command timeout type
     * @see #applyConfig(org.opengrok.indexer.configuration.Configuration,
     * boolean, CommandTimeoutType) applyConfig(config, reindex, cmdType)
     */
    public void applyConfig(String configuration, boolean reindex, CommandTimeoutType cmdType) {
        Configuration config;
        try {
            config = makeXMLStringAsConfiguration(configuration);
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "Configuration decoding failed", ex);
            return;
        }

        applyConfig(config, reindex, cmdType);
    }

    /**
     * Set configuration from the incoming parameter. The configuration could
     * have come from the Indexer (in which case some extra work is needed) or
     * is it just a request to set new configuration in place.
     *
     * @param config the incoming configuration
     * @param reindex is the message result of reindex
     * @param cmdType command timeout type
     */
    public void applyConfig(Configuration config, boolean reindex, CommandTimeoutType cmdType) {
        setConfiguration(config, cmdType);
        LOGGER.log(Level.INFO, "Configuration updated");

        if (reindex) {
            // We are assuming that each update of configuration means reindex. If dedicated thread is introduced
            // in the future solely for the purpose of getting the event of reindex, the 2 calls below should
            // be moved there.
            refreshSearcherManagerMap();
            maybeRefreshIndexSearchers();
            // Force timestamp to update itself upon new config arrival.
            refreshDateForLastIndexRun();
        }

        // start/stop the watchdog if necessary
        if (isAuthorizationWatchdog() && getPluginDirectory() != null) {
            watchDog.start(new File(getPluginDirectory()));
        } else {
            watchDog.stop();
        }

        // set the new plugin directory and reload the authorization framework
        getAuthorizationFramework().setPluginDirectory(getPluginDirectory());
        getAuthorizationFramework().setStack(getPluginStack());
        getAuthorizationFramework().reload();

        messagesContainer.setMessageLimit(getMessageLimit());
    }

    public void setIndexTimestamp() throws IOException {
        indexTime.stamp();
    }

    public void refreshDateForLastIndexRun() {
        indexTime.refreshDateForLastIndexRun();
    }

    private void maybeRefreshSearcherManager(SearcherManager sm) {
        try {
            sm.maybeRefresh();
        }  catch (AlreadyClosedException ex) {
            // This is a case of removed project. See refreshSearcherManagerMap() for details.
        } catch (IOException ex) {
            LOGGER.log(Level.SEVERE, "maybeRefresh failed", ex);
        }
    }

    public void maybeRefreshIndexSearchers(Iterable<String> projects) {
        for (String proj : projects) {
            if (searcherManagerMap.containsKey(proj)) {
                maybeRefreshSearcherManager(searcherManagerMap.get(proj));
            }
        }
    }

    public void maybeRefreshIndexSearchers() {
        for (Map.Entry<String, SearcherManager> entry : searcherManagerMap.entrySet()) {
            maybeRefreshSearcherManager(entry.getValue());
        }
    }

    /**
     * Get IndexSearcher for given project.
     * Each IndexSearcher is born from a SearcherManager object. There is one SearcherManager for every project.
     * This schema makes it possible to reuse IndexSearcher/IndexReader objects so the heavy lifting
     * (esp. system calls) performed in FSDirectory and DirectoryReader happens only once for a project.
     * The caller has to make sure that the IndexSearcher is returned back
     * to the SearcherManager. This is done with returnIndexSearcher().
     * The return of the IndexSearcher should happen only after the search result data are read fully.
     *
     * @param projectName project
     * @return SearcherManager for given project
     * @throws IOException I/O exception
     */
    public SuperIndexSearcher getIndexSearcher(String projectName) throws IOException {
        SearcherManager mgr = searcherManagerMap.get(projectName);
        SuperIndexSearcher searcher;

        if (mgr == null) {
            File indexDir = new File(getDataRootPath(), IndexDatabase.INDEX_DIR);
            Directory dir = FSDirectory.open(new File(indexDir, projectName).toPath());
            mgr = new SearcherManager(dir, new ThreadpoolSearcherFactory());
            searcherManagerMap.put(projectName, mgr);
            searcher = (SuperIndexSearcher) mgr.acquire();
            searcher.setSearcherManager(mgr);
        } else {
            searcher = (SuperIndexSearcher) mgr.acquire();
            searcher.setSearcherManager(mgr);
        }

        return searcher;
    }

    /**
     * After new configuration is put into place, the set of projects might
     * change so we go through the SearcherManager objects and close those where
     * the corresponding project is no longer present.
     */
    public void refreshSearcherManagerMap() {
        ArrayList<String> toRemove = new ArrayList<>();

        for (Map.Entry<String, SearcherManager> entry : searcherManagerMap.entrySet()) {
            // If a project is gone, close the corresponding SearcherManager
            // so that it cannot produce new IndexSearcher objects.
            if (!getProjectNames().contains(entry.getKey())) {
                try {
                    LOGGER.log(Level.FINE,
                        "closing SearcherManager for project" + entry.getKey());
                    entry.getValue().close();
                } catch (IOException ex) {
                    LOGGER.log(Level.SEVERE,
                        "cannot close SearcherManager for project" + entry.getKey(), ex);
                }
                toRemove.add(entry.getKey());
            }
        }

        for (String proj : toRemove) {
            searcherManagerMap.remove(proj);
        }
    }

    /**
     * Return collection of IndexReader objects as MultiReader object
     * for given list of projects.
     * The caller is responsible for releasing the IndexSearcher objects
     * so we add them to the map.
     *
     * @param projects list of projects
     * @param searcherList each SuperIndexSearcher produced will be put into this list
     * @return MultiReader for the projects
     */
    public MultiReader getMultiReader(SortedSet<String> projects,
        ArrayList<SuperIndexSearcher> searcherList) {

        IndexReader[] subreaders = new IndexReader[projects.size()];
        int ii = 0;

        // TODO might need to rewrite to Project instead of String, need changes in projects.jspf too.
        for (String proj : projects) {
            try {
                SuperIndexSearcher searcher = getIndexSearcher(proj);
                subreaders[ii++] = searcher.getIndexReader();
                searcherList.add(searcher);
            } catch (IOException | NullPointerException ex) {
                LOGGER.log(Level.SEVERE,
                    "cannot get IndexReader for project " + proj, ex);
                return null;
            }
        }
        MultiReader multiReader = null;
        try {
            multiReader = new MultiReader(subreaders, true);
        } catch (IOException ex) {
            LOGGER.log(Level.SEVERE,
                "cannot construct MultiReader for set of projects", ex);
        }
        return multiReader;
    }

    public void startExpirationTimer() {
        messagesContainer.setMessageLimit(getMessageLimit());
        messagesContainer.startExpirationTimer();
    }

    public void stopExpirationTimer() {
        messagesContainer.stopExpirationTimer();
    }

    /**
     * Get the default set of messages for the main tag.
     *
     * @return set of messages
     */
    public SortedSet<AcceptedMessage> getMessages() {
        return messagesContainer.getMessages();
    }

    /**
     * Get the set of messages for the arbitrary tag.
     *
     * @param tag the message tag
     * @return set of messages
     */
    public SortedSet<AcceptedMessage> getMessages(final String tag) {
        return messagesContainer.getMessages(tag);
    }

    /**
     * Add a message to the application.
     * Also schedules a expiration timer to remove this message after its expiration.
     *
     * @param message the message
     */
    public void addMessage(final Message message) {
        messagesContainer.addMessage(message);
    }

    /**
     * Remove all messages containing at least one of the tags.
     * @param tags set of tags
     * @param text message text (can be null, empty)
     */
    public void removeAnyMessage(final Set<String> tags, final String text) {
        messagesContainer.removeAnyMessage(tags, text);
    }

    /**
     * @return all messages regardless their tag
     */
    public Set<AcceptedMessage> getAllMessages() {
        return messagesContainer.getAllMessages();
    }

    public Path getDtagsEftarPath() {
        return syncReadConfiguration(Configuration::getDtagsEftarPath);
    }

    /**
     * Get the eftar file, which contains definition tags for path descriptions.
     *
     * @return {@code null} if there is no such file, the file otherwise.
     */
    public File getDtagsEftar() {
        if (dtagsEftar == null) {
            File tmp = getDtagsEftarPath().toFile();
            if (tmp.canRead()) {
                dtagsEftar = tmp;
            }
        }
        return dtagsEftar;
    }

    public SuggesterConfig getSuggesterConfig() {
        return syncReadConfiguration(Configuration::getSuggesterConfig);
    }

    public void setSuggesterConfig(SuggesterConfig suggesterConfig) {
        syncWriteConfiguration(suggesterConfig, Configuration::setSuggesterConfig);
    }

    public BaseStatsdConfig getBaseStatsdConfig() {
        return syncReadConfiguration(Configuration::getStatsdConfig);
    }

    public void setBaseStatsdConfig(BaseStatsdConfig baseStatsdConfig) {
        syncWriteConfiguration(baseStatsdConfig, Configuration::setStatsdConfig);
    }

    public BaseGraphiteConfig getBaseGraphiteConfig() {
        return syncReadConfiguration(Configuration::getGraphiteConfig);
    }

    public void setBaseGraphiteConfig(BaseGraphiteConfig baseGraphiteConfig) {
        syncWriteConfiguration(baseGraphiteConfig, Configuration::setGraphiteConfig);
    }

    public MeterRegistryType getWebAppMeterRegistryType() {
        return syncReadConfiguration(Configuration::getWebAppMeterRegistryType);
    }

    public void setWebAppMeterRegistryType(MeterRegistryType registryType) {
        syncWriteConfiguration(registryType, Configuration::setWebAppMeterRegistryType);
    }

    public MeterRegistryType getIndexerMeterRegistryType() {
        return syncReadConfiguration(Configuration::getIndexerMeterRegistryType);
    }

    public void setIndexerAppMeterRegistryType(MeterRegistryType registryType) {
        syncWriteConfiguration(registryType, Configuration::setIndexerMeterRegistryType);
    }

    /**
     * Applies the specified function to the runtime configuration, after having
     * obtained the configuration read-lock (and releasing afterward).
     * @param function a defined function
     * @param <R> the type of the result of the function
     * @return the function result
     */
    public <R> R syncReadConfiguration(Function<Configuration, R> function) {
        try (ResourceLock resourceLock = configLock.readLockAsResource()) {
            //noinspection ConstantConditions to avoid warning of no reference to auto-closeable
            assert resourceLock != null;
            return function.apply(configuration);
        }
    }

    /**
     * Performs the specified operation which is provided the runtime
     * configuration and the specified argument, after first having obtained the
     * configuration write-lock (and releasing afterward).
     * @param <V> the type of the input to the operation
     * @param v the input argument
     * @param consumer a defined consumer
     */
    public <V> void syncWriteConfiguration(V v, ConfigurationValueConsumer<V> consumer) {
        try (ResourceLock resourceLock = configLock.writeLockAsResource()) {
            //noinspection ConstantConditions to avoid warning of no reference to auto-closeable
            assert resourceLock != null;
            consumer.accept(configuration, v);
        }
    }

    private int getMessageLimit() {
        return syncReadConfiguration(Configuration::getMessageLimit);
    }
}
