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

import io.micrometer.core.instrument.Tag;
import org.junit.Test;
import org.opengrok.indexer.Metrics;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class MetricsCommonTagTest {
    @Test
    public void testCommonTag() {
        Metrics metrics = Metrics.getInstance();
        assertNull(metrics.updateSubFiles(Collections.emptyList()));

        List<String> subFiles = Arrays.asList("/foo", "/bar");
        metrics.configure(MeterRegistryType.PROMETHEUS);
        Tag tag = metrics.updateSubFiles(subFiles);
        assertEquals(Tag.of("projects", subFiles.stream().map(s -> s.substring(1)).
                collect(Collectors.joining(","))), tag);
    }
}
