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
 * Copyright (c) 2007, 2020, Oracle and/or its affiliates. All rights reserved.
 * Portions Copyright (c) 2017-2020, Chris Fraire <cfraire@me.com>.
 * Portions Copyright (c) 2020, Aleksandr Kirillov <alexkirillovsamara@gmail.com>.
 */
package org.opengrok.indexer.configuration;

import java.beans.ExceptionListener;
import java.beans.XMLDecoder;
import java.beans.XMLEncoder;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

import org.opengrok.indexer.authorization.AuthControlFlag;
import org.opengrok.indexer.authorization.AuthorizationStack;
import org.opengrok.indexer.history.RepositoryInfo;
import org.opengrok.indexer.logger.LoggerFactory;


/**
 * Placeholder class for all configuration variables. Due to the multi-threaded
 * nature of the web application, each thread will use the same instance of the
 * configuration object for each page request. Class and methods should have
 * package scope, but that didn't work with the XMLDecoder/XMLEncoder.
 * <p>
 * This should be as close to a
 * <a href="https://en.wikipedia.org/wiki/Plain_old_Java_object">POJO</a> as
 * possible.
 */
public final class Configuration {

    private static final Logger LOGGER = LoggerFactory.getLogger(Configuration.class);
    public static final String PLUGIN_DIRECTORY_DEFAULT = "plugins";

    /**
     * A check if a pattern contains at least one pair of parentheses meaning
     * that there is at least one capture group. This group must not be empty.
     */
    private static final String PATTERN_SINGLE_GROUP = ".*\\([^\\)]+\\).*";
    /**
     * Error string for invalid patterns without a single group. This is passed
     * as a first argument to the constructor of PatternSyntaxException and in
     * the output it is followed by the invalid pattern.
     *
     * @see PatternSyntaxException
     * @see #PATTERN_SINGLE_GROUP
     */
    private static final String PATTERN_MUST_CONTAIN_GROUP = "The pattern must contain at least one non-empty group -";
    /**
     * Error string for negative numbers (could be int, double, long, ...).
     * First argument is the name of the property, second argument is the actual
     * value.
     */
    private static final String NEGATIVE_NUMBER_ERROR = "Invalid value for \"%s\" - \"%s\". Expected value greater or equal than 0";
    /**
     * Error string for non-positive numbers (could be int, double, long, ...).
     * First argument is the name of the property, second argument is the actual
     * value.
     */
    private static final String NONPOSITIVE_NUMBER_ERROR =
        "Invalid value for \"%s\" - \"%s\". Expected value greater than 0";

    /**
     * Path to {@code ctags} binary.
     */
    private String ctags;
    private boolean webappCtags;

    /**
     * A defined value to specify the mandoc binary or else null so that mandoc
     * will be cross-referenced using {@code PlainXref}.
     */
    private String mandoc;

    /**
     * Should the history log be cached?
     */
    private boolean historyCache;
    /**
     * The maximum time in milliseconds {@code HistoryCache.get()} can take
     * before its result is cached.
     */
    private int historyCacheTime;
    /**
     * flag to generate history. This is bigger hammer than @{code historyCache}
     * above. If set to false, no history query will be ever made and the webapp
     * will not display any history related links/allow any history queries.
     */
    private boolean historyEnabled;
    /**
     * maximum number of messages in webapp.
     */
    private int messageLimit;
    /**
     * Directory with authorization plug-ins. Default value is
     * {@code dataRoot/../plugins} (can be {@code /var/opengrok/plugins} if dataRoot is
     * {@code /var/opengrok/data}).
     */
    private String pluginDirectory;
    /**
     * Enable watching the plug-in directory for changes in real time. Suitable
     * for development.
     */
    private boolean authorizationWatchdogEnabled;
    private AuthorizationStack pluginStack;
    private Map<String, Project> projects; // project name -> Project
    private Set<Group> groups;
    private String sourceRoot;
    private String dataRoot;
    /**
     * Directory with include files for web application (header, footer, etc.).
     */
    private String includeRoot;
    private List<RepositoryInfo> repositories;

    private boolean generateHtml;
    /**
     * Default projects will be used, when no project is selected and no project
     * is in cookie, so basically only the first time a page is opened,
     * or when web cookies are cleared.
     */
    private Set<Project> defaultProjects;
    /**
     * Default size of memory to be used for flushing of Lucene docs per thread.
     * Lucene 4.x uses 16MB and 8 threads, so below is a nice tunable.
     */
    private double ramBufferSize;
    /**
     * If below is set, then we count how many files per project we need to
     * process and print percentage of completion per project.
     */
    private boolean printProgress;
    private boolean allowLeadingWildcard;
    private IgnoredNames ignoredNames;
    private Filter includedNames;
    private String userPage;
    private String userPageSuffix;
    private String bugPage;
    private String bugPattern;
    private String reviewPage;
    private String reviewPattern;
    private String webappLAF;
    private RemoteSCM remoteScmSupported;
    private boolean optimizeDatabase;
    private boolean quickContextScan;

