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
 */
package opengrok.auth.plugin;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.PatternSyntaxException;

import jakarta.servlet.http.HttpServletRequest;
import opengrok.auth.entity.LdapUser;
import opengrok.auth.plugin.entity.User;
import opengrok.auth.plugin.ldap.AbstractLdapProvider;
import opengrok.auth.plugin.ldap.LdapException;
import opengrok.auth.plugin.util.FilterUtil;
import org.opengrok.indexer.authorization.AuthorizationException;
import org.opengrok.indexer.configuration.Group;
import org.opengrok.indexer.configuration.Project;

import static opengrok.auth.plugin.util.FilterUtil.expandUserFilter;
import static opengrok.auth.plugin.util.FilterUtil.replace;

/**
 * Authorization plug-in to check if given user matches configured LDAP filter.
 *
 * @author Krystof Tulinger
 */
public class LdapFilterPlugin extends AbstractLdapPlugin {

    private static final Logger LOGGER = Logger.getLogger(LdapFilterPlugin.class.getName());

    protected static final String FILTER_PARAM = "filter";
    protected static final String TRANSFORMS_PARAM = "transforms";
    private static final String SESSION_ALLOWED_PREFIX = "opengrok-filter-plugin-allowed";
    private static final String INSTANCE = "instance";
    private String sessionAllowed = SESSION_ALLOWED_PREFIX;

    /**
     * List of configuration names.
     * <ul>
     * <li><code>filter</code> is LDAP filter used for searching (mandatory)</li>
     * <li><code>instance</code> is number of <code>LdapUserInstance</code> plugin to use (optional)</li>
     * <li><code>transforms</code> are comma separated string transforms, where each transform is name:value pair,
     * allowed values: <code>toLowerCase</code>, <code>toUpperCase</code></li>
     * </ul>
     */
    private String ldapFilter;
    private Integer ldapUserInstance;
    private Map<String, String> transforms;

    public LdapFilterPlugin() {
        sessionAllowed += "-" + nextId++;
    }

    @Override
    public void load(Map<String, Object> parameters) {
        super.load(parameters);

        if ((ldapFilter = (String) parameters.get(FILTER_PARAM)) == null) {
            throw new NullPointerException("Missing param [" + FILTER_PARAM + "] in the setup");
        }

        String instance = (String) parameters.get(INSTANCE);
        if (instance != null) {
            ldapUserInstance = Integer.parseInt(instance);
        }

        String transformsString = (String) parameters.get(TRANSFORMS_PARAM);
        if (transformsString != null) {
            loadTransforms(transformsString);
        }

        LOGGER.log(Level.FINER, "LdapFilter plugin loaded with filter={0}, instance={1}, transforms={2}",
                new Object[]{ldapFilter, ldapUserInstance, transforms});
    }

    void loadTransforms(String transformsString) throws NullPointerException {
        transforms = new TreeMap<>();
        String[] transformsArray = transformsString.split(",");
        for (String elem: transformsArray) {
            String[] tran = elem.split(":");
            transforms.put(tran[0], tran[1]);
        }
        FilterUtil.checkTransforms(transforms);
    }

    @Override
    protected boolean sessionExists(HttpServletRequest req) {
        return super.sessionExists(req)
                && req.getSession().getAttribute(sessionAllowed) != null;
    }

    private String getSessionAttr() {
        return (LdapUserPlugin.SESSION_ATTR + (ldapUserInstance != null ? ldapUserInstance.toString() : ""));
    }

    @Override
    public void fillSession(HttpServletRequest req, User user) {
        LdapUser ldapUser;

        updateSession(req, false);

        if ((ldapUser = (LdapUser) req.getSession().getAttribute(getSessionAttr())) == null) {
            LOGGER.log(Level.WARNING, "failed to get LDAP attribute ''{0}'' from session for user {1}",
                    new Object[]{LdapUserPlugin.SESSION_ATTR, user});
            return;
        }

        String expandedFilter = expandFilter(ldapFilter, ldapUser, user);
        LOGGER.log(Level.FINEST, "expanded filter ''{0}'' for user {1} and LDAP user {2} into ''{3}''",
                new Object[]{ldapFilter, user, ldapUser, expandedFilter});
        AbstractLdapProvider ldapProvider = getLdapProvider();
        try {
            if ((ldapProvider.lookupLdapContent(null, expandedFilter)) == null) {
                LOGGER.log(Level.FINE,
                        "empty content for LDAP user {0} with filter ''{1}'' on {2}",
                        new Object[]{ldapUser, expandedFilter, ldapProvider});
                return;
            }
        } catch (LdapException ex) {
            throw new AuthorizationException(ex);
        }

        LOGGER.log(Level.FINER, "LDAP user {0} allowed on {2}",
                new Object[]{ldapUser, ldapProvider});
        updateSession(req, true);
    }

    /**
     * Expand {@code LdapUser} / {@code User} object attribute values into the filter.
     *
     * @see opengrok.auth.plugin.util.FilterUtil
     *
     * Use \% for printing the '%' character.
     *
     * @param filter basic filter containing the special values
     * @param ldapUser user from LDAP
     * @param user user from the request
     * @return the filter with replacements
     */
    String expandFilter(String filter, LdapUser ldapUser, User user) {

        filter = expandUserFilter(user, filter, transforms);

        for (Entry<String, Set<String>> entry : ldapUser.getAttributes().entrySet()) {
            if (entry.getValue().size() == 1) {
                String name = entry.getKey();
                String value = entry.getValue().iterator().next();
                try {
                    filter = replace(filter, name, value, transforms);
                } catch (PatternSyntaxException ex) {
                    LOGGER.log(Level.WARNING,
                            String.format("Failed to expand filter ''%s'' with name ''%s'' and value ''%s''",
                                    filter, name, value), ex);
                }
            }
        }
        
        filter = filter.replaceAll("\\\\%", "%");
        
        return filter;
    }

    /**
     * Add a new allowed value into the session.
     *
     * @param req the request
     * @param allowed the new value
     */
    protected void updateSession(HttpServletRequest req, boolean allowed) {
        req.getSession().setAttribute(sessionAllowed, allowed);
    }

    @Override
    public boolean checkEntity(HttpServletRequest request, Project project) {
        return ((Boolean) request.getSession().getAttribute(sessionAllowed));
    }

    @Override
    public boolean checkEntity(HttpServletRequest request, Group group) {
        return ((Boolean) request.getSession().getAttribute(sessionAllowed));
    }
}
