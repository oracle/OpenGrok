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
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
 * Portions Copyright (c) 2020, Chris Fraire <cfraire@me.com>.
 */
package opengrok.auth.plugin;

import jakarta.servlet.http.HttpServletRequest;
import opengrok.auth.plugin.entity.User;
import org.opengrok.indexer.authorization.IAuthorizationPlugin;
import org.opengrok.indexer.configuration.Group;
import org.opengrok.indexer.configuration.Project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

public class UserWhiteListPlugin implements IAuthorizationPlugin {
    private static final String className = UserWhiteListPlugin.class.getName();
    private static final Logger LOGGER = Logger.getLogger(className);

    // configuration parameters
    static final String FILE_PARAM = "file";
    static final String FIELD_PARAM = "fieldName";

    // valid values for the FIELD_NAME parameter
    static final String USERNAME_FIELD = "username";
    static final String ID_FIELD = "id";

    private final Set<String> whitelist = new TreeSet<>();
    private String fieldName;

    @Override
    public void load(Map<String, Object> parameters) {
        String filePath;
        if ((filePath = (String) parameters.get(FILE_PARAM)) == null) {
            throw new IllegalArgumentException("Missing parameter [" + FILE_PARAM + "] in the configuration");
        }

        if ((fieldName = (String) parameters.get(FIELD_PARAM)) == null) {
            fieldName = USERNAME_FIELD;
        } else if (!fieldName.equals(USERNAME_FIELD) && !fieldName.equals(ID_FIELD)) {
            throw new IllegalArgumentException("Invalid value of parameter [" + FIELD_PARAM +
                    "] in the configuration: only " + USERNAME_FIELD + " or " + ID_FIELD + " are supported");
        }

        // Load whitelist from file to memory.
        try (Stream<String> stream = Files.lines(Paths.get(filePath))) {
            stream.forEach(whitelist::add);
        } catch (IOException e) {
            throw new IllegalArgumentException(String.format("Unable to read the file \"%s\"", filePath), e);
        }

        LOGGER.log(Level.FINER, "UserWhiteList plugin loaded with filePath={0} ({1} entries), fieldName={2}",
                new Object[]{filePath, whitelist.size(), fieldName});
    }

    @Override
    public void unload() {
        whitelist.clear();
    }

    private boolean checkWhitelist(HttpServletRequest request) {
        User user;
        String attrName = UserPlugin.REQUEST_ATTR;
        if ((user = (User) request.getAttribute(attrName)) == null) {
            LOGGER.log(Level.WARNING, "cannot get {0} attribute", attrName);
            return false;
        }

        if (fieldName.equals(USERNAME_FIELD)) {
            return user.getUsername() != null && whitelist.contains(user.getUsername());
        } else if (fieldName.equals(ID_FIELD)) {
            return user.getId() != null && whitelist.contains(user.getId());
        }

        return false;
    }

    @Override
    public boolean isAllowed(HttpServletRequest request, Project project) {
        return checkWhitelist(request);
    }

    @Override
    public boolean isAllowed(HttpServletRequest request, Group group) {
        return checkWhitelist(request);
    }
}
