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
package opengrok.auth.plugin.ldap;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.naming.CommunicationException;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.SizeLimitExceededException;
import javax.naming.TimeLimitExceededException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import opengrok.auth.plugin.configuration.Configuration;
import opengrok.auth.plugin.util.WebHook;
import opengrok.auth.plugin.util.WebHooks;
import org.opengrok.indexer.Metrics;

public class LdapFacade extends AbstractLdapProvider {

    private static final Logger LOGGER = Logger.getLogger(LdapFacade.class.getName());

    /**
     * Default LDAP filter.
     */
    private static final String LDAP_FILTER = "objectclass=*";

    /**
     * Default timeout for retrieving the results.
     */
    private static final int LDAP_SEARCH_TIMEOUT = 5000; // ms

    /**
     * Default limit of result traversal.
     *
     * @see
     * <a href="https://docs.oracle.com/javase/7/docs/api/javax/naming/directory/SearchControls.html#setCountLimit%28long%29">SearchControls</a>
     *
     * basically it does not mean that the server must send at most this number
     * of results, but that the program should not iterate more than this number
     * over the results.
     */
    private static final int LDAP_COUNT_LIMIT = 100;

    /**
     * When there is no active server in the pool, the facade waits this time
     * interval (since the last failure) until it tries the servers again.
     *
     * This should avoid heavy load to the LDAP servers when they are all
     * broken/not responding/down - pool waiting.
     *
     * Also each server uses this same interval since its last failure - per
     * server waiting.
     */
    private int interval = 10 * 1000; // ms

    /**
     * LDAP search base.
     */
    private String searchBase;

    /**
     * Server pool.
     */
    private List<LdapServer> servers = new ArrayList<>();

    /**
     * server webHooks.
     */
    private WebHooks webHooks;

    private SearchControls controls;
    private int actualServer = -1;
    private long errorTimestamp = 0;
    private boolean reported = false;

    private Timer ldapLookupTimer;

    /**
     * Interface for converting LDAP results into user defined types.
     *
     * @param <T> the type of the result
     */
    private interface AttributeMapper<T> {

        T mapFromAttributes(Attributes attr) throws NamingException;
    }

    /**
     * Transforms the attributes to the set of strings used for authorization.
     *
     * Currently this behaves like it get all records stored in
     */
    private static class ContentAttributeMapper implements AttributeMapper<Map<String, Set<String>>> {

        private final String[] values;

        /**
         * Create a new mapper which retrieves the given values in the resulting
         * set.
         *
         * @param values include these values in the result
         */
        ContentAttributeMapper(String[] values) {
            this.values = values;
        }

        @Override
        public Map<String, Set<String>> mapFromAttributes(Attributes attrs) throws NamingException {
            Map<String, Set<String>> map = new HashMap<>();

            if (values == null) {
                for (NamingEnumeration<? extends Attribute> attrEnum = attrs.getAll(); attrEnum.hasMore();) {
                    Attribute attr = attrEnum.next();

                    addAttrToMap(map, attr);
                }
            } else {
                for (String value : values) {
                    Attribute attr = attrs.get(value);

                    if (attr == null) {
                        continue;
                    }

                    addAttrToMap(map, attr);
                }
            }

            return map;
        }

        private void addAttrToMap(Map<String, Set<String>> map, Attribute attr) throws NamingException {
            if (!map.containsKey(attr.getID())) {
                map.put(attr.getID(), new TreeSet<>());
            }

            final Set<String> valueSet = map.get(attr.getID());

            for (NamingEnumeration<?> values = attr.getAll(); values.hasMore(); ) {
                valueSet.add((String) values.next());
            }
        }
    }

    public LdapFacade(Configuration cfg) {
        setServers(cfg.getServers(), cfg.getConnectTimeout(), cfg.getReadTimeout());
        setInterval(cfg.getInterval());
        setSearchBase(cfg.getSearchBase());
        setWebHooks(cfg.getWebHooks());

        MeterRegistry registry = Metrics.getInstance().getRegistry();
        if (registry != null) {
            ldapLookupTimer = Timer.builder("ldap.latency").
                    description("LDAP lookup latency").
                    register(registry);
        }

        // Anti-pattern: do some non trivial stuff in the constructor.
        prepareSearchControls(cfg.getSearchTimeout(), cfg.getCountLimit());
        prepareServers();
    }

