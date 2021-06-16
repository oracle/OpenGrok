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
 * Copyright (c) 2018, 2021, Oracle and/or its affiliates. All rights reserved.
 */
package org.opengrok.web.api.v1.controller;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.util.StdDateFormat;
import org.opengrok.indexer.configuration.IndexTimestamp;
import org.opengrok.indexer.configuration.RuntimeEnvironment;
import org.opengrok.indexer.web.EftarFile;
import org.opengrok.indexer.logger.LoggerFactory;
import org.opengrok.indexer.web.PathDescription;
import org.opengrok.web.api.v1.suggester.provider.service.SuggesterService;

import java.io.IOException;
import java.util.Collections;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

@Path("/system")
public class SystemController {

    private final RuntimeEnvironment env = RuntimeEnvironment.getInstance();

    private static final Logger LOGGER = LoggerFactory.getLogger(SystemController.class);

    @Inject
    private SuggesterService suggester;

    @PUT
    @Path("/refresh")
    @Consumes(MediaType.TEXT_PLAIN)
    public void refresh(final String project) {
        env.maybeRefreshIndexSearchers(Collections.singleton(project));
        CompletableFuture.runAsync(() -> suggester.rebuild(project));
    }

    @PUT
    @Path("/includes/reload")
    public void reloadIncludes() {
        env.getIncludeFiles().reloadIncludeFiles();
    }

    @POST
    @Path("/pathdesc")
    @Consumes(MediaType.APPLICATION_JSON)
    public void loadPathDescriptions(@Valid final PathDescription[] descriptions) throws IOException {
        EftarFile ef = new EftarFile();
        ef.create(Set.of(descriptions), env.getDtagsEftarPath().toString());
        LOGGER.log(Level.FINE, "reloaded path descriptions with {0} entries", descriptions.length);
    }

    @GET
    @Path("/indextime")
    @Produces(MediaType.APPLICATION_JSON)
    public String getIndexTime() throws JsonProcessingException {
        Date date = new IndexTimestamp().getDateForLastIndexRun();
        ObjectMapper mapper = new ObjectMapper();
        // StdDateFormat is ISO8601 since jackson 2.9
        mapper.setDateFormat(new StdDateFormat().withColonInTimeZone(true));
        return mapper.writeValueAsString(date);
    }
}
