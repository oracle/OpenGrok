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
 * Copyright (c) 2016, 2018, Oracle and/or its affiliates. All rights reserved.
 */
package org.opensolaris.opengrok.configuration.messages;

import java.io.IOException;
import java.text.ParseException;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.opensolaris.opengrok.util.Getopt;

public final class Messages {

    public static void main(String[] argv) {

        String type = null;
        String text = null;
        List<String> tags = new ArrayList<>();
        String cssClass = null;
        Duration duration = null;
        String server = null;
        int port = -1;
        String filepath = null;

        String x;

        Getopt getopt = new Getopt(argv, "c:d:f:g:hm:p:s:t:?");

        try {
            getopt.parse();
        } catch (ParseException ex) {
            System.err.println("Messages: " + ex.getMessage());
            b_usage();
            System.exit(1);
        }

        int cmd;
        getopt.reset();
        while ((cmd = getopt.getOpt()) != -1) {
            switch (cmd) {
                case 'c':
                    cssClass = getopt.getOptarg();
                    break;
                case 'd':
                    x = getopt.getOptarg();
                    try {
                        duration = Duration.parse(x);
                    } catch (DateTimeParseException e) {
                        System.err.println("Cannot parse " + x + ", allowed format: PnDTnHnMn.nS");
                        b_usage();
                        System.exit(1);
                    }
                    break;
                case 'f':
                    filepath = getopt.getOptarg();
                    break;
                case 'g':
                    tags.add(getopt.getOptarg());
                    break;
                case '?':
                case 'h':
                    a_usage();
                    System.exit(0);
                    break;
                case 'm':
                    type = getopt.getOptarg();
                    break;
                case 'p':
                    x = getopt.getOptarg();
                    try {
                        port = Integer.parseInt(x);
                    } catch (NumberFormatException e) {
                        System.err.println("Cannot parse " + x + " into integer");
                        b_usage();
                        System.exit(1);
                    }
                    break;
                case 's':
                    server = getopt.getOptarg();
                    break;
                case 't':
                    text = getopt.getOptarg();
                    break;
                default:
                    System.err.println("Internal Error - Not implemented option: " + (char) cmd);
                    b_usage();
                    System.exit(1);
                    break;
            }
        }

        if (type == null) {
            type = "normal";
        }

        if (server == null) {
            server = "localhost";
        }

        if (port == -1) {
            port = 2424;
        }

        Optional<Class<? extends Message>> messageType = getMessageClass(type);
        if (!messageType.isPresent()) {
            System.err.println("Unknown message type " + type);
            b_usage();
            System.exit(1);
        }

        Message.Builder messageBuilder = new Message.Builder<>(messageType.get());

        if (filepath != null) {
            try {
                messageBuilder.setTextFromFile(filepath);
            } catch (IOException ex) {
                System.err.println("Cannot read '" + filepath + "': " + ex);
                System.exit(1);
            }
        } else {
            messageBuilder.setText(text);
        }

        messageBuilder.setCssClass(cssClass);

        for (String tag : tags) {
            messageBuilder.addTag(tag);
        }

        if (duration != null) {
            messageBuilder.setDuration(duration);
        }

        Message m = messageBuilder.build();
        try {
            m.validate();
        } catch (Exception e) {
            System.err.println("This message is not valid:");
            System.err.println(e.getLocalizedMessage());
            System.exit(1);
        }

        try {
            Response response = m.write(server, port);
            for (String item : response.getData()) {
                System.out.println(item);
            }
        } catch (IOException ex) {
            System.err.println(ex.getMessage());
            ex.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static final void a_usage() {
        System.err.println("Usage:");
        System.err.println("Messages.java" + " [OPTIONS] -t <text>");
        System.err.println();
        System.err.println("OPTIONS:");
        System.err.println("Help");
        System.err.println("-?                   print this help message");
        System.err.println("-h                   print this help message");
        System.err.println();
        System.err.println("Message type");
        System.err.println("-m <type>            message type (default is 'normal')");
        System.err.println();
        System.err.println("Message text");
        System.err.println("-t <text>            text of the message");
        System.err.println("-f <path>            read the file into the message's text");
        System.err.println();
        System.err.println("Tags");
        System.err.println("-g <tag>             add a tag to the message (can be specified multiple times)");
        System.err.println();
        System.err.println("Class name");
        System.err.println("-c <class>           set the css class for the message (default is 'info')");
        System.err.println();
        System.err.println("Duration");
        System.err.println("-d <duration>        set the duration of the message (format PnDTnHnMn.nS)");
        System.err.println();
        System.err.println("Remote address");
        System.err.println("-s <remote address>  set the remote address where to send the message (default is 'localhost')");
        System.err.println("-p <port num>        set the remote port (default is '2424')");
        System.err.println();
        System.err.println();
        System.err.println("Examples");
        System.err.println("-t \"ahoj\"                                        # => send normal message without any tag");
        System.err.println("-t \"ahoj\" -g \"main\"                              # => send normal message with tag 'main'");
        System.err.println("-t \"ahoj\" -g \"main\" -c \"list-group-item-success\" # => send normal message with tag and css class name");
    }

    private static final void b_usage() {
        System.err.println("Maybe try to run Messages -h");
    }

    private static Optional<Class<? extends Message>> getMessageClass(final String messageType) {
        Class<?> messageClass;
        try {
            messageClass = getClassForMessageType(messageType);
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        }

        if (!isMessageSubclass(messageClass)) {
            return Optional.empty();
        }

        @SuppressWarnings("unchecked")
        Class<? extends Message> mcl = (Class<? extends Message>) messageClass;
        return Optional.of(mcl);
    }

    private static Class<?> getClassForMessageType(final String messageType) throws ClassNotFoundException {
        String classname = Message.class.getPackage().getName();
        classname += "." + messageType.substring(0, 1).toUpperCase(Locale.getDefault());
        classname += messageType.substring(1) + "Message";

        return Class.forName(classname);
    }

    private static boolean isMessageSubclass(final Class<?> cl) {
        return Message.class.isAssignableFrom(cl);
    }

}