    private void setWebHooks(WebHooks webHooks) {
        this.webHooks = webHooks;
    }

    /**
     * Go through all servers in the pool and record the first working.
     */
    void prepareServers() {
        for (int i = 0; i < servers.size(); i++) {
            LdapServer server = servers.get(i);
            if (server.isWorking() && actualServer == -1) {
                actualServer = i;
            }
        }
    }

    /**
     * Closes all available servers.
     */
    @Override
    public void close() {
        for (LdapServer server : servers) {
            server.close();
        }
    }

    public List<LdapServer> getServers() {
        return servers;
    }

    public LdapFacade setServers(List<LdapServer> servers, int connectTimeout, int readTimeout) {
        this.servers = servers;
        // Inherit timeout values from server pool configuration.
        for (LdapServer server : servers) {
            if (server.getConnectTimeout() == 0 && connectTimeout != 0) {
                server.setConnectTimeout(connectTimeout);
            }
            if (server.getReadTimeout() == 0 && readTimeout != 0) {
                server.setReadTimeout(readTimeout);
            }
        }
        return this;
    }

    public int getInterval() {
        return interval;
    }

    public void setInterval(int interval) {
        this.interval = interval;
        for (LdapServer server : servers) {
            server.setInterval(interval);
        }
    }

    public String getSearchBase() {
        return searchBase;
    }

    public void setSearchBase(String base) {
        this.searchBase = base;
    }

    @Override
    public boolean isConfigured() {
        return servers != null && !servers.isEmpty() && searchBase != null && actualServer != -1;
    }

    /**
     * Get LDAP attributes.
     *
     * @param dn LDAP DN attribute. If @{code null} then {@code searchBase} will be used.
     * @param filter LDAP filter to use. If @{code null} then @{link LDAP_FILTER} will be used.
     * @param values match these LDAP values
     *
     * @return set of strings describing the user's attributes
     */
    @Override
    public LdapSearchResult<Map<String, Set<String>>> lookupLdapContent(String dn, String filter, String[] values) throws LdapException {

        return lookup(
                dn != null ? dn : getSearchBase(),
                filter == null ? LDAP_FILTER : filter,
                values,
                new ContentAttributeMapper(values));
    }

    private SearchControls prepareSearchControls(int ldapTimeout, int ldapCountLimit) {
        controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setTimeLimit(ldapTimeout > 0 ? ldapTimeout : LDAP_SEARCH_TIMEOUT);
        controls.setCountLimit(ldapCountLimit > 0 ? ldapCountLimit : LDAP_COUNT_LIMIT);

        return controls;
    }

    public SearchControls getSearchControls() {
        return controls;
    }

    /**
     * Lookups the LDAP server for content.
     *
     * @param <T> return type
     * @param dn search base for the query
     * @param filter LDAP filter for the query
     * @param attributes returning LDAP attributes
     * @param mapper mapper class implementing @code{AttributeMapper} closed
     *
     * @return results transformed with mapper
     */
    private <T> LdapSearchResult<T> lookup(String dn, String filter, String[] attributes, AttributeMapper<T> mapper) throws LdapException {
        Instant start = Instant.now();
        LdapSearchResult<T> res = lookup(dn, filter, attributes, mapper, 0);
        if (ldapLookupTimer != null) {
            ldapLookupTimer.record(Duration.between(start, Instant.now()));
        }
        return res;
    }

    private String getSearchDescription(String dn, String filter, String[] attributes) {
        return "DN: " + dn + " , filter: " + filter + " , attributes: " + String.join(",", attributes);
    }

