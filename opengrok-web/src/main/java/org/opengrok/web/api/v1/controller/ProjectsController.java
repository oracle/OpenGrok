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
 * Portions Copyright (c) 2020, Chris Fraire <cfraire@me.com>.
 */
package org.opengrok.web.api.v1.controller;

import static org.opengrok.indexer.history.RepositoryFactory.getRepository;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.opengrok.indexer.configuration.CommandTimeoutType;
import org.opengrok.indexer.configuration.Group;
import org.opengrok.indexer.configuration.Project;
import org.opengrok.indexer.configuration.RuntimeEnvironment;
import org.opengrok.indexer.history.HistoryException;
import org.opengrok.indexer.history.HistoryGuru;
import org.opengrok.indexer.history.Repository;
import org.opengrok.indexer.history.RepositoryInfo;
import org.opengrok.indexer.index.IndexDatabase;
import org.opengrok.indexer.logger.LoggerFactory;
import org.opengrok.indexer.util.ClassUtil;
import org.opengrok.indexer.util.ForbiddenSymlinkException;
import org.opengrok.indexer.util.IOUtils;
import org.opengrok.indexer.web.Laundromat;
import org.opengrok.web.api.v1.suggester.provider.service.SuggesterService;

@Path("/projects")
public class ProjectsController {

    private static final Logger logger = LoggerFactory.getLogger(ProjectsController.class);

    private final RuntimeEnvironment env = RuntimeEnvironment.getInstance();

    @Inject
    private SuggesterService suggester;

    @POST
    @Consumes(MediaType.TEXT_PLAIN)
    public Response addProject(String projectName) {
        // Avoid classification as a taint bug.
        projectName = Laundromat.launderInput(projectName);

        File srcRoot = env.getSourceRootFile();
        File projDir = new File(srcRoot, projectName);

        if (!env.getProjects().containsKey(projectName)) {
            Project project = new Project(projectName, "/" + projectName);

            if (env.isHistoryEnabled()) {
                // Add repositories in this project.
                List<RepositoryInfo> repos = getRepositoriesInDir(projDir);

                env.addRepositories(repos);
                env.getProjectRepositoriesMap().put(project, repos);
            }

            // Finally introduce the project to the configuration.
            // Note that the project is inactive in the UI until it is indexed.
            // See {@code isIndexed()}
            env.getProjects().put(projectName, project);
            env.populateGroups(env.getGroups(), new TreeSet<>(env.getProjectList()));
        } else {
            Project project = env.getProjects().get(projectName);
            Map<Project, List<RepositoryInfo>> map = env.getProjectRepositoriesMap();

            if (env.isHistoryEnabled()) {
                // Refresh the list of repositories of this project.
                // This is the goal of this action: if an existing project
                // is re-added, this means its list of repositories has changed.
                List<RepositoryInfo> repos = getRepositoriesInDir(projDir);
                List<RepositoryInfo> allrepos = env.getRepositories();
                synchronized (allrepos) {
                    // newly added repository
                    for (RepositoryInfo repo : repos) {
                        if (!allrepos.contains(repo)) {
                            allrepos.add(repo);
                        }
                    }
                    // deleted repository
                    if (map.containsKey(project)) {
                        for (RepositoryInfo repo : map.get(project)) {
                            if (!repos.contains(repo)) {
                                allrepos.remove(repo);
                            }
                        }
                    }
                }

                map.put(project, repos);
            }
        }

        return Response.status(Response.Status.CREATED).build();
    }

    private List<RepositoryInfo> getRepositoriesInDir(final File projDir) {

        HistoryGuru histGuru = HistoryGuru.getInstance();

        // There is no need to perform the work of invalidateRepositories(),
        // since addRepositories() calls getRepository() for each of
        // the repos.
        return new ArrayList<>(histGuru.addRepositories(new File[]{projDir}));
    }

    private Project disableProject(String projectName) {
        Project project = env.getProjects().get(projectName);
        if (project == null) {
            throw new IllegalStateException("cannot get project \"" + projectName + "\"");
        }

        // Remove the project from searches so no one can trip over incomplete index data.
        project.setIndexed(false);

        return project;
    }