    private LuceneLockName luceneLocking = LuceneLockName.OFF;
    private boolean compressXref;
    private boolean indexVersionedFilesOnly;
    private int indexingParallelism;
    private int historyParallelism;
    private int historyRenamedParallelism;
    private boolean tagsEnabled;
    private int hitsPerPage;
    private int cachePages;
    private short contextLimit; // initialized non-zero in ctor
    private short contextSurround;
    private boolean lastEditedDisplayMode;
    private String CTagsExtraOptionsFile;
    private int scanningDepth;
    private int nestingMaximum;
    private Set<String> allowedSymlinks;
    private Set<String> canonicalRoots;
    private boolean obfuscatingEMailAddresses;
    private boolean chattyStatusPage;
    private final Map<String, String> cmds;  // repository type -> command
    private int tabSize;
    private int indexerCommandTimeout; // in seconds
    private int interactiveCommandTimeout; // in seconds
    private int webappStartCommandTimeout; // in seconds
    private int restfulCommandTimeout; // in seconds
    private long ctagsTimeout; // in seconds
    private boolean scopesEnabled;
    private boolean projectsEnabled;
    private boolean foldingEnabled;
    /*
     * Set to false if we want to disable fetching history of individual files
     * (by running appropriate SCM command) when the history is not found
     * in history cache for repositories capable of fetching history for
     * directories. This option affects file based history cache only.
     */
    private boolean fetchHistoryWhenNotInCache;
    /*
     * Set to false to disable extended handling of history of files across
     * renames, i.e. support getting diffs of revisions across renames
     * for capable repositories.
     */
    private boolean handleHistoryOfRenamedFiles;

    public static final double defaultRamBufferSize = 16;

    /**
     * The directory hierarchy depth to limit the scanning for repositories.
     * E.g. if the /mercurial/ directory (relative to source root) is a repository
     * and /mercurial/usr/closed/ is sub-repository, the latter will be discovered
     * only if the depth is set to 2 or greater.
     */
    public static final int defaultScanningDepth = 2;

    /**
     * The name of the eftar file relative to the <var>DATA_ROOT</var>, which
     * contains definition tags.
     */
    public static final String EFTAR_DTAGS_NAME = "dtags.eftar";

    /**
     * Revision messages will be collapsible if they exceed this many number of
     * characters. Front end enforces an appropriate minimum.
     */
    private int revisionMessageCollapseThreshold;

    /**
     * Groups are collapsed if number of repositories is greater than this
     * threshold. This applies only for non-favorite groups - groups which don't
     * contain a project which is considered as a favorite project for the user.
     * Favorite projects are the projects which the user browses and searches
     * and are stored in a cookie. Favorite groups are always expanded.
     */
    private int groupsCollapseThreshold;

    /**
     * Current indexed message will be collapsible if they exceed this many
     * number of characters. Front end enforces an appropriate minimum.
     */
    private int currentIndexedCollapseThreshold;

    /**
     * Upper bound for number of threads used for performing multi-project
     * searches. This is total for the whole webapp.
     */
    private int MaxSearchThreadCount;

    /**
     * Upper bound for number of threads used for getting revision contents.
     * This is total for the whole webapp.
     */
    private int MaxRevisionThreadCount;

    /**
     * If false, do not display listing or projects/repositories on the index page.
     */
    private boolean displayRepositories;

    /**
     * If true, list directories first in xref directory listing.
     */
    private boolean listDirsFirst = true;

    /**
     * A flag if the navigate window should be opened by default when browsing
     * the source code of projects.
     */
    private boolean navigateWindowEnabled;

    private SuggesterConfig suggesterConfig = new SuggesterConfig();

    private BaseStatsdConfig statsdConfig = new BaseStatsdConfig();
    private BaseGraphiteConfig graphiteConfig = new BaseGraphiteConfig();

    private Set<String> disabledRepositories;

    /*
     * types of handling history for remote SCM repositories:
     *  ON - index history and display it in webapp
     *  OFF - do not index or display history in webapp
     *  DIRBASED - index history only for repositories capable
     *             of getting history for directories
     *  UIONLY - display history only in webapp (do not index it)
     */
    public enum RemoteSCM {
        ON, OFF, DIRBASED, UIONLY
    }

    private MeterRegistryType webAppMeterRegistryType;
    private MeterRegistryType indexerMeterRegistryType;

    /**
     * Get the default tab size (number of space characters per tab character)
     * to use for each project. If {@code <= 0} tabs are read/write as is.
     *
     * @return current tab size set.
     * @see Project#getTabSize()
     * @see org.opengrok.indexer.analysis.ExpandTabsReader
     */
    public int getTabSize() {
        return tabSize;
    }

    /**
     * Set the default tab size (number of space characters per tab character)
     * to use for each project. If {@code <= 0} tabs are read/write as is.
     *
     * @param tabSize tabsize to set.
     * @see Project#setTabSize(int)
     * @see org.opengrok.indexer.analysis.ExpandTabsReader
     */
    public void setTabSize(int tabSize) {
        this.tabSize = tabSize;
    }

    public int getScanningDepth() {
        return scanningDepth;
    }

    /**
     * Set the scanning depth to a new value.
     *
     * @param scanningDepth the new value
     * @throws IllegalArgumentException when the scanningDepth is negative
     */
    public void setScanningDepth(int scanningDepth) throws IllegalArgumentException {
        if (scanningDepth < 0) {
            throw new IllegalArgumentException(
                    String.format(NEGATIVE_NUMBER_ERROR, "scanningDepth", scanningDepth));
        }
        this.scanningDepth = scanningDepth;
    }

    /**
     * Gets the nesting maximum of repositories. Default is 1.
     */
    public int getNestingMaximum() {
        return nestingMaximum;
    }

