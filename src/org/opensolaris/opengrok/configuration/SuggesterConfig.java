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
package org.opensolaris.opengrok.configuration;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;

import java.util.Objects;
import java.util.Set;

/**
 * The suggester specific configuration.
 */
public class SuggesterConfig {

    public static final boolean ENABLED_DEFAULT = true;
    public static final int MAX_RESULTS_DEFAULT = 10;
    public static final int MIN_CHARS_DEFAULT = 0;
    public static final int MAX_PROJECTS_DEFAULT = Integer.MAX_VALUE;
    public static final boolean ALLOW_COMPLEX_QUERIES_DEFAULT = true;
    public static final boolean ALLOW_MOST_POPULAR_DEFAULT = true;
    public static final boolean SHOW_SCORES_DEFAULT = false;
    public static final boolean SHOW_PROJECTS_DEFAULT = true;
    public static final boolean SHOW_TIME_DEFAULT = false;
    public static final String REBUILD_CRON_CONFIG_DEFAULT = "0 0 * * *"; // every day at midnight
    public static final int SUGGESTER_BUILD_TERMINATION_TIME_DEFAULT = 1800; // half an hour should be enough

    public static final Set<String> allowedProjectsDefault = null;
    public static final Set<String> allowedFieldsDefault = null;

    /**
     * Specifies if the suggester is enabled.
     */
    private boolean enabled;

    /**
     * Specifies how many results suggester should return at maximum.
     */
    private int maxResults;

    /**
     * Specifies minimum number of characters that are needed for suggester to start looking for suggestions.
     */
    private int minChars;

    /**
     * Specifies set of projects for which the suggester should be enabled. If {@code null} then all projects are
     * enabled.
     */
    private Set<String> allowedProjects;

    /**
     * Specifies how many maximum projects can be selected at the same time and the suggestions will work.
     */
    private int maxProjects;

    /**
     * Specifies the fields for which the suggester should be enabled. If {@code null} then all fields are enabled.
     */
    private Set<String> allowedFields;

    /**
     * Specifies if the suggester should support complex queries.
     */
    private boolean allowComplexQueries;

    /**
     * Specifies if the most popular completion should be enabled.
     */
    private boolean allowMostPopular;

    /**
     * Specifies if the scores should be displayed next to the suggestions.
     */
    private boolean showScores;

    /**
     * Specifies if the suggestions should show in which project the term was found.
     */
    private boolean showProjects;

    /**
     * Specifies if the time it took the suggester to find the suggestions should be displayed.
     */
    private boolean showTime;

    /**
     * Specifies how often should the suggester rebuild the WFST data structures. (Data structures for simple prefix
     * queries.)
     */
    private String rebuildCronConfig;

    /**
     * Specifies after how much time the suggester should kill the threads that build the suggester data structures.
     */
    private int suggesterBuildTerminationTimeSec;

    private SuggesterConfig() {
    }

