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
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
 * Portions Copyright (c) 2018, Chris Fraire <cfraire@me.com>.
 */
package org.opensolaris.opengrok.analysis;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * This class lets you create {@code InputStream}s that read data from a
 * specific source. It could be used if you need to pass a stream as an
 * argument to a method where the stream may need to be read multiple times.
 * Instead of passing the stream directly, you pass a {@code StreamSource}
 * instance that generates the stream. The receiver may call
 * {@link #getStream()} multiple times, getting a fresh stream each time,
 * so that there may be multiple, concurrent readers that don't interfere
 * with each other.
 */
public abstract class StreamSource {

    protected static final String SHA256 = "SHA-256";

    /**
     * Get a stream that reads data from the input source. Every call should
     * return a new instance so that multiple readers can read from the source
     * without interfering with each other.
     *
     * @return an {@code InputStream}
     * @throws IOException if an error occurs when opening the stream
     */
    public abstract InputStream getStream() throws IOException;

    /**
     * Gets a stream that reads data from the input source and produces a
     * SHA-256 digest. Every call should return a new instance so that multiple
     * readers can read from the source without interfering with each other.
     *
     * @return a defined instance
     * @throws IOException if an error occurs when opening the stream
     */
    public abstract DigestedInputStream getSHA256stream() throws IOException;

    /**
     * Helper method that creates a {@code StreamSource} instance that
     * reads data from a file.
     *
     * @param file the data file
     * @return a stream source that reads from {@code file}
     */
    public static StreamSource fromFile(final File file) {
        return new StreamSource() {
            @Override
            public InputStream getStream() throws IOException {
                return new BufferedInputStream(new FileInputStream(file));
            }

            @Override
            public DigestedInputStream getSHA256stream() throws IOException {
                return getSHA256stream(getStream());
            }
        };
    }

    protected static DigestedInputStream getSHA256stream(InputStream under)
            throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance(SHA256);
        } catch (NoSuchAlgorithmException ex) {
            /*
             * NOT EXPECTED because "Every implementation of the Java
             * platform is required to support ... SHA-256 ...."
             */
            throw new IOException("digest failed");
        }
        DigestInputStream dis = new DigestInputStream(under, md);

        return new DigestedInputStream() {
            @Override
            public InputStream getStream() {
                return dis;
            }

            @Override
            public MessageDigest getMessageDigest() {
                return dis.getMessageDigest();
            }

            @Override
            public byte[] digestAll() throws IOException {
                return StreamSource.digestAll(this);
            }

            @Override
            public void close() throws Exception {
                dis.close();
            }
        };
    }

    protected static byte[] digestAll(DigestedInputStream dis)
            throws IOException {
        byte[] buf = new byte[64 * 1024];
        InputStream iss = dis.getStream();
        while (iss.read(buf) != -1) {
            // iterate
        }
        return dis.getMessageDigest().digest();
    }
}