    /**
     * Sets the nesting maximum of repositories to a specified value.
     * @param nestingMaximum the new value
     * @throws IllegalArgumentException if {@code nestingMaximum} is negative
     */
    public void setNestingMaximum(int nestingMaximum) throws IllegalArgumentException {
        if (nestingMaximum < 0) {
            throw new IllegalArgumentException(
                    String.format(NEGATIVE_NUMBER_ERROR, "nestingMaximum", nestingMaximum));
        }
        this.nestingMaximum = nestingMaximum;
    }

    public int getIndexerCommandTimeout() {
        return indexerCommandTimeout;
    }

    /**
     * Set the command timeout to a new value.
     *
     * @param timeout the new value
     * @throws IllegalArgumentException when the timeout is negative
     */
    public void setIndexerCommandTimeout(int timeout) throws IllegalArgumentException {
        if (timeout < 0) {
            throw new IllegalArgumentException(
                    String.format(NEGATIVE_NUMBER_ERROR, "commandTimeout", timeout));
        }
        this.indexerCommandTimeout = timeout;
    }

    public int getRestfulCommandTimeout() {
        return restfulCommandTimeout;
    }

    /**
     * Set the command timeout to a new value.
     *
     * @param timeout the new value
     * @throws IllegalArgumentException when the timeout is negative
     */
    public void setRestfulCommandTimeout(int timeout) throws IllegalArgumentException {
        if (timeout < 0) {
            throw new IllegalArgumentException(
                    String.format(NEGATIVE_NUMBER_ERROR, "restfulCommandTimeout", timeout));
        }
        this.restfulCommandTimeout = timeout;
    }

    public int getWebappStartCommandTimeout() {
        return webappStartCommandTimeout;
    }

    /**
     * Set the command timeout to a new value.
     *
     * @param timeout the new value
     * @throws IllegalArgumentException when the timeout is negative
     */
    public void setWebappStartCommandTimeout(int timeout) throws IllegalArgumentException {
        if (timeout < 0) {
            throw new IllegalArgumentException(
                    String.format(NEGATIVE_NUMBER_ERROR, "webappStartCommandTimeout", timeout));
        }
        this.webappStartCommandTimeout = timeout;
    }

    public int getInteractiveCommandTimeout() {
        return interactiveCommandTimeout;
    }

    /**
     * Set the interactive command timeout to a new value.
     *
     * @param timeout the new value
     * @throws IllegalArgumentException when the timeout is negative
     */
    public void setInteractiveCommandTimeout(int timeout) throws IllegalArgumentException {
        if (timeout < 0) {
            throw new IllegalArgumentException(
                    String.format(NEGATIVE_NUMBER_ERROR, "interactiveCommandTimeout", timeout));
        }
        this.interactiveCommandTimeout = timeout;
    }

    public long getCtagsTimeout() {
        return ctagsTimeout;
    }

    /**
     * Set the ctags timeout to a new value.
     *
     * @param timeout the new value
     * @throws IllegalArgumentException when the timeout is negative
     */
    public void setCtagsTimeout(long timeout) throws IllegalArgumentException {
        if (timeout < 0) {
            throw new IllegalArgumentException(
                    String.format(NEGATIVE_NUMBER_ERROR, "ctagsTimeout", timeout));
        }
        this.ctagsTimeout = timeout;
    }

    public boolean isLastEditedDisplayMode() {
        return lastEditedDisplayMode;
    }

    public void setLastEditedDisplayMode(boolean lastEditedDisplayMode) {
        this.lastEditedDisplayMode = lastEditedDisplayMode;
    }

    public int getGroupsCollapseThreshold() {
        return groupsCollapseThreshold;
    }

    /**
     * Set the groups collapse threshold to a new value.
     *
     * @param groupsCollapseThreshold the new value
     * @throws IllegalArgumentException when the timeout is negative
     */
    public void setGroupsCollapseThreshold(int groupsCollapseThreshold) throws IllegalArgumentException {
        if (groupsCollapseThreshold < 0) {
            throw new IllegalArgumentException(
                    String.format(NEGATIVE_NUMBER_ERROR, "groupsCollapseThreshold", groupsCollapseThreshold));
        }
        this.groupsCollapseThreshold = groupsCollapseThreshold;
    }

