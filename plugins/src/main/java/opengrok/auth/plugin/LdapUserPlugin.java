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

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.servlet.http.HttpServletRequest;
import opengrok.auth.entity.LdapUser;
import opengrok.auth.plugin.entity.User;
import opengrok.auth.plugin.ldap.AbstractLdapProvider;
import opengrok.auth.plugin.ldap.LdapException;
import org.opengrok.indexer.authorization.AuthorizationException;
import org.opengrok.indexer.configuration.Group;
import org.opengrok.indexer.configuration.Project;

import static opengrok.auth.plugin.util.FilterUtil.expandUserFilter;

/**
 * Authorization plug-in to extract user's LDAP attributes.
 * The attributes can be then used by the other LDAP plugins down the stack.
 *
 * @author Krystof Tulinger
 */
public class LdapUserPlugin extends AbstractLdapPlugin {

    private static final Logger LOGGER = Logger.getLogger(LdapUserPlugin.class.getName());
    
    static final String SESSION_ATTR = "opengrok-ldap-plugin-user";

    /**
     * List of configuration names.
     * <ul>
     * <li><code>filter</code> is LDAP filter used for searching (optional)</li>
     * <li><code>useDN</code> boolean value indicating if User.username should be treated as Distinguished Name (optional, default is false)</li>
     * <li><code>attributes</code> is comma separated list of LDAP attributes to be produced (mandatory)</li>
     * <li><code>instance</code> integer that can be used to identify instance of this plugin by other LDAP plugins (optional, default empty)</li>
     * </ul>
     */
    static final String LDAP_FILTER = "filter";
    static final String ATTRIBUTES = "attributes";
    static final String USE_DN = "useDN";
    static final String INSTANCE = "instance";

    private String ldapFilter;
    private Boolean useDN;
    private Set<String> attributes;
    private Integer instance;

    // for testing
    void load(Map<String, Object> parameters, AbstractLdapProvider provider) {
        super.load(provider);

        init(parameters);
    }

    @Override
    public void load(Map<String, Object> parameters) {
        super.load(parameters);

        init(parameters);
    }

    private void init(Map<String, Object> parameters) {
        String attributesVal;
        if ((attributesVal = (String) parameters.get(ATTRIBUTES)) == null) {
            throw new NullPointerException("Missing configuration parameter [" + ATTRIBUTES +
                    "] in the setup");
        }
        attributes = new HashSet<>(Arrays.asList(attributesVal.split(",")));

        ldapFilter = (String) parameters.get(LDAP_FILTER);

        if ((useDN = (Boolean) parameters.get(USE_DN)) == null) {
            useDN = false;
        }

        String instance_param = (String) parameters.get(INSTANCE);
        if (instance_param != null) {
            instance = Integer.parseInt(instance_param);
        }

        LOGGER.log(Level.FINER, "LdapUser plugin loaded with filter={0}, " +
                        "attributes={1}, useDN={2}, instance={3}",
                new Object[]{ldapFilter, attributes, useDN, instance});
    }

    /**
     * Check if the session exists and contains all necessary fields required by
     * this plug-in.
     *
     * @param req the HTTP request
     * @return true if it does; false otherwise
     */
    @Override
    protected boolean sessionExists(HttpServletRequest req) {
        return super.sessionExists(req)
                && req.getSession().getAttribute(getSessionAttrName()) != null;
    }

    /**
     * Expand {@code User} object attribute values into the filter.
     *
     * @see opengrok.auth.plugin.util.FilterUtil
     *
     * Use \% for printing the '%' character.
     *
     * @param user User object from the request (created by {@code UserPlugin})
     * @return replaced result
     */
    String expandFilter(User user) {
        String filter = ldapFilter;

        filter = expandUserFilter(user, filter);

        filter = filter.replaceAll("\\\\%", "%");

        return filter;
    }

    @Override
    public void fillSession(HttpServletRequest req, User user) {
        Map<String, Set<String>> records;
        
        updateSession(req, null);

        if (getLdapProvider() == null) {
            LOGGER.log(Level.WARNING, "cannot get LDAP provider");
            return;
        }

        String dn = null;
        if (useDN) {
            dn = user.getUsername();
            LOGGER.log(Level.FINEST, "using DN ''{0}'' for user {1}",
                    new Object[]{dn, user});
        }

        String expandedFilter = null;
        if (ldapFilter != null) {
            expandedFilter = expandFilter(user);
            LOGGER.log(Level.FINEST, "expanded filter for user {0} into ''{1}''",
                    new Object[]{user, expandedFilter});
        }

        AbstractLdapProvider ldapProvider = getLdapProvider();
        try {
            AbstractLdapProvider.LdapSearchResult<Map<String, Set<String>>> res;
            if ((res = ldapProvider.lookupLdapContent(dn, expandedFilter,
                    attributes.toArray(new String[0]))) == null) {
                LOGGER.log(Level.WARNING, "failed to get LDAP attributes ''{2}'' for user {0} " +
                                "with filter ''{1}'' from LDAP provider {3}",
                        new Object[]{user, expandedFilter, attributes, getLdapProvider()});
                return;
            }

            records = res.getAttrs();
            if (!useDN) {
                dn = res.getDN();
                LOGGER.log(Level.FINEST, "got DN ''{0}'' for user {1} on {2}",
                        new Object[]{dn, user});
            }
        } catch (LdapException ex) {
            throw new AuthorizationException(ex);
        }

        if (records.isEmpty()) {
            LOGGER.log(Level.WARNING, "LDAP records for user {0} are empty on {1}",
                    new Object[]{user, ldapProvider});
            return;
        }

        for (String attrName : attributes) {
            if (!records.containsKey(attrName) || records.get(attrName) == null || records.get(attrName).isEmpty()) {
                LOGGER.log(Level.WARNING, "''{0}'' record for user {1} is not present or empty on {2}",
                        new Object[]{attrName, user, ldapProvider});
            }
        }

        Map<String, Set<String>> attrSet = new HashMap<>();
        for (String attrName : attributes) {
            attrSet.put(attrName, records.get(attrName));
        }

        LOGGER.log(Level.FINEST, "DN for user {0} is ''{1}'' on {2}",
                new Object[]{user, dn, ldapProvider});
        updateSession(req, new LdapUser(dn, attrSet));
    }

    /**
     * Add a new user value into the session.
     *
     * @param req the request
     * @param user the new value for user
     */
    void updateSession(HttpServletRequest req, LdapUser user) {
        req.getSession().setAttribute(getSessionAttrName(), user);
    }

    static String getSessionAttrName(Integer instance) {
        return (SESSION_ATTR + (instance != null ? instance.toString() : ""));
    }

    private String getSessionAttrName() {
        return getSessionAttrName(instance);
    }

    @Override
    public boolean checkEntity(HttpServletRequest request, Project project) {
        return request.getSession().getAttribute(getSessionAttrName()) != null;
    }

    @Override
    public boolean checkEntity(HttpServletRequest request, Group group) {
        return request.getSession().getAttribute(getSessionAttrName()) != null;
    }
}
