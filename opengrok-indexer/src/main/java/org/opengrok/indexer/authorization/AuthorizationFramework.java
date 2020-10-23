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
 * Copyright (c) 2016, 2020, Oracle and/or its affiliates. All rights reserved.
 * Portions Copyright (c) 2020, Chris Fraire <cfraire@me.com>.
 */
package org.opengrok.indexer.authorization;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.opengrok.indexer.Metrics;
import org.opengrok.indexer.configuration.Configuration;
import org.opengrok.indexer.configuration.Group;
import org.opengrok.indexer.configuration.Nameable;
import org.opengrok.indexer.configuration.Project;
import org.opengrok.indexer.configuration.RuntimeEnvironment;
import org.opengrok.indexer.framework.PluginFramework;
import org.opengrok.indexer.logger.LoggerFactory;
import org.opengrok.indexer.web.Laundromat;

/**
 * Placeholder for performing authorization checks.
 *
 * @author Krystof Tulinger
 */
public final class AuthorizationFramework extends PluginFramework<IAuthorizationPlugin> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthorizationFramework.class);

    private Counter authStackReloadCounter;
    private Counter authCacheHits;
    private Counter authCacheMisses;
    private Counter authSessionsInvalidated;

    private Timer authTimerPositive;
    private Timer authTimerNegative;

    /**
     * Stack of available plugins/stacks in the order of the execution.
     */
    AuthorizationStack stack;

    /**
     * New stack. This is set by {@code setStack()} and used for delayed
     * reconfiguration in {@code reload()}.
     */
    AuthorizationStack newStack;

    /**
     * Stack of plugins intended for loading.
     */
    AuthorizationStack loadingStack;

    /**
     * Lock for safe reloads.
     */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Keeping track of the number of reloads in this framework. This is
     * used to invalidate the session and force reload the authorization values
     * stored in HTTP session.
     * <p>
     * Starting at 0 and increases with every reload.
     */
    private long pluginVersion = 0;

    // HTTP session attribute that holds plugin version 
    private static final String SESSION_VERSION = "opengrok-authorization-session-version";

    /**
     * Create a new instance of authorization framework with no plugin
     * directory and the default plugin stack.
     */
    public AuthorizationFramework() {
        this(null);
    }

    /**
     * Create a new instance of authorization framework with the plugin
     * directory and the default plugin stack.
     *
     * @param path the plugin directory path
     */
    public AuthorizationFramework(String path) {
        this(path, new AuthorizationStack(AuthControlFlag.REQUIRED, "default stack"));
    }

    /**
     * Create a new instance of authorization framework with the plugin
     * directory and the plugin stack.
     *
     * @param path  the plugin directory path
     * @param stack the top level stack configuration
     */
    public AuthorizationFramework(String path, AuthorizationStack stack) {
        super(IAuthorizationPlugin.class, path);
        this.stack = stack;

        MeterRegistry registry = Metrics.getInstance().getRegistry();
        if (registry != null) {
            authStackReloadCounter = registry.counter("authorization.stack.reload");
            authCacheHits = Counter.builder("authorization.cache").
                    description("authorization cache hits").
                    tag("what", "hits").
                    register(registry);
            authCacheMisses = Counter.builder("authorization.cache").
                    description("authorization cache misses").
                    tag("what", "misses").
                    register(registry);
            authSessionsInvalidated = registry.counter("authorization.sessions.invalidated");

            authTimerPositive = Timer.builder("authorization.latency").
                    description("authorization latency").
                    tag("outcome", "positive").
                    register(registry);
            authTimerNegative = Timer.builder("authorization.latency").
                    description("authorization latency").
                    tag("outcome", "negative").
                    register(registry);
        }
    }

    /**
     * Checks if the request should have an access to project. See
     * {@link #checkAll} for more information about invocation order.
     *
     * @param request request object
     * @param project project object
     * @return true if yes
     * @see #checkAll
     */
    public boolean isAllowed(HttpServletRequest request, Project project) {
        return checkAll(
                request,
                "plugin_framework_project_cache",
                project,
                new AuthorizationEntity.PluginDecisionPredicate() {
                    @Override
                    public boolean decision(IAuthorizationPlugin plugin) {
                        return plugin.isAllowed(request, project);
                    }
                }, new AuthorizationEntity.PluginSkippingPredicate() {
                    @Override
                    public boolean shouldSkip(AuthorizationEntity authEntity) {
                        // shouldn't skip if there is no setup
                        if (authEntity.forProjects().isEmpty() && authEntity.forGroups().isEmpty()) {
                            return false;
                        }

                        // shouldn't skip if the project is contained in the setup
                        if (authEntity.forProjects().contains(project.getName())) {
                            return false;
                        }

                        return true;
                    }
                });
    }

    /**
     * Checks if the request should have an access to group. See
     * {@link #checkAll} for more information about invocation order.
     *
     * @param request request object
     * @param group   group object
     * @return true if yes
     * @see #checkAll
     */
    public boolean isAllowed(HttpServletRequest request, Group group) {
        return checkAll(
                request,
                "plugin_framework_group_cache",
                group,
                new AuthorizationEntity.PluginDecisionPredicate() {
                    @Override
                    public boolean decision(IAuthorizationPlugin plugin) {
                        return plugin.isAllowed(request, group);
                    }
                }, new AuthorizationEntity.PluginSkippingPredicate() {
                    @Override
                    public boolean shouldSkip(AuthorizationEntity authEntity) {
                        // shouldn't skip if there is no setup
                        if (authEntity.forProjects().isEmpty() && authEntity.forGroups().isEmpty()) {
                            return false;
                        }

                        // shouldn't skip if the group is contained in the setup
                        return !authEntity.forGroups().contains(group.getName());
                    }
                });
    }

    /**
     * Get available plugins.
     * <p>
     * This method and couple of following methods use locking because
     * <ol>
     * <li>plugins can be reloaded at anytime</li>
     * <li>requests are pretty asynchronous</li>
     * </ol>
     * <p>
     * So this tries to ensure that there will be no
     * {@code ConcurrentModificationException} or other similar exceptions.
     *
     * @return the stack containing plugins/other stacks
     */
    public AuthorizationStack getStack() {
        lock.readLock().lock();
        try {
            return stack;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Set new value of the authorization stack. This will come into effect
     * only after {@code reload()} is called.
     *
     * @param s new stack to be used
     */
    public void setStack(AuthorizationStack s) {
        this.newStack = s;
    }

    /**
     * Add an entity into the plugin stack.
     *
     * @param stack  the stack
     * @param entity the authorization entity (stack or plugin)
     */
    protected void addPlugin(AuthorizationStack stack, AuthorizationEntity entity) {
        if (stack != null) {
            stack.add(entity);
        }
    }

    /**
     * Add a plug-in into the plug-in stack. This has the same effect as invoking
     * addPlugin(stack, IAuthorizationPlugin, REQUIRED).
     *
     * @param stack  the stack
     * @param plugin the authorization plug-in
     */
    public void addPlugin(AuthorizationStack stack, IAuthorizationPlugin plugin) {
        addPlugin(stack, plugin, AuthControlFlag.REQUIRED);
    }

    /**
     * Add a plug-in into the plug-in array.
     *
     * <h3>Configured plugin</h3>
     * For plug-in that has an entry in configuration, the new plug-in is put
     * in the place respecting the user-defined order of execution.
     *
     * <h3>New plugin</h3>
     * If there is no entry in configuration for this class, the plugin is
     * appended to the end of the plugin stack with flag <code>flag</code>
     *
     * <p><b>The plug-in's load method is NOT invoked at this point</b></p>
     * <p>
     * This has the same effect as invoking
     * {@code addPlugin(new AuthorizationEntity(stack, flag,
     * getClassName(plugin), plugin)}.
     *
     * @param stack  the stack
     * @param plugin the authorization plug-in
     * @param flag   the flag for the new plug-in
     */
    public void addPlugin(AuthorizationStack stack, IAuthorizationPlugin plugin, AuthControlFlag flag) {
        if (stack != null) {
            LOGGER.log(Level.WARNING, "Plugin class \"{0}\" was not found in configuration."
                            + " Appending the plugin at the end of the list with flag \"{1}\"",
                    new Object[]{getClassName(plugin), flag});
            addPlugin(stack, new AuthorizationPlugin(flag, getClassName(plugin), plugin));
        }
    }

    /**
     * Remove and unload all plugins from the stack.
     *
     * @see AuthorizationEntity#unload()
     */
    public void removeAll() {
        unloadAllPlugins(stack);
        stack.getStack().clear();
    }

    private void removeAll(AuthorizationStack stack) {
        unloadAllPlugins(stack);
        stack.getStack().clear();
    }

    /**
     * Load all plugins in the stack. If any plugin has not been loaded yet it
     * is marked as failed.
     *
     * @param stack the stack
     */
    public void loadAllPlugins(AuthorizationStack stack) {
        if (stack != null) {
            stack.load(new TreeMap<>());
        }
    }

    /**
     * Unload all plugins in the stack.
     *
     * @param stack the stack
     */
    public void unloadAllPlugins(AuthorizationStack stack) {
        if (stack != null) {
            stack.unload();
        }
    }

    @Override
    protected void classLoaded(IAuthorizationPlugin plugin) {
        if (!loadingStack.setPlugin(plugin)) {
            LOGGER.log(Level.INFO, "plugin {0} is not configured in the stack", plugin.getClass().getCanonicalName());
        }
    }

    /**
     * Prepare the loading stack for new plugins.
     *
     * @see #classLoaded(IAuthorizationPlugin)
     */
    @Override
    protected void beforeReload() {
        if (this.newStack == null) {
            // Clone a new stack not interfering with the current stack.
            loadingStack = getStack().clone();
        } else {
            loadingStack = this.newStack.clone();
        }
    }

    /**
     * Calling this function forces the framework to reload its stack.
     *
     * <p>
     * Plugins are taken from the pluginDirectory.</p>
     *
     * <p>
     * Old instances in stack are removed and new list of stack is constructed.
     * Unload and load event is fired on each plugin.</p>
     *
     * <p>
     * This method is thread safe with respect to the currently running
     * authorization checks.</p>
     *
     * @see IAuthorizationPlugin#load(java.util.Map)
     * @see IAuthorizationPlugin#unload()
     * @see Configuration#getPluginDirectory()
     */
    @Override
    protected void afterReload() {
        if (stack == null) {
            LOGGER.log(Level.WARNING, "Plugin stack not found in configuration: null. All requests allowed.");
            return;
        }

        // fire load events
        loadAllPlugins(loadingStack);

        AuthorizationStack oldStack;
        /**
         * Replace the stack in a write lock to avoid inconsistent state between
         * the stack change and currently executing requests performing some
         * authorization on the same stack.
         *
         * @see #performCheck is controlled with a read lock
         */
        lock.writeLock().lock();
        try {
            oldStack = stack;
            stack = loadingStack;

            // increase the current plugin version tracked by the framework
            increasePluginVersion();
        } finally {
            lock.writeLock().unlock();
        }

        if (authStackReloadCounter != null) {
            authStackReloadCounter.increment();
        }

        // clean the old stack
        removeAll(oldStack);
        loadingStack = null;
    }

    /**
     * Returns the current plugin version in this framework.
     * <p>
     * This number changes with every {@code reload()}.
     * <p>
     * Assumes the {@code lock} is held for reading.
     *
     * @return the current version number
     */
    private long getPluginVersion() {
        return pluginVersion;
    }

    /**
     * Changes the plugin version to the next version.
     * <p>
     * Assumes that {@code lock} is held for writing.
     */
    private void increasePluginVersion() {
        this.pluginVersion++;
    }

    /**
     * Is this session marked as invalid?
     * <p>
     * Assumes the {@code lock} is held for reading.
     *
     * @param session the request session
     * @return true if it is; false otherwise
     */
    private boolean isSessionInvalid(HttpSession session) {
        if (session.getAttribute(SESSION_VERSION) == null) {
            return true;
        }

        long version = (long) session.getAttribute(SESSION_VERSION);

        return version != getPluginVersion();
    }

    /**
     * Checks if the request should have an access to a resource. This method is
     * thread safe with respect to the concurrent reload of plugins.
     *
     * <p>
     * Internally performed with a predicate. Using cache in request
     * attributes.</p>
     *
     * <h3>Order of plugin invocation</h3>
     *
     * <p>
     * The order of plugin invocation is given by the stack and appropriate
     * actions are taken when traversing the stack with set of keywords,
     * such as:</p>
     *
     * <h4>required</h4>
     * Failure of such a plugin will ultimately lead to the authorization
     * framework returning failure but only after the remaining plugins have
     * been invoked.
     *
     * <h4>requisite</h4>
     * Like required, however, in the case that such a plugin returns a failure,
     * control is directly returned to the application. The return value is that
     * associated with the first required or requisite plugin to fail.
     *
     * <h4>sufficient</h4>
     * If such a plugin succeeds and no prior required plugin has failed the
     * authorization framework returns success to the application immediately
     * without calling any further plugins in the stack. A failure of a
     * sufficient plugin is ignored and processing of the plugin list continues
     * unaffected.
     *
     * <p>
     * Loaded plugins which do not occur in the configuration are appended to
     * the list with "required" keyword. As of the nature of the class discovery
     * this means that the order of invocation of these plugins is rather
     * random.</p>
     *
     * <p>
     * Plugins in the configuration which have not been loaded are skipped.</p>
     *
     * @param request           request object
     * @param cache             cache
     * @param entity            entity with name
     * @param pluginPredicate   predicate to determine the plugin's decision for the request
     * @param skippingPredicate predicate to determine if the plugin should be skipped for this request
     * @return true if yes
     * @see RuntimeEnvironment#getPluginStack()
     */
    @SuppressWarnings("unchecked")
    private boolean checkAll(HttpServletRequest request, String cache, Nameable entity,
            AuthorizationEntity.PluginDecisionPredicate pluginPredicate,
            AuthorizationEntity.PluginSkippingPredicate skippingPredicate) {

        if (stack == null) {
            return true;
        }

        if (entity == null) {
            LOGGER.log(Level.WARNING, "entity was null for request with parameters: {}",
                    Laundromat.launderLog(request.getParameterMap()));
            return false;
        }

        Boolean val;
        Map<String, Boolean> m = (Map<String, Boolean>) request.getAttribute(cache);

        if (m == null) {
            m = new TreeMap<>();
        } else if ((val = m.get(entity.getName())) != null) {
            // cache hit
            if (authCacheHits != null) {
                authCacheHits.increment();
            }
            return val;
        }

        if (authCacheMisses != null) {
            authCacheMisses.increment();
        }

        Duration duration;
        boolean overallDecision;

        lock.readLock().lock();
        try {
            // Make sure there is a HTTP session that corresponds to current plugin version.
            HttpSession session;
            if (((session = request.getSession(false)) != null) && isSessionInvalid(session)) {
                session.invalidate();
                if (authSessionsInvalidated != null) {
                    authSessionsInvalidated.increment();
                }
            }
            request.getSession().setAttribute(SESSION_VERSION, getPluginVersion());

            Instant start = Instant.now();
            overallDecision = performCheck(entity, pluginPredicate, skippingPredicate);
            Instant end = Instant.now();
            duration = Duration.between(start, end);
        } finally {
            lock.readLock().unlock();
        }

        // Update the timers.
        if (overallDecision) {
            if (authTimerPositive != null) {
                authTimerPositive.record(duration);
            }
        } else {
            if (authTimerNegative != null) {
                authTimerNegative.record(duration);
            }
        }

        m.put(entity.getName(), overallDecision);
        request.setAttribute(cache, m);

        return overallDecision;
    }

    /**
     * Perform the actual check for the entity.
     * <p>
     * Assumes that {@code lock} is held in read mode.
     *
     * @param entity            either a project or a group
     * @param pluginPredicate   a predicate that decides if the authorization is
     *                          successful for the given plugin
     * @param skippingPredicate predicate that decides if given authorization
     *                          entity should be omitted from the authorization process
     * @return true if entity is allowed; false otherwise
     */
    private boolean performCheck(Nameable entity,
            AuthorizationEntity.PluginDecisionPredicate pluginPredicate,
            AuthorizationEntity.PluginSkippingPredicate skippingPredicate) {

        return stack.isAllowed(entity, pluginPredicate, skippingPredicate);
    }
}