    /**
     * Creates a new instance of Configuration with default values.
     */
    public Configuration() {
        // This list of calls is sorted alphabetically so please keep it.
        cmds = new HashMap<>();
        setAllowLeadingWildcard(true);
        setAllowedSymlinks(new HashSet<>());
        setAuthorizationWatchdogEnabled(false);
        //setBugPage("http://bugs.myserver.org/bugdatabase/view_bug.do?bug_id=");
        setBugPattern("\\b([12456789][0-9]{6})\\b");
        setCachePages(5);
        setCanonicalRoots(new HashSet<>());
        setIndexerCommandTimeout(600); // 10 minutes
        setRestfulCommandTimeout(60);
        setInteractiveCommandTimeout(30);
        setWebappStartCommandTimeout(5);
        setCompressXref(true);
        setContextLimit((short) 10);
        //contextSurround is default(short)
        //ctags is default(String)
        setCtagsTimeout(10);
        setCurrentIndexedCollapseThreshold(27);
        setDataRoot(null);
        setDisplayRepositories(true);
        setFetchHistoryWhenNotInCache(true);
        setFoldingEnabled(true);
        setGenerateHtml(true);
        setGroups(new TreeSet<>());
        setGroupsCollapseThreshold(4);
        setHandleHistoryOfRenamedFiles(false);
        setHistoryCache(true);
        setHistoryCacheTime(30);
        setHistoryEnabled(true);
        setHitsPerPage(25);
        setIgnoredNames(new IgnoredNames());
        setIncludedNames(new Filter());
        setIndexVersionedFilesOnly(false);
        setLastEditedDisplayMode(true);
        //luceneLocking default is OFF
        //mandoc is default(String)
        setMaxSearchThreadCount(2 * Runtime.getRuntime().availableProcessors());
        setMaxRevisionThreadCount(Runtime.getRuntime().availableProcessors());
        setMessageLimit(500);
        setNavigateWindowEnabled(false);
        setNestingMaximum(1);
        setOptimizeDatabase(true);
        setPluginDirectory(null);
        setPluginStack(new AuthorizationStack(AuthControlFlag.REQUIRED, "default stack"));
        setPrintProgress(false);
        setDisabledRepositories(new HashSet<>());
        setProjects(new ConcurrentHashMap<>());
        setQuickContextScan(true);
        //below can cause an outofmemory error, since it is defaulting to NO LIMIT
        setRamBufferSize(defaultRamBufferSize); //MB
        setRemoteScmSupported(RemoteSCM.OFF);
        setRepositories(new ArrayList<>());
        //setReviewPage("http://arc.myserver.org/caselog/PSARC/");
        setReviewPattern("\\b(\\d{4}/\\d{3})\\b"); // in form e.g. PSARC 2008/305
        setRevisionMessageCollapseThreshold(200);
        setScanningDepth(defaultScanningDepth); // default depth of scanning for repositories
        setScopesEnabled(true);
        setSourceRoot(null);
        //setTabSize(4);
        setTagsEnabled(false);
        //setUserPage("http://www.myserver.org/viewProfile.jspa?username=");
        // Set to empty string so we can append it to the URL
        // unconditionally later.
        setUserPageSuffix("");
        setWebappLAF("default");
        // webappCtags is default(boolean)
        setWebAppMeterRegistryType(MeterRegistryType.PROMETHEUS);
        setIndexerMeterRegistryType(MeterRegistryType.NONE);
    }

    public String getRepoCmd(String clazzName) {
        return cmds.get(clazzName);
    }

    public String setRepoCmd(String clazzName, String cmd) {
        if (clazzName == null) {
            return null;
        }
        if (cmd == null || cmd.length() == 0) {
            return cmds.remove(clazzName);
        }
        return cmds.put(clazzName, cmd);
    }

    // just to satisfy bean/de|encoder stuff
    public Map<String, String> getCmds() {
        return Collections.unmodifiableMap(cmds);
    }

    /**
     * @see org.opengrok.indexer.web.messages.MessagesContainer
     *
     * @return int the current message limit
     */
    public int getMessageLimit() {
        return messageLimit;
    }

    /**
     * @see org.opengrok.indexer.web.messages.MessagesContainer
     *
     * @param messageLimit the limit
     * @throws IllegalArgumentException when the limit is negative
     */
    public void setMessageLimit(int messageLimit) throws IllegalArgumentException {
        if (messageLimit < 0) {
            throw new IllegalArgumentException(
                    String.format(NEGATIVE_NUMBER_ERROR, "messageLimit", messageLimit));
        }
        this.messageLimit = messageLimit;
    }

    public String getPluginDirectory() {
        return pluginDirectory;
    }

    public void setPluginDirectory(String pluginDirectory) {
        this.pluginDirectory = pluginDirectory;
    }

    public boolean isAuthorizationWatchdogEnabled() {
        return authorizationWatchdogEnabled;
    }

    public void setAuthorizationWatchdogEnabled(boolean authorizationWatchdogEnabled) {
        this.authorizationWatchdogEnabled = authorizationWatchdogEnabled;
    }

    public AuthorizationStack getPluginStack() {
        return pluginStack;
    }

    public void setPluginStack(AuthorizationStack pluginStack) {
        this.pluginStack = pluginStack;
    }

    public void setCmds(Map<String, String> cmds) {
        this.cmds.clear();
        this.cmds.putAll(cmds);
    }

    /**
     * Gets the configuration's ctags command. Default is null.
     * @return the configured value
     */
    public String getCtags() {
        return ctags;
    }

    /**
     * Sets the configuration's ctags command.
     * @param ctags A program name (full-path if necessary) or {@code null}
     */
    public void setCtags(String ctags) {
        this.ctags = ctags;
    }

    /**
     * Gets the configuration's mandoc command. Default is {@code null}.
     * @return the configured value
     */
    public String getMandoc() {
        return mandoc;
    }

    /**
     * Sets the configuration's mandoc command.
     * @param value A program name (full-path if necessary) or {@code null}
     */
    public void setMandoc(String value) {
        this.mandoc = value;
    }

    /**
     * Gets the total number of context lines per file to show in cases where it
     * is limited. Default is 10.
     * @return a value greater than zero
     */
    public short getContextLimit() {
        return contextLimit;
    }

    /**
     * Sets the total number of context lines per file to show in cases where it
     * is limited.
     * @param value a value greater than zero
     * @throws IllegalArgumentException if {@code value} is not positive
     */
    public void setContextLimit(short value) throws IllegalArgumentException {
        if (value < 1) {
            throw new IllegalArgumentException(
                String.format(NONPOSITIVE_NUMBER_ERROR, "contextLimit", value));
        }
        this.contextLimit = value;
    }

