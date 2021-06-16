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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import jakarta.servlet.http.HttpServletRequest;
import opengrok.auth.entity.LdapUser;
import opengrok.auth.plugin.entity.User;
import opengrok.auth.plugin.ldap.AbstractLdapProvider;
import opengrok.auth.plugin.ldap.LdapException;
import org.opengrok.indexer.authorization.AuthorizationException;
import org.opengrok.indexer.configuration.Group;
import org.opengrok.indexer.configuration.Project;

/**
 * Authorization plug-in to check user's LDAP attribute against whitelist.
 *
 * This plugin heavily relies on the presence of the {@code LdapUserPlugin} in the stack above it,
 * since it is using the Distinguished Name of the {@code LdapUser} to perform the LDAP lookup.
 *
 * @author Krystof Tulinger
 */
public class LdapAttrPlugin extends AbstractLdapPlugin {

    private static final Logger LOGGER = Logger.getLogger(LdapAttrPlugin.class.getName());

    /**
     * List of configuration names.
     * <ul>
     * <li><code>attribute</code> is LDAP attribute to check (mandatory)</li>
     * <li><code>file</code> whitelist file (mandatory)</li>
     * <li><code>instance</code> is number of <code>LdapUserInstance</code> plugin to use (optional)</li>
     * </ul>
     */
    static final String ATTR_PARAM = "attribute";
    static final String FILE_PARAM = "file";
    static final String INSTANCE_PARAM = "instance";

    private static final String SESSION_ALLOWED_PREFIX = "opengrok-ldap-attr-plugin-allowed";
    private String sessionAllowed = SESSION_ALLOWED_PREFIX;

    private String ldapAttr;
    private final Set<String> whitelist = new TreeSet<>();
    private Integer ldapUserInstance;
    private String filePath;

    public LdapAttrPlugin() {
        sessionAllowed += "-" + nextId++;
    }

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
        if ((ldapAttr = (String) parameters.get(ATTR_PARAM)) == null) {
            throw new NullPointerException("Missing param [" + ATTR_PARAM + "] in the setup");
        }

        if ((filePath = (String) parameters.get(FILE_PARAM)) == null) {
            throw new NullPointerException("Missing param [" + FILE_PARAM + "] in the setup");
        }

        String instance = (String) parameters.get(INSTANCE_PARAM);
        if (instance != null) {
            ldapUserInstance = Integer.parseInt(instance);
        }

        try (Stream<String> stream = Files.lines(Paths.get(filePath))) {
            stream.forEach(whitelist::add);
        } catch (IOException e) {
            throw new IllegalArgumentException(String.format("Unable to read the file \"%s\"", filePath), e);
        }

        LOGGER.log(Level.FINER, "LdapAttrPlugin plugin loaded with attr={0}, whitelist={1} ({2} entries), " +
                        "instance={3}", new Object[]{ldapAttr, filePath, whitelist.size(), ldapUserInstance});
    }

    @Override
    protected boolean sessionExists(HttpServletRequest req) {
        return super.sessionExists(req)
                && req.getSession().getAttribute(sessionAllowed) != null;
    }

    String getSessionAllowedAttrName() {
        return sessionAllowed;
    }

    @Override
    public void fillSession(HttpServletRequest req, User user) {
        updateSession(req, false);

        LdapUser ldapUser = (LdapUser) req.getSession().getAttribute(LdapUserPlugin.getSessionAttrName(ldapUserInstance));
        if (ldapUser == null) {
            LOGGER.log(Level.WARNING, "cannot get {0} attribute from {1}",
                    new Object[]{LdapUserPlugin.SESSION_ATTR, user});
            return;
        }

        // Check attributes cached in LDAP user object first, then query LDAP server
        // (and if found, cache the result in the LDAP user object).
        Set<String> attributeValues = ldapUser.getAttribute(ldapAttr);
        if (attributeValues == null) {
            Map<String, Set<String>> records = null;
            AbstractLdapProvider ldapProvider = getLdapProvider();
            try {
                String dn = ldapUser.getDn();
                if (dn != null) {
                    LOGGER.log(Level.FINEST, "searching with dn={0} on {1}",
                            new Object[]{dn, ldapProvider});
                    AbstractLdapProvider.LdapSearchResult<Map<String, Set<String>>> res;
                    if ((res = ldapProvider.lookupLdapContent(dn, new String[]{ldapAttr})) == null) {
                        LOGGER.log(Level.WARNING, "cannot lookup attributes {0} for user {1} on {2})",
                                new Object[]{ldapAttr, ldapUser, ldapProvider});
                        return;
                    }

                    records = res.getAttrs();
                } else {
                    LOGGER.log(Level.FINE, "no DN for LDAP user {0} on {1}",
                            new Object[]{ldapUser, ldapProvider});
                }
            } catch (LdapException ex) {
                throw new AuthorizationException(ex);
            }

            if (records == null || records.isEmpty() || (attributeValues = records.get(ldapAttr)) == null) {
                LOGGER.log(Level.WARNING, "empty records or attribute values {0} for user {1} on {2}",
                        new Object[]{ldapAttr, ldapUser, ldapProvider});
                return;
            }

            ldapUser.setAttribute(ldapAttr, attributeValues);
        }

        boolean sessionAllowed = attributeValues.stream().anyMatch(whitelist::contains);
        LOGGER.log(Level.FINEST, "LDAP user {0} {1} against {2}",
                new Object[]{ldapUser, sessionAllowed ? "allowed" : "denied", filePath});
        updateSession(req, sessionAllowed);
    }

    /**
     * Add a new allowed value into the session.
     *
     * @param req the request
     * @param allowed the new value
     */
    private void updateSession(HttpServletRequest req, boolean allowed) {
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