    @DELETE
    @Path("/{project}")
    public void deleteProject(@PathParam("project") String projectName)
            throws HistoryException {
        // Avoid classification as a taint bug.
        projectName = Laundromat.launderInput(projectName);

        Project project = disableProject(projectName);
        logger.log(Level.FINE, "deleting configuration for project {0}", projectName);

        // Delete index data associated with the project.
        deleteProjectData(projectName);

        // Remove the project from its groups.
        for (Group group : project.getGroups()) {
            group.getRepositories().remove(project);
            group.getProjects().remove(project);
        }

        if (env.isHistoryEnabled()) {
            // Now remove the repositories associated with this project.
            List<RepositoryInfo> repos = env.getProjectRepositoriesMap().get(project);
            if (repos != null) {
                env.getRepositories().removeAll(repos);
            }
            env.getProjectRepositoriesMap().remove(project);
        }

        env.getProjects().remove(projectName, project);

        // Prevent the project to be included in new searches.
        env.refreshSearcherManagerMap();
    }

    @DELETE
    @Path("/{project}/data")
    public void deleteProjectData(@PathParam("project") String projectNameParam) {
        // Avoid classification as a taint bug.
        final String projectName = Laundromat.launderInput(projectNameParam);

        Project project = disableProject(projectName);
        logger.log(Level.FINE, "deleting data for project {0}", projectName);

        // Delete index and xrefs.
        for (String dirName: new String[]{IndexDatabase.INDEX_DIR, IndexDatabase.XREF_DIR}) {
            java.nio.file.Path path = Paths.get(env.getDataRootPath(), dirName, projectName);
            try {
                IOUtils.removeRecursive(path);
            } catch (IOException e) {
                logger.log(Level.WARNING, "Could not delete {0}", path.toString());
            }
        }

        deleteHistoryCache(projectName);

        // Delete suggester data.
        CompletableFuture.runAsync(() -> suggester.delete(projectName));
    }

    @DELETE
    @Path("/{project}/historycache")
    public void deleteHistoryCache(@PathParam("project") String projectName) {
        if (!env.isHistoryEnabled()) {
            return;
        }

        // Avoid classification as a taint bug.
        projectName = Laundromat.launderInput(projectName);

        Project project = disableProject(projectName);
        logger.log(Level.FINE, "deleting history cache for project {0}", projectName);

        List<RepositoryInfo> repos = env.getProjectRepositoriesMap().get(project);
        if (repos == null || repos.isEmpty()) {
            logger.log(Level.INFO, "history cache for project {0} is not present", projectName);
            return;
        }

        // Delete history cache data.
        HistoryGuru guru = HistoryGuru.getInstance();
        guru.removeCache(repos.stream().
                map(x -> {
                    try {
                        return env.getPathRelativeToSourceRoot(new File((x).getDirectoryName()));
                    } catch (ForbiddenSymlinkException e) {
                        logger.log(Level.FINER, e.getMessage());
                        return "";
                    } catch (IOException e) {
                        logger.log(Level.WARNING, "cannot remove files for repository {0}", x.getDirectoryName());
                        // Empty output should not cause any harm
                        // since {@code getReposFromString()} inside
                        // {@code removeCache()} will return nothing.
                        return "";
                    }
                }).filter(x -> !x.isEmpty()).collect(Collectors.toSet()));
    }

    @PUT
    @Path("/{project}/indexed")
    @Consumes(MediaType.TEXT_PLAIN)
    public void markIndexed(@PathParam("project") String projectNameParam) throws Exception {
        // Avoid classification as a taint bug.
        final String projectName = Laundromat.launderInput(projectNameParam);

        Project project = env.getProjects().get(projectName);
        if (project == null) {
            logger.log(Level.WARNING, "cannot find project {0} to mark as indexed", projectName);
            throw new NotFoundException(String.format("project '%s' does not exist", projectName));
        }

        project.setIndexed(true);

        // Refresh current version of the project's repositories.
        List<RepositoryInfo> riList = env.getProjectRepositoriesMap().get(project);
        if (riList != null) {
            for (RepositoryInfo ri : riList) {
                Repository repo = getRepository(ri, CommandTimeoutType.RESTFUL);

                if (repo != null && repo.getCurrentVersion() != null && repo.getCurrentVersion().length() > 0) {
                    // getRepository() always creates fresh instance
                    // of the Repository object so there is no need
                    // to call setCurrentVersion() on it.
                    ri.setCurrentVersion(repo.determineCurrentVersion());
                }
            }
        }

        CompletableFuture.runAsync(() -> suggester.rebuild(projectName));

        // In case this project has just been incrementally indexed,
        // its IndexSearcher needs a poke.
        env.maybeRefreshIndexSearchers(Collections.singleton(projectName));

        env.refreshDateForLastIndexRun();
    }

