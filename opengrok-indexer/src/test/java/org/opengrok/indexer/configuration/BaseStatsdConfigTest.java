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
import org.junit.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BaseStatsdConfigTest {
    @Test
    public void testIsEnabled() {
        BaseStatsdConfig config = new BaseStatsdConfig();
        assertFalse(config.isEnabled());
        config.setPort(3141);
        assertFalse(config.isEnabled());
        config.setHost("foo");
        assertFalse(config.isEnabled());
        config.setFlavor(StatsdFlavor.ETSY);
        assertTrue(config.isEnabled());
    }
}
