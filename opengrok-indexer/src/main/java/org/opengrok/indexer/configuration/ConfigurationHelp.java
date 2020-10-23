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
 * Copyright (c) 2018-2020, Chris Fraire <cfraire@me.com>.
 */

package org.opengrok.indexer.configuration;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.opengrok.indexer.authorization.AuthControlFlag;
import org.opengrok.indexer.authorization.AuthorizationPlugin;
import org.opengrok.indexer.authorization.AuthorizationStack;
import org.opengrok.indexer.history.RepositoryInfo;
import org.opengrok.indexer.util.StringUtils;

/**
 * Represents a utility class to present some user-readable help regarding
 * {@link Configuration}.
 */
public class ConfigurationHelp {

    private static final String XML_COMMENT_START = "  <!-- ";

    private ConfigurationHelp() {
    }

    /**
     * Gets sample content for a configuration XML file.
     * @return a defined instance
     * @throws RuntimeException if an error occurs producing the sample
     */
    public static String getSamples() throws RuntimeException {

        Configuration conf = new Configuration();
        Class<?> klass = conf.getClass();

        StringBuilder b = new StringBuilder();
        LinesBuilder h = new LinesBuilder();

        b.append("Configuration examples:\n");
        b.append("\n");

        String sample = conf.getXMLRepresentationAsString();
        b.append("<!-- Sample empty configuration.xml -->\n");
        b.append(sample);
        b.append("\n");

        List<Method> mthds = getSetters(klass);
        for (Method mthd : mthds) {
            // Get a pristine instance.
            conf = new Configuration();
            Object defaultValue = getDefaultValue(klass, mthd, conf);
            // Get a pristine instance.
            conf = new Configuration();
            Object sampleValue = getSampleValue(mthd, defaultValue);
            if (sampleValue == null) {
                continue;
            }

            try {
                mthd.invoke(conf, sampleValue);
            } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException ex) {
                throw new RuntimeException("error setting sample value " + sampleValue + " for " + mthd);
            }

            sample = conf.getXMLRepresentationAsString();
            sample = sample.replaceFirst(
                "(?sx)^<\\?xml.*Configuration\\d*\">\\n", "");
            sample = sample.replaceFirst("</object>\\n</java>", "");

            h.clear();
            h.append(XML_COMMENT_START);
            h.append("Sample for ");
            h.append(mthd.getName());
            h.append(". Default is ");
            h.appendWords(defaultValue);
            h.appendWords(" -->");
            h.appendLine();

            b.append(h);
            b.append(sample);
        }
        return b.toString();
    }

    private static List<Method> getSetters(Class<?> klass) {
        List<Method> res = new ArrayList<>();
        Method[] methods = klass.getDeclaredMethods();
        for (Method mth : methods) {
            int mod = mth.getModifiers();
            if (Modifier.isPublic(mod) && !Modifier.isStatic(mod) &&
                mth.getParameterCount() == 1 &&
                mth.getName().matches("^set.*") && !isDeprecated(mth)) {
                res.add(mth);
            }
        }
        res.sort((o1, o2) -> o1.getName().compareToIgnoreCase(o2.getName()));
        return res;
    }

    private static Object getSampleValue(Method setter, Object defaultValue) {

        Class<?> paramType = setter.getParameterTypes()[0];
        Type genType = setter.getGenericParameterTypes()[0];

        if (setter.getName().equals("setBugPattern")) {
            return "Sample Bug \\#(\\d+)";
        } else if (setter.getName().equals("setReviewPattern")) {
            return "Sample Issue \\#(\\d+)";
        } else if (paramType == String.class) {
            return "user-specified-value";
        } else if (paramType == int.class) {
            return 1 + (int) defaultValue;
        } else if (paramType == long.class) {
            return 1 + (long) defaultValue;
        } else if (paramType == short.class) {
            return (short) (1 + (short) defaultValue);
        } else if (paramType == boolean.class) {
            if (defaultValue == null) {
                return null;
            }
            return !(boolean) defaultValue;
        } else if (paramType == double.class) {
            return 1 + (double) defaultValue;
        } else if (paramType == List.class) {
            return getSampleListValue(genType);
        } else if (paramType == Map.class) {
            return getSampleMapValue(genType);
        } else if (paramType == Set.class) {
            return getSampleSetValue(genType);
        } else if (paramType == AuthorizationStack.class) {
            AuthorizationStack astck = new AuthorizationStack(
                AuthControlFlag.REQUIRED, "user-specified-value");
            astck.add(new AuthorizationPlugin(AuthControlFlag.REQUISITE,
                "user-specified-value"));
            return astck;
        } else if (paramType == Filter.class) {
            Filter flt = new Filter();
            flt.add("user-specified-(patterns)*");
            flt.add("user-specified-filename");
            flt.add("user/specified/path");
            return flt;
        } else if (paramType == IgnoredNames.class) {
            IgnoredNames inm = new IgnoredNames();
            inm.add("f:user-specified-value");
            inm.add("d:user-specified-value");
            return inm;
        } else if (paramType == MeterRegistryType.class) {
            return MeterRegistryType.GRAPHITE;
        } else if (paramType.isEnum()) {
            for (Object value : paramType.getEnumConstants()) {
                if (!value.equals(defaultValue)) {
                    return value;
                }
            }
            return null;
        } else if (paramType == SuggesterConfig.class) {
            return SuggesterConfig.getForHelp();
        } else if (paramType == BaseStatsdConfig.class) {
            return BaseStatsdConfig.getForHelp();
        } else if (paramType == BaseGraphiteConfig.class) {
            return BaseGraphiteConfig.getForHelp();
        } else {
            throw new UnsupportedOperationException("getSampleValue() for " +
                paramType + ", " + genType);
        }
    }

    private static Object getSampleListValue(Type genType) {
        if (!(genType instanceof ParameterizedType)) {
            return null;
        }
        ParameterizedType genParamType = (ParameterizedType) genType;
        Type actType = genParamType.getActualTypeArguments()[0];

        if (actType != RepositoryInfo.class) {
            throw new UnsupportedOperationException("Not supported yet for " + actType);
        }
        return null;
    }

    private static Object getSampleMapValue(Type genType) {
        if (!(genType instanceof ParameterizedType)) {
            return null;
        }
        ParameterizedType genParamType = (ParameterizedType) genType;
        Type[] actualTypeArguments = genParamType.getActualTypeArguments();
        Type actType0 = actualTypeArguments[0];
        Type actType1 = actualTypeArguments[1];
        Object res;

        if (actType0 == String.class) {
            if (actType1 == String.class) {
                Map<String, String> strmap = new TreeMap<>();
                strmap.put("user-defined-key", "user-defined-value");
                res = strmap;
            } else if (actType1 == Project.class) {
                Map<String, Project> strmap = new TreeMap<>();
                String nm = "user-defined-key";
                strmap.put(nm, getSampleProject(nm));
                res = strmap;
            } else {
                throw new UnsupportedOperationException(
                    "Not supported yet for " + actType0 + " " + actType1);
            }
        } else {
            throw new UnsupportedOperationException("Not supported yet for " +
                actType0 + " " + actType1);
        }
        return res;
    }

    private static Object getSampleSetValue(Type genType) {
        if (!(genType instanceof ParameterizedType)) {
            return null;
        }
        ParameterizedType genParamType = (ParameterizedType) genType;
        Type actType = genParamType.getActualTypeArguments()[0];
        Object res;

        if (actType == String.class) {
            Set<String> strset = new HashSet<>();
            strset.add("user-defined-element");
            res = strset;
        } else if (actType == Group.class) {
            Set<Group> grpset = new HashSet<>();
            Group g = new Group("user-defined-name", "user-defined-pattern");
            grpset.add(g);
            res = grpset;
        } else if (actType == Project.class) {
            Set<Project> prjset = new HashSet<>();
            Project p = getSampleProject("user-defined-name");
            prjset.add(p);
            res = prjset;
        } else {
            throw new UnsupportedOperationException("Not supported yet for " +
                actType);
        }
        return res;
    }

    private static Project getSampleProject(String name) {
        Project p = new Project(name, "/user/defined/path");
        p.setNavigateWindowEnabled(true);
        p.setTabSize(8);
        return p;
    }

    private static Object getDefaultValue(Class<?> klass, Method setter,
        Configuration cinst) {

        String gname = setter.getName().replaceFirst("^set", "get");
        Method getter;
        try {
            getter = klass.getDeclaredMethod(gname);
        } catch (NoSuchMethodException | SecurityException ex) {
            gname = setter.getName().replaceFirst("^set", "is");
            try {
                getter = klass.getDeclaredMethod(gname);
            } catch (NoSuchMethodException | SecurityException ex2) {
                return null;
            }
        }

        // Return a text override for some objects.
        switch (gname) {
            case "getSuggesterConfig":
                return "as below but with Boolean opposites, non-zeroes decremented by 1, null " +
                        "for allowed-projects, and also including \"full\" in allowed-fields";
            case "getPluginStack":
                return "an empty stack";
            case "getIncludedNames":
                return "an empty filter";
            case "getIgnoredNames":
                return "OpenGrok's standard set of ignored files and directories";
        }

        try {
            return getter.invoke(cinst);
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException ex) {
            return null;
        }
    }

    private static boolean isDeprecated(Method mth) {
        for (Annotation annotation : mth.getAnnotations()) {
            if (annotation instanceof Deprecated) {
                return true;
            }
        }
        return false;
    }

    private static class LinesBuilder {
        static final int LINE_LENGTH = 80;

        final StringBuilder b = new StringBuilder();
        int length;

        void clear() {
            b.setLength(0);
            length = 0;
        }

        void append(String value) {
            b.append(value);
            length += value.length();
        }

        void appendWords(Object value) {
            if (value == null) {
                appendWords("null");
            } else {
                appendWords(value.toString());
            }
        }

        void appendWords(String value) {
            if (length > LINE_LENGTH) {
                appendLine();
            }

            int i = 0;
            while (true) {
                int spaceLen = StringUtils.whitespaceOrControlLength(value, i, true);
                int wordLen = StringUtils.whitespaceOrControlLength(value, i + spaceLen, false);

                if (wordLen < 1) {
                    break;
                }

                String word = value.substring(i + spaceLen, i + spaceLen + wordLen);
                if (length + spaceLen + wordLen > LINE_LENGTH) {
                    appendLine();
                    for (int j = 0; j < XML_COMMENT_START.length(); ++j) {
                        append(" ");
                    }
                    append(word);
                } else {
                    append(value.substring(i, i + spaceLen));
                    append(word);
                }

                i += spaceLen + wordLen;
            }
        }

        void appendLine() {
            b.append("\n");
            length = 0;
        }

        @Override
        public String toString() {
            return b.toString();
        }
    }
}