    @PUT
    @Path("/{project}/property/{field}")
    public void set(
            @PathParam("project") String projectName,
            @PathParam("field") String field,
            final String value
    ) throws Exception {
        // Avoid classification as a taint bug.
        projectName = Laundromat.launderInput(projectName);
        field = Laundromat.launderInput(field);

        Project project = env.getProjects().get(projectName);
        if (project != null) {
            // Set the property.
            ClassUtil.setFieldValue(project, field, value);

            // Refresh repositories for this project as well.
            List<RepositoryInfo> riList = env.getProjectRepositoriesMap().get(project);
            if (riList != null) {
                for (RepositoryInfo ri : riList) {
                    Repository repo = getRepository(ri, CommandTimeoutType.RESTFUL);

                    // set the property
                    ClassUtil.setFieldValue(repo, field, value);
                }
            }
        } else {
            logger.log(Level.WARNING, "cannot find project {0} to set a property", projectName);
        }
    }

    @GET
    @Path("/{project}/property/{field}")
    @Produces(MediaType.APPLICATION_JSON)
    public Object get(@PathParam("project") String projectName, @PathParam("field") String field)
            throws IOException {
        // Avoid classification as a taint bug.
        projectName = Laundromat.launderInput(projectName);
        field = Laundromat.launderInput(field);

        Project project = env.getProjects().get(projectName);
        if (project == null) {
            throw new WebApplicationException(
                    "cannot find project " + projectName + " to get a property", Response.Status.BAD_REQUEST);
        }
        return ClassUtil.getFieldValue(project, field);
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<String> listProjects() {
        return env.getProjectNames();
    }

    @GET
    @Path("indexed")
    @Produces(MediaType.APPLICATION_JSON)
    public List<String> listIndexed() {
        return env.getProjectList().stream()
                .filter(Project::isIndexed)
                .map(Project::getName)
                .collect(Collectors.toList());
    }

    @GET
    @Path("/{project}/repositories")
    @Produces(MediaType.APPLICATION_JSON)
    public List<String> getRepositories(@PathParam("project") String projectName) {
        // Avoid classification as a taint bug.
        projectName = Laundromat.launderInput(projectName);

        Project project = env.getProjects().get(projectName);
        if (project != null) {
            List<RepositoryInfo> infos = env.getProjectRepositoriesMap().get(project);
            if (infos != null) {
                return infos.stream()
                        .map(RepositoryInfo::getDirectoryNameRelative)
                        .collect(Collectors.toList());
            }
        }

        return Collections.emptyList();
    }

    @GET
    @Path("/{project}/repositories/type")
    @Produces(MediaType.APPLICATION_JSON)
    public Set<String> getRepositoriesType(@PathParam("project") String projectName) {
        // Avoid classification as a taint bug.
        projectName = Laundromat.launderInput(projectName);

        Project project = env.getProjects().get(projectName);
        if (project != null) {
            List<RepositoryInfo> infos = env.getProjectRepositoriesMap().get(project);
            if (infos != null) {
                return infos.stream()
                        .map(RepositoryInfo::getType)
                        .collect(Collectors.toSet());
            }
        }
        return Collections.emptySet();
    }

    @GET
    @Path("/{project}/files")
    @Produces(MediaType.APPLICATION_JSON)
    public Set<String> getProjectIndexFiles(@PathParam("project") String projectName) throws IOException {
        // Avoid classification as a taint bug.
        projectName = Laundromat.launderInput(projectName);

        return IndexDatabase.getAllFiles(Collections.singletonList("/" + projectName));
    }
}
