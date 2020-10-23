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
import io.micrometer.statsd.StatsdFlavor;
import org.junit.Test;
import org.opengrok.indexer.Metrics;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class MetricsConfigureTest {
    @Test
    public void testConfigure() {
        RuntimeEnvironment env = RuntimeEnvironment.getInstance();

        Metrics metrics = Metrics.getInstance();
        assertNull(metrics.getRegistry());

        metrics.configure(MeterRegistryType.PROMETHEUS);
        assertNotNull(metrics.getRegistry());
        assertNotNull(metrics.getPrometheusRegistry());

        metrics.configure(MeterRegistryType.NONE);
        assertNull(metrics.getRegistry());

        env.setBaseGraphiteConfig(new BaseGraphiteConfig("localhost", 2222, GraphiteProtocol.PLAINTEXT));
        metrics.configure(MeterRegistryType.GRAPHITE);
        assertNotNull(metrics.getRegistry());
        metrics.configure(MeterRegistryType.NONE);

        env.setBaseStatsdConfig(new BaseStatsdConfig("loalhost", 8126, StatsdFlavor.DATADOG));
        metrics.configure(MeterRegistryType.STATSD);
        assertNotNull(metrics.getRegistry());
        metrics.configure(MeterRegistryType.NONE);
    }
}
