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

import io.micrometer.statsd.StatsdFlavor;

/**
 * Configuration for Statsd metrics emitted by the Indexer via {@link org.opengrok.indexer.util.Statistics}.
 */
public class BaseStatsdConfig {
    private int port;
    private String host;
    private StatsdFlavor flavor;

    public BaseStatsdConfig() {
    }

    public BaseStatsdConfig(String host, int port, StatsdFlavor flavor) {
        this.host = host;
        this.port = port;
        this.flavor = flavor;
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

    public StatsdFlavor getFlavor() {
        return flavor;
    }

    public void setFlavor(StatsdFlavor flavor) {
        this.flavor = flavor;
    }

    public boolean isEnabled() {
        return port != 0 && host != null && !host.isEmpty() && flavor != null;
    }

    @Override
    public String toString() {
        return String.format("%s:%d (%s)", getHost(), getPort(), getFlavor());
    }

    /**
     * Gets an instance version suitable for helper documentation by shifting
     * most default properties slightly.
     */
    static BaseStatsdConfig getForHelp() {
        BaseStatsdConfig res = new BaseStatsdConfig();
        res.setHost("foo.bar");
        res.setPort(8125);
        res.setFlavor(StatsdFlavor.ETSY);
        return res;
    }
}
