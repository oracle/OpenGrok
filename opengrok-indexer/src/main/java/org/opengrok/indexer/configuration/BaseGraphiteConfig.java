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
 * Copyright (c) 2020 Oracle and/or its affiliates. All rights reserved.
 */

package org.opengrok.indexer.configuration;

import io.micrometer.graphite.GraphiteProtocol;

public class BaseGraphiteConfig {
    private int port;
    private String host;
    private GraphiteProtocol protocol;

    public BaseGraphiteConfig() {
    }

    public BaseGraphiteConfig(String host, int port, GraphiteProtocol protocol) {
        this.host = host;
        this.port = port;
        this.protocol = protocol;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public boolean isEnabled() {
        return port != 0 && host != null && !host.isEmpty();
    }

    public void setProtocol(GraphiteProtocol protocol) {
        this.protocol = protocol;
    }

    public GraphiteProtocol getProtocol() {
        return protocol;
    }

    @Override
    public String toString() {
        return String.format("%s:%d (%s)", getHost(), getPort(), getProtocol());
    }

    /**
     * Gets an instance version suitable for helper documentation by shifting
     * most default properties slightly.
     */
    static BaseGraphiteConfig getForHelp() {
        BaseGraphiteConfig res = new BaseGraphiteConfig();
        res.setHost("foo.bar");
        res.setPort(2004);
        res.setProtocol(GraphiteProtocol.UDP);
        return res;
    }
}
