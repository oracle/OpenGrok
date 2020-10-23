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
package org.opengrok.web;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Timer;
import org.opengrok.indexer.Metrics;
import org.opengrok.indexer.web.SearchHelper;

public class StatisticsFilter implements Filter {

    static final String REQUESTS_METRIC = "requests";

    private final DistributionSummary requests = Metrics.getInstance().getRegistry().summary(REQUESTS_METRIC);

    private final Timer emptySearch = Timer.builder("search.latency").
            tags("outcome", "empty").
            register(Metrics.getInstance().getRegistry());
    private final Timer successfulSearch = Timer.builder("search.latency").
            tags("outcome", "success").
            register(Metrics.getInstance().getRegistry());

    @Override
    public void init(FilterConfig fc) throws ServletException {
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain fc)
            throws IOException, ServletException {

        requests.record(1);

        HttpServletRequest httpReq = (HttpServletRequest) servletRequest;

        Instant start = Instant.now();

        PageConfig config = PageConfig.get(httpReq);

        fc.doFilter(servletRequest, servletResponse);

        measure((HttpServletResponse) servletResponse, httpReq, Duration.between(start, Instant.now()), config);
    }

    private void measure(HttpServletResponse httpResponse, HttpServletRequest httpReq,
                         Duration duration, PageConfig config) {
        String category;
        if (isRoot(httpReq)) {
            category = "root";
        } else {
            String prefix = config.getPrefix().toString();
            if (prefix.isEmpty()) {
                category = "unknown";
            } else {
                category = prefix.substring(1);
            }
        }

        Timer categoryTimer = Timer.builder("requests.latency").
                tags("category", category, "code", String.valueOf(httpResponse.getStatus())).
                register(Metrics.getInstance().getRegistry());
        categoryTimer.record(duration);

        SearchHelper helper = (SearchHelper) config.getRequestAttribute(SearchHelper.REQUEST_ATTR);
        if (helper != null) {
            if (helper.hits == null || helper.hits.length == 0) {
                emptySearch.record(duration);
            } else {
                successfulSearch.record(duration);
            }
        }
    }

    private boolean isRoot(final HttpServletRequest httpReq) {
        return httpReq.getRequestURI().replace(httpReq.getContextPath(), "").equals("/")
                || httpReq.getRequestURI().replace(httpReq.getContextPath(), "").equals("");
    }

    @Override
    public void destroy() {
    }
}
