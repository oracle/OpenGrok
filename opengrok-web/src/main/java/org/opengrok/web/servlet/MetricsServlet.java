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
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 */
package org.opengrok.web.servlet;

import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.opengrok.indexer.Metrics;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

@WebServlet("/metrics/prometheus")
public class MetricsServlet extends HttpServlet {

    private static final long serialVersionUID = 0L;

    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
        PrometheusMeterRegistry registry = Metrics.getInstance().getPrometheusRegistry();
        if (registry != null) {
            try (PrintWriter pw = resp.getWriter()) {
                pw.print(registry.scrape());
            }
        } else {
            resp.sendError(HttpServletResponse.SC_NOT_IMPLEMENTED);
        }
    }
}