    /**
     * Gets the number of context lines to show before or after any match.
     * Default is zero.
     * @return a value greater than or equal to zero
     */
    public short getContextSurround() {
        return contextSurround;
    }

    /**
     * Sets the number of context lines to show before or after any match.
     * @param value a value greater than or equal to zero
     * @throws IllegalArgumentException if {@code value} is negative
     */
    public void setContextSurround(short value)
            throws IllegalArgumentException {
        if (value < 0) {
            throw new IllegalArgumentException(
                String.format(NEGATIVE_NUMBER_ERROR, "contextSurround", value));
        }
        this.contextSurround = value;
    }

    public int getCachePages() {
        return cachePages;
    }

    /**
     * Set the cache pages to a new value.
     *
     * @param cachePages the new value
     * @throws IllegalArgumentException when the cachePages is negative
     */
    public void setCachePages(int cachePages) throws IllegalArgumentException {
        if (cachePages < 0) {
            throw new IllegalArgumentException(
                    String.format(NEGATIVE_NUMBER_ERROR, "cachePages", cachePages));
        }
        this.cachePages = cachePages;
    }

    public int getHitsPerPage() {
        return hitsPerPage;
    }

    /**
     * Set the hits per page to a new value.
     *
     * @param hitsPerPage the new value
     * @throws IllegalArgumentException when the hitsPerPage is negative
     */
    public void setHitsPerPage(int hitsPerPage) throws IllegalArgumentException {
        if (hitsPerPage < 0) {
            throw new IllegalArgumentException(
                    String.format(NEGATIVE_NUMBER_ERROR, "hitsPerPage", hitsPerPage));
        }
        this.hitsPerPage = hitsPerPage;
    }

    /**
     * Should the history be enabled ?
     *
     * @return {@code true} if history is enabled, {@code false} otherwise
     */
    public boolean isHistoryEnabled() {
        return historyEnabled;
    }

    /**
     * Set whether history should be enabled.
     *
     * @param flag if {@code true} enable history
     */
    public void setHistoryEnabled(boolean flag) {
        this.historyEnabled = flag;
    }

    /**
     * Should the history log be cached?
     *
     * @return {@code true} if a {@code HistoryCache} implementation should be
     * used, {@code false} otherwise
     */
    public boolean isHistoryCache() {
        return historyCache;
    }

    /**
     * Set whether history should be cached.
     *
     * @param historyCache if {@code true} enable history cache
     */
    public void setHistoryCache(boolean historyCache) {
        this.historyCache = historyCache;
    }
    
    /**
     * How long can a history request take before it's cached? If the time is
     * exceeded, the result is cached. This setting only affects
     * {@code FileHistoryCache}.
     *
     * @return the maximum time in milliseconds a history request can take
     * before it's cached
     */
    public int getHistoryCacheTime() {
        return historyCacheTime;
    }

    /**
     * Set the maximum time a history request can take before it's cached. This
     * setting is only respected if {@code FileHistoryCache} is used.
     *
     * @param historyCacheTime maximum time in milliseconds
     */
    public void setHistoryCacheTime(int historyCacheTime) {
        this.historyCacheTime = historyCacheTime;
    }

    public boolean isFetchHistoryWhenNotInCache() {
        return fetchHistoryWhenNotInCache;
    }

    public void setFetchHistoryWhenNotInCache(boolean nofetch) {
        this.fetchHistoryWhenNotInCache = nofetch;
    }

    public boolean isHandleHistoryOfRenamedFiles() {
        return handleHistoryOfRenamedFiles;
    }

    public void setHandleHistoryOfRenamedFiles(boolean enable) {
        this.handleHistoryOfRenamedFiles = enable;
    }

    public boolean isNavigateWindowEnabled() {
        return navigateWindowEnabled;
    }

    public void setNavigateWindowEnabled(boolean navigateWindowEnabled) {
        this.navigateWindowEnabled = navigateWindowEnabled;
    }

    public Map<String, Project> getProjects() {
        return projects;
    }

    public void setProjects(Map<String, Project> projects) {
        this.projects = projects;
    }

    /**
     * Adds a group to the set. This is performed upon configuration parsing
     *
     * @param group group
     * @throws IOException when group is not unique across the set
     */
    public void addGroup(Group group) throws IOException {
        if (!groups.add(group)) {
            throw new IOException(
                    String.format("Duplicate group name '%s' in configuration.",
                            group.getName()));
        }
    }

    public Set<Group> getGroups() {
        return groups;
    }

    public void setGroups(Set<Group> groups) {
        this.groups = groups;
    }

    public String getSourceRoot() {
        return sourceRoot;
    }

    public void setSourceRoot(String sourceRoot) {
        this.sourceRoot = sourceRoot;
    }

    public String getDataRoot() {
        return dataRoot;
    }

    /**
     * Sets data root.
     *
     * This method also sets the pluginDirectory if it is not already set.
     *
     * @see #setPluginDirectory(java.lang.String)
     *
     * @param dataRoot data root path
     */
    public void setDataRoot(String dataRoot) {
        if (dataRoot != null && getPluginDirectory() == null) {
            setPluginDirectory(dataRoot + "/../" + PLUGIN_DIRECTORY_DEFAULT);
        }
        this.dataRoot = dataRoot;
    }

