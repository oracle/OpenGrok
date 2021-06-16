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
 * Copyright (c) 2017, 2018, Oracle and/or its affiliates. All rights reserved.
 */
package opengrok.auth.plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.servlet.http.HttpServletRequest;
import opengrok.auth.plugin.decoders.IUserDecoder;
import opengrok.auth.plugin.entity.User;
import org.opengrok.indexer.authorization.IAuthorizationPlugin;
import org.opengrok.indexer.configuration.Group;
import org.opengrok.indexer.configuration.Project;

/**
 * Authorization plug-in to extract user info from HTTP headers.
 *
 * @author Krystof Tulinger
 */
public class UserPlugin implements IAuthorizationPlugin {

    private static final Logger LOGGER = Logger.getLogger(UserPlugin.class.getName());

    private static final String DECODER_CLASS_PARAM = "decoder";

    public static final String REQUEST_ATTR = "opengrok-user-plugin-user";

    private IUserDecoder decoder;

    public UserPlugin() {
    }

    // for testing
    protected UserPlugin(IUserDecoder decoder) {
        this.decoder = decoder;
    }

    private IUserDecoder getDecoder(String name) throws ClassNotFoundException, NoSuchMethodException,
            IllegalAccessException, InvocationTargetException, InstantiationException {
        Class<?> clazz = Class.forName(name);
        Constructor<?> constructor = clazz.getConstructor();
        Object instance = constructor.newInstance();
        return (IUserDecoder) instance;
    }

    @Override
    public void load(Map<String, Object> parameters) {
        String decoder_name;
        if ((decoder_name = (String) parameters.get(DECODER_CLASS_PARAM)) == null) {
            throw new NullPointerException(String.format("missing " +
                    "parameter '%s' in %s configuration",
                    DECODER_CLASS_PARAM, UserPlugin.class.getName()));
        }

        LOGGER.log(Level.FINE, "loading decoder: {0}", decoder_name);
        try {
            decoder = getDecoder(decoder_name);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException |
                InvocationTargetException | InstantiationException e) {
            throw new RuntimeException("cannot load decoder " + decoder_name, e);
        }
    }

    @Override
    public void unload() {
    }

    private User getUser(HttpServletRequest request) {
        User user;

        if ((user = (User) request.getAttribute(REQUEST_ATTR)) == null) {
            user = decoder.fromRequest(request);
            request.setAttribute(REQUEST_ATTR, user);
        }

        return user;
    }

    @Override
    public boolean isAllowed(HttpServletRequest request, Project project) {
        return getUser(request) != null;
    }

    @Override
    public boolean isAllowed(HttpServletRequest request, Group group) {
        return getUser(request) != null;
    }
}