    /**
     * Lookups the LDAP server for content.
     *
     * @param <T> return type
     * @param dn search base for the query
     * @param filter LDAP filter for the query
     * @param attributes returning LDAP attributes
     * @param mapper mapper class implementing @code{AttributeMapper} closed
     * @param fail current count of failures
     *
     * @return results transformed with mapper or {@code null} on failure
     * @throws LdapException LDAP exception
     */
    private <T> LdapSearchResult<T> lookup(String dn, String filter, String[] attributes, AttributeMapper<T> mapper, int fail) throws LdapException {

        if (errorTimestamp > 0 && errorTimestamp + interval > System.currentTimeMillis()) {
            if (!reported) {
                reported = true;
                LOGGER.log(Level.SEVERE, "LDAP server pool is still broken");
            }
            throw new LdapException("LDAP server pool is still broken");
        }

        if (fail > servers.size() - 1) {
            // did the whole rotation
            LOGGER.log(Level.SEVERE, "Tried all LDAP servers in a pool but no server works");
            errorTimestamp = System.currentTimeMillis();
            reported = false;
            WebHook hook;
            if ((hook = webHooks.getFail()) != null) {
                hook.post();
            }
            throw new LdapException("Tried all LDAP servers in a pool but no server works");
        }

        if (!isConfigured()) {
            LOGGER.log(Level.SEVERE, "LDAP is not configured");
            throw new LdapException("LDAP is not configured");
        }

        NamingEnumeration<SearchResult> namingEnum = null;
        LdapServer server = null;
        try {
            server = servers.get(actualServer);
            controls.setReturningAttributes(attributes);
            for (namingEnum = server.search(dn, filter, controls); namingEnum.hasMore();) {
                SearchResult sr = namingEnum.next();
                reported = false;
                if (errorTimestamp > 0) {
                    errorTimestamp = 0;
                    WebHook hook;
                    if ((hook = webHooks.getRecover()) != null) {
                        hook.post();
                    }
                }

                return new LdapSearchResult<>(sr.getNameInNamespace(), processResult(sr, mapper));
            }
        } catch (NameNotFoundException ex) {
            LOGGER.log(Level.WARNING, String.format("The LDAP name for search '%s' was not found on server %s",
                    getSearchDescription(dn, filter, attributes), server), ex);
            throw new LdapException("The LDAP name was not found.", ex);
        } catch (SizeLimitExceededException ex) {
            LOGGER.log(Level.SEVERE, String.format("The maximum size of the LDAP result has exceeded "
                    + "on server %s", server), ex);
            closeActualServer();
            actualServer = getNextServer();
            return lookup(dn, filter, attributes, mapper, fail + 1);
        } catch (TimeLimitExceededException ex) {
            LOGGER.log(Level.SEVERE, String.format("Time limit for LDAP operation has exceeded on server %s",
                    server), ex);
            closeActualServer();
            actualServer = getNextServer();
            return lookup(dn, filter, attributes, mapper, fail + 1);
        } catch (CommunicationException ex) {
            LOGGER.log(Level.WARNING, String.format("Communication error received on server %s, " +
                    "reconnecting to next server.", server), ex);
            closeActualServer();
            actualServer = getNextServer();
            return lookup(dn, filter, attributes, mapper, fail + 1);
        } catch (NamingException ex) {
            LOGGER.log(Level.SEVERE, String.format("An arbitrary LDAP error occurred on server %s " +
                    "when searching for '%s'", server, getSearchDescription(dn, filter, attributes)), ex);
            closeActualServer();
            actualServer = getNextServer();
            return lookup(dn, filter, attributes, mapper, fail + 1);
        } finally {
            if (namingEnum != null) {
                try {
                    namingEnum.close();
                } catch (NamingException e) {
                    LOGGER.log(Level.WARNING,
                            "failed to close search result enumeration");
                }
            }
        }

        return null;
    }

    private void closeActualServer() {
        servers.get(actualServer).close();
    }

    /**
     * Server take over algorithm behavior.
     *
     * @return the index of the next server to be used
     */
    private int getNextServer() {
        return (actualServer + 1) % servers.size();
    }

    /**
     * Process the incoming LDAP result.
     *
     * @param <T> type of the result
     * @param result LDAP result
     * @param mapper mapper to transform the result into the result type
     * @return transformed result
     *
     * @throws NamingException naming exception
     */
    private <T> T processResult(SearchResult result, AttributeMapper<T> mapper) throws NamingException {
        Attributes attrs = result.getAttributes();
        if (attrs != null) {
            return mapper.mapFromAttributes(attrs);
        }

        return null;
    }

    @Override
    public String toString() {
        return "{server=" + (actualServer != -1 ? servers.get(actualServer) : "no active server") +
                ", searchBase=" + getSearchBase() + "}";
    }
}