    /**
     * If {@link #includeRoot} is not set, {@link #dataRoot} will be returned.
     * @return web include root directory
     */
    public String getIncludeRoot() {
        return includeRoot != null ? includeRoot : dataRoot;
    }
    
    public void setIncludeRoot(String newRoot) {
        this.includeRoot = newRoot;
    }
    
    public List<RepositoryInfo> getRepositories() {
        return repositories;
    }

    public void setRepositories(List<RepositoryInfo> repositories) {
        this.repositories = repositories;
    }

    public void addRepositories(List<RepositoryInfo> repositories) {
        this.repositories.addAll(repositories);
    }

    public void setGenerateHtml(boolean generateHtml) {
        this.generateHtml = generateHtml;
    }

    public boolean isGenerateHtml() {
        return generateHtml;
    }

    public void setDefaultProjects(Set<Project> defaultProjects) {
        this.defaultProjects = defaultProjects;
    }

    public Set<Project> getDefaultProjects() {
        return defaultProjects;
    }

    public double getRamBufferSize() {
        return ramBufferSize;
    }

    /**
     * Set size of memory to be used for flushing docs (default 16 MB) (this can
     * improve index speed a LOT) note that this is per thread (lucene uses 8
     * threads by default in 4.x).
     *
     * @param ramBufferSize new size in MB
     */
    public void setRamBufferSize(double ramBufferSize) {
        this.ramBufferSize = ramBufferSize;
    }

    public boolean isPrintProgress() {
        return printProgress;
    }

    public void setPrintProgress(boolean printProgress) {
        this.printProgress = printProgress;
    }

    public void setAllowLeadingWildcard(boolean allowLeadingWildcard) {
        this.allowLeadingWildcard = allowLeadingWildcard;
    }

    public boolean isAllowLeadingWildcard() {
        return allowLeadingWildcard;
    }

    public boolean isQuickContextScan() {
        return quickContextScan;
    }

    public void setQuickContextScan(boolean quickContextScan) {
        this.quickContextScan = quickContextScan;
    }

    public void setIgnoredNames(IgnoredNames ignoredNames) {
        this.ignoredNames = ignoredNames;
    }

    public IgnoredNames getIgnoredNames() {
        return ignoredNames;
    }

    public void setIncludedNames(Filter includedNames) {
        this.includedNames = includedNames;
    }

    public Filter getIncludedNames() {
        return includedNames;
    }

    public void setUserPage(String userPage) {
        this.userPage = userPage;
    }

    public String getUserPage() {
        return userPage;
    }

    public void setUserPageSuffix(String userPageSuffix) {
        this.userPageSuffix = userPageSuffix;
    }

    public String getUserPageSuffix() {
        return userPageSuffix;
    }

    public void setBugPage(String bugPage) {
        this.bugPage = bugPage;
    }

    public String getBugPage() {
        return bugPage;
    }

    /**
     * Set the bug pattern to a new value.
     *
     * @param bugPattern the new pattern
     * @throws PatternSyntaxException when the pattern is not a valid regexp or
     * does not contain at least one capture group and the group does not
     * contain a single character
     */
    public void setBugPattern(String bugPattern) throws PatternSyntaxException {
        if (!bugPattern.matches(PATTERN_SINGLE_GROUP)) {
            throw new PatternSyntaxException(PATTERN_MUST_CONTAIN_GROUP, bugPattern, 0);
        }
        this.bugPattern = Pattern.compile(bugPattern).toString();
    }

    public String getBugPattern() {
        return bugPattern;
    }

    public String getReviewPage() {
        return reviewPage;
    }

    public void setReviewPage(String reviewPage) {
        this.reviewPage = reviewPage;
    }

    public String getReviewPattern() {
        return reviewPattern;
    }

    /**
     * Set the review pattern to a new value.
     *
     * @param reviewPattern the new pattern
     * @throws PatternSyntaxException when the pattern is not a valid regexp or
     * does not contain at least one capture group and the group does not
     * contain a single character
     */
    public void setReviewPattern(String reviewPattern) throws PatternSyntaxException {
        if (!reviewPattern.matches(PATTERN_SINGLE_GROUP)) {
            throw new PatternSyntaxException(PATTERN_MUST_CONTAIN_GROUP, reviewPattern, 0);
        }
        this.reviewPattern = Pattern.compile(reviewPattern).toString();
    }

    public String getWebappLAF() {
        return webappLAF;
    }

    public void setWebappLAF(String webappLAF) {
        this.webappLAF = webappLAF;
    }

    /**
     * Gets a value indicating if the web app should run ctags as necessary.
     */
    public boolean isWebappCtags() {
        return webappCtags;
    }

    /**
     * Sets a value indicating if the web app should run ctags as necessary.
     */
    public void setWebappCtags(boolean value) {
        this.webappCtags = value;
    }

    public RemoteSCM getRemoteScmSupported() {
        return remoteScmSupported;
    }

    public void setRemoteScmSupported(RemoteSCM remoteScmSupported) {
        this.remoteScmSupported = remoteScmSupported;
    }

    public boolean isOptimizeDatabase() {
        return optimizeDatabase;
    }

    public void setOptimizeDatabase(boolean optimizeDatabase) {
        this.optimizeDatabase = optimizeDatabase;
    }

    public LuceneLockName getLuceneLocking() {
        return luceneLocking;
    }