    public static SuggesterConfig getDefault() {
        SuggesterConfig config = new SuggesterConfig();
        config.setEnabled(ENABLED_DEFAULT);
        config.setMaxResults(MAX_RESULTS_DEFAULT);
        config.setMinChars(MIN_CHARS_DEFAULT);
        config.setAllowedProjects(allowedProjectsDefault);
        config.setMaxProjects(MAX_PROJECTS_DEFAULT);
        config.setAllowedFields(allowedFieldsDefault);
        config.setAllowComplexQueries(ALLOW_COMPLEX_QUERIES_DEFAULT);
        config.setAllowMostPopular(ALLOW_MOST_POPULAR_DEFAULT);
        config.setShowScores(SHOW_SCORES_DEFAULT);
        config.setShowProjects(SHOW_PROJECTS_DEFAULT);
        config.setShowTime(SHOW_TIME_DEFAULT);
        // do not use setter because indexer invocation with --man will fail
        config.rebuildCronConfig = REBUILD_CRON_CONFIG_DEFAULT;
        config.setSuggesterBuildTerminationTimeSec(SUGGESTER_BUILD_TERMINATION_TIME_DEFAULT);
        return config;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(final int maxResults) {
        if (maxResults <= 0) {
            throw new IllegalArgumentException("Max results cannot be negative or zero");
        }
        this.maxResults = maxResults;
    }

    public int getMinChars() {
        return minChars;
    }

    public void setMinChars(final int minChars) {
        if (minChars < 0) {
            throw new IllegalArgumentException(
                    "Minimum number of characters needed for suggester to provide suggestions cannot be negative");
        }
        this.minChars = minChars;
    }

    public Set<String> getAllowedProjects() {
        return allowedProjects;
    }

    public void setAllowedProjects(final Set<String> allowedProjects) {
        this.allowedProjects = allowedProjects;
    }

    public int getMaxProjects() {
        return maxProjects;
    }

    public void setMaxProjects(final int maxProjects) {
        if (maxProjects < 1) {
            throw new IllegalArgumentException("Maximum projects for suggestions cannot be less than 1");
        }
        this.maxProjects = maxProjects;
    }

    public Set<String> getAllowedFields() {
        return allowedFields;
    }

    public void setAllowedFields(final Set<String> allowedFields) {
        this.allowedFields = allowedFields;
    }

    public boolean isAllowComplexQueries() {
        return allowComplexQueries;
    }

    public void setAllowComplexQueries(final boolean allowComplexQueries) {
        this.allowComplexQueries = allowComplexQueries;
    }

    public boolean isAllowMostPopular() {
        return allowMostPopular;
    }

    public void setAllowMostPopular(final boolean allowMostPopular) {
        this.allowMostPopular = allowMostPopular;
    }

    public boolean isShowScores() {
        return showScores;
    }

    public void setShowScores(final boolean showScores) {
        this.showScores = showScores;
    }

    public boolean isShowProjects() {
        return showProjects;
    }

    public void setShowProjects(final boolean showProjects) {
        this.showProjects = showProjects;
    }

    public boolean isShowTime() {
        return showTime;
    }

    public void setShowTime(final boolean showTime) {
        this.showTime = showTime;
    }

    public String getRebuildCronConfig() {
        return rebuildCronConfig;
    }

    public void setRebuildCronConfig(final String rebuildCronConfig) {
        if (rebuildCronConfig != null) { // check cron format
            CronParser parser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));
            parser.parse(rebuildCronConfig); // throws IllegalArgumentException if invalid
        }
        this.rebuildCronConfig = rebuildCronConfig;
    }

    public int getSuggesterBuildTerminationTimeSec() {
        return suggesterBuildTerminationTimeSec;
    }

    public void setSuggesterBuildTerminationTimeSec(final int suggesterBuildTerminationTimeSec) {
        if (suggesterBuildTerminationTimeSec < 0) {
            throw new IllegalArgumentException("Suggester build termination time cannot be negative");
        }
        this.suggesterBuildTerminationTimeSec = suggesterBuildTerminationTimeSec;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SuggesterConfig that = (SuggesterConfig) o;
        return enabled == that.enabled &&
                maxResults == that.maxResults &&
                minChars == that.minChars &&
                maxProjects == that.maxProjects &&
                allowComplexQueries == that.allowComplexQueries &&
                allowMostPopular == that.allowMostPopular &&
                showScores == that.showScores &&
                showProjects == that.showProjects &&
                showTime == that.showTime &&
                suggesterBuildTerminationTimeSec == that.suggesterBuildTerminationTimeSec &&
                Objects.equals(allowedProjects, that.allowedProjects) &&
                Objects.equals(allowedFields, that.allowedFields) &&
                Objects.equals(rebuildCronConfig, that.rebuildCronConfig);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, maxResults, minChars, allowedProjects, maxProjects, allowedFields,
                allowComplexQueries, allowMostPopular, showScores, showProjects, showTime, rebuildCronConfig,
                suggesterBuildTerminationTimeSec);
    }

}