    /**
     * @param value off|on|simple|native where "on" is an alias for "simple".
     * Any other value is a fallback alias for "off" (with a logged warning).
     */
    public void setLuceneLocking(LuceneLockName value) {
        this.luceneLocking = value;
    }

    public void setCompressXref(boolean compressXref) {
        this.compressXref = compressXref;
    }

    public boolean isCompressXref() {
        return compressXref;
    }

    public boolean isIndexVersionedFilesOnly() {
        return indexVersionedFilesOnly;
    }

    public void setIndexVersionedFilesOnly(boolean indexVersionedFilesOnly) {
        this.indexVersionedFilesOnly = indexVersionedFilesOnly;
    }

    public int getIndexingParallelism() {
        return indexingParallelism;
    }

    public void setIndexingParallelism(int value) {
        this.indexingParallelism = value > 0 ? value : 0;
    }

    public int getHistoryParallelism() {
        return historyParallelism;
    }

    public void setHistoryParallelism(int value) {
        this.historyParallelism = value > 0 ? value : 0;
    }

    public int getHistoryRenamedParallelism() {
        return historyRenamedParallelism;
    }

    public void setHistoryRenamedParallelism(int value) {
        this.historyRenamedParallelism = value > 0 ? value : 0;
    }

    public boolean isTagsEnabled() {
        return this.tagsEnabled;
    }

    public void setTagsEnabled(boolean tagsEnabled) {
        this.tagsEnabled = tagsEnabled;
    }

    public void setRevisionMessageCollapseThreshold(int threshold) {
        this.revisionMessageCollapseThreshold = threshold;
    }

    public int getRevisionMessageCollapseThreshold() {
        return this.revisionMessageCollapseThreshold;
    }

    public int getCurrentIndexedCollapseThreshold() {
        return currentIndexedCollapseThreshold;
    }

    public void setCurrentIndexedCollapseThreshold(int currentIndexedCollapseThreshold) {
        this.currentIndexedCollapseThreshold = currentIndexedCollapseThreshold;
    }

    public boolean getDisplayRepositories() {
        return this.displayRepositories;
    }

    public void setDisplayRepositories(boolean flag) {
        this.displayRepositories = flag;
    }

    public boolean getListDirsFirst() {
        return listDirsFirst;
    }

    public void setListDirsFirst(boolean flag) {
        listDirsFirst = flag;
    }

    /**
     * The name of the file relative to the <var>DATA_ROOT</var>, which should
     * be included into the footer of generated web pages.
     */
    public static final String FOOTER_INCLUDE_FILE = "footer_include";

    /**
     * The name of the file relative to the <var>DATA_ROOT</var>, which should
     * be included into the header of generated web pages.
     */
    public static final String HEADER_INCLUDE_FILE = "header_include";

    /**
     * The name of the file relative to the <var>DATA_ROOT</var>, which should
     * be included into the body of web app's "Home" page.
     */
    public static final String BODY_INCLUDE_FILE = "body_include";

    /**
     * The name of the file relative to the <var>DATA_ROOT</var>, which should
     * be included into the error page handling access forbidden errors - HTTP
     * code 403 Forbidden.
     */
    public static final String E_FORBIDDEN_INCLUDE_FILE = "error_forbidden_include";


    /**
     * The name of the file relative to the <var>DATA_ROOT</var>, which should
     * be included into the HTTP header of generated web pages.
     */
    public static final String HTTP_HEADER_INCLUDE_FILE = "http_header_include";

    /**
     * @return path to the file holding compiled path descriptions for the web application
     */
    public Path getDtagsEftarPath() {
        return Paths.get(getDataRoot(), "index", EFTAR_DTAGS_NAME);
    }

    public String getCTagsExtraOptionsFile() {
        return CTagsExtraOptionsFile;
    }

    public void setCTagsExtraOptionsFile(String filename) {
        this.CTagsExtraOptionsFile = filename;
    }

    public Set<String> getAllowedSymlinks() {
        return allowedSymlinks;
    }

    public void setAllowedSymlinks(Set<String> allowedSymlinks) {
        this.allowedSymlinks = allowedSymlinks;
    }

    public Set<String> getCanonicalRoots() {
        return canonicalRoots;
    }

    public void setCanonicalRoots(Set<String> canonicalRoots) {
        this.canonicalRoots = canonicalRoots;
    }

    public boolean isObfuscatingEMailAddresses() {
        return obfuscatingEMailAddresses;
    }

    public void setObfuscatingEMailAddresses(boolean obfuscate) {
        this.obfuscatingEMailAddresses = obfuscate;
    }

    public boolean isChattyStatusPage() {
        return chattyStatusPage;
    }

    public void setChattyStatusPage(boolean chattyStatusPage) {
        this.chattyStatusPage = chattyStatusPage;
    }

    public boolean isScopesEnabled() {
        return scopesEnabled;
    }

    public void setScopesEnabled(boolean scopesEnabled) {
        this.scopesEnabled = scopesEnabled;
    }

    public boolean isFoldingEnabled() {
        return foldingEnabled;
    }

    public void setFoldingEnabled(boolean foldingEnabled) {
        this.foldingEnabled = foldingEnabled;
    }

    public int getMaxSearchThreadCount() {
        return MaxSearchThreadCount;
    }

    public void setMaxSearchThreadCount(int count) {
        this.MaxSearchThreadCount = count;
    }

    public int getMaxRevisionThreadCount() {
        return MaxRevisionThreadCount;
    }

    public void setMaxRevisionThreadCount(int count) {
        this.MaxRevisionThreadCount = count;
    }

    public boolean isProjectsEnabled() {
        return projectsEnabled;
    }

    public void setProjectsEnabled(boolean flag) {
        this.projectsEnabled = flag;
    }

    public SuggesterConfig getSuggesterConfig() {
        return suggesterConfig;
    }

    public void setSuggesterConfig(final SuggesterConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Cannot set Suggester configuration to null");
        }
        this.suggesterConfig = config;
    }

    public BaseStatsdConfig getStatsdConfig() {
        return statsdConfig;
    }

    public void setStatsdConfig(final BaseStatsdConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Cannot set Statsd configuration to null");
        }
        this.statsdConfig = config;
    }

    public BaseGraphiteConfig getGraphiteConfig() {
        return graphiteConfig;
    }

    public void setGraphiteConfig(BaseGraphiteConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Cannot set Graphite configuration to null");
        }
        this.graphiteConfig = config;
    }

    public MeterRegistryType getWebAppMeterRegistryType() {
        return webAppMeterRegistryType;
    }

    public void setWebAppMeterRegistryType(MeterRegistryType registryType) {
        this.webAppMeterRegistryType = registryType;
    }

    public MeterRegistryType getIndexerMeterRegistryType() {
        return indexerMeterRegistryType;
    }

    public void setIndexerMeterRegistryType(MeterRegistryType registryType) {
        if (registryType == MeterRegistryType.PROMETHEUS) {
            throw new IllegalArgumentException("unsupported registry type");
        }
        this.indexerMeterRegistryType = registryType;
    }

    public Set<String> getDisabledRepositories() {
        return disabledRepositories;
    }

    public void setDisabledRepositories(Set<String> disabledRepositories) {
        this.disabledRepositories = disabledRepositories;
    }

    /**
     * Write the current configuration to a file.
     *
     * @param file the file to write the configuration into
     * @throws IOException if an error occurs
     */
    public void write(File file) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            this.encodeObject(out);
        }
    }

    public String getXMLRepresentationAsString() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        this.encodeObject(bos);
        return bos.toString();
    }

    public void encodeObject(OutputStream out) {
        try (XMLEncoder e = new XMLEncoder(new BufferedOutputStream(out))) {
            e.writeObject(this);
        }
    }

    public static Configuration read(File file) throws IOException {
        LOGGER.log(Level.INFO, "Reading configuration from {0}", file.getCanonicalPath());
        try (FileInputStream in = new FileInputStream(file)) {
            return decodeObject(in);
        }
    }

    public static Configuration makeXMLStringAsConfiguration(String xmlconfig) throws IOException {
        final Configuration ret;
        final ByteArrayInputStream in = new ByteArrayInputStream(xmlconfig.getBytes());
        ret = decodeObject(in);
        return ret;
    }

    private static Configuration decodeObject(InputStream in) throws IOException {
        final Object ret;
        final LinkedList<Exception> exceptions = new LinkedList<>();
        ExceptionListener listener = new ExceptionListener() {
            @Override
            public void exceptionThrown(Exception e) {
                exceptions.addLast(e);
            }
        };

        try (XMLDecoder d = new XMLDecoder(new BufferedInputStream(in), null, listener)) {
            ret = d.readObject();
        }

        if (!(ret instanceof Configuration)) {
            throw new IOException("Not a valid config file");
        }

        if (!exceptions.isEmpty()) {
            // There was an exception during parsing.
            // see {@code addGroup}
            if (exceptions.getFirst() instanceof IOException) {
                throw (IOException) exceptions.getFirst();
            }
            throw new IOException(exceptions.getFirst());
        }

        Configuration conf = ((Configuration) ret);

        // Removes all non root groups.
        // This ensures that when the configuration is reloaded then the set 
        // contains only root groups. Subgroups are discovered again
        // as follows below
        conf.groups.removeIf(new Predicate<Group>() {
            @Override
            public boolean test(Group g) {
                return g.getParent() != null;
            }
        });

        // Traversing subgroups and checking for duplicates,
        // effectively transforms the group tree to a structure (Set)
        // supporting an iterator.
        TreeSet<Group> copy = new TreeSet<>();
        LinkedList<Group> stack = new LinkedList<>(conf.groups);
        while (!stack.isEmpty()) {
            Group group = stack.pollFirst();
            stack.addAll(group.getSubgroups());

            if (!copy.add(group)) {
                throw new IOException(
                        String.format("Duplicate group name '%s' in configuration.",
                                group.getName()));
            }

            // populate groups where the current group in in their subtree
            Group tmp = group.getParent();
            while (tmp != null) {
                tmp.addDescendant(group);
                tmp = tmp.getParent();
            }
        }
        conf.setGroups(copy);

        /*
         * Validate any defined canonicalRoot entries, and only include where
         * validation succeeds.
         */
        if (conf.canonicalRoots != null) {
            conf.canonicalRoots = conf.canonicalRoots.stream().filter(s -> {
                String problem = CanonicalRootValidator.validate(s, "canonicalRoot element");
                if (problem == null) {
                    return true;
                } else {
                    LOGGER.warning(problem);
                    return false;
                }
            }).collect(Collectors.toCollection(HashSet::new));
        }

        return conf;
    }
}
