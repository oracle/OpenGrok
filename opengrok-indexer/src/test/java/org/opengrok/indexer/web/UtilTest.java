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
 * Copyright (c) 2007, 2020, Oracle and/or its affiliates. All rights reserved.
 * Portions Copyright (c) 2017, 2019, Chris Fraire <cfraire@me.com>.
 */

package org.opengrok.indexer.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.servlet.http.HttpServletRequest;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.opengrok.indexer.Metrics;
import org.opengrok.indexer.configuration.RuntimeEnvironment;
import org.opengrok.indexer.util.PlatformUtils;

import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

/**
 * Test of the methods in <code>org.opengrok.indexer.web.Util</code>.
 */
public class UtilTest {

    private static Locale savedLocale;

    @BeforeClass
    public static void setUpClass() {
        // Some of the methods have different results in different locales.
        // Set locale to en_US for these tests.
        savedLocale = Locale.getDefault();
        Locale.setDefault(Locale.US);
        Metrics.getInstance().configure(RuntimeEnvironment.getInstance().getWebAppMeterRegistryType());
    }

    @AfterClass
    public static void tearDownClass() {
        Locale.setDefault(savedLocale);
        savedLocale = null;
    }

    @Test
    public void htmlize() throws IOException {
        String[][] input_output = {
                {"This is a test", "This is a test"},
                {"Newline\nshould become <br/>", "Newline<br/>should become &lt;br/&gt;"},
                {"Open & Grok", "Open &amp; Grok"},
                {"&amp;&lt;&gt;", "&amp;amp;&amp;lt;&amp;gt;"}};
        for (String[] in_out : input_output) {
            // 1 arg
            assertEquals(in_out[1], Util.htmlize(in_out[0]));
            // 2 args
            StringBuilder sb = new StringBuilder();
            Util.htmlize(in_out[0], sb);
            assertEquals(in_out[1], sb.toString());
        }
    }

    @Test
    public void breadcrumbPath() {
        assertNull(Util.breadcrumbPath("/root/", null));

        assertEquals("", Util.breadcrumbPath("/root/", ""));

        assertEquals("<a href=\"/root/x\">x</a>",
                Util.breadcrumbPath("/root/", "x"));
        assertEquals("<a href=\"/root/xx\">xx</a>",
                Util.breadcrumbPath("/root/", "xx"));

        // parent directories have a trailing slash in href
        assertEquals("<a href=\"/r/a/\">a</a>/<a href=\"/r/a/b\">b</a>",
                Util.breadcrumbPath("/r/", "a/b"));
        // if basename is a dir (ends with file seperator), href link also
        // ends with a '/'
        assertEquals("<a href=\"/r/a/\">a</a>/<a href=\"/r/a/b/\">b</a>/",
                Util.breadcrumbPath("/r/", "a/b/"));
        // should work the same way with a '.' as file separator
        assertEquals("<a href=\"/r/java/\">java</a>."
                        + "<a href=\"/r/java/lang/\">lang</a>."
                        + "<a href=\"/r/java/lang/String\">String</a>",
                Util.breadcrumbPath("/r/", "java.lang.String", '.'));
        // suffix added to the link?
        assertEquals("<a href=\"/root/xx&project=y\">xx</a>",
                Util.breadcrumbPath("/root/", "xx", '/', "&project=y", false));
        // compact: path needs to be resolved to /xx and no link is added
        // for the virtual root directory (parent) but emitted as plain text.
        // Prefix gets just prefixed as is and not mangled wrt. path -> "//"
        assertEquals("/<a href=\"/root//xx&project=y\">xx</a>",
                Util.breadcrumbPath("/root/", "../xx", '/', "&project=y", true));
        // relative pathes are resolved wrt. / , so path resolves to /a/c/d 
        assertEquals("/<a href=\"/r//a/\">a</a>/"
                        + "<a href=\"/r//a/c/\">c</a>/"
                        + "<a href=\"/r//a/c/d\">d</a>",
                Util.breadcrumbPath("/r/", "../a/b/../c//d", '/', "", true));
    }

    @Test
    public void redableSize() {
        assertEquals("0 ", Util.readableSize(0));
        assertEquals("1 ", Util.readableSize(1));
        assertEquals("-1 ", Util.readableSize(-1));
        assertEquals("1,000 ", Util.readableSize(1000));
        assertEquals("1 KiB", Util.readableSize(1024));
        assertEquals("2.4 KiB", Util.readableSize(2500));
        assertEquals("<b>1.4 MiB</b>", Util.readableSize(1474560));
        assertEquals("<b>3.5 GiB</b>", Util.readableSize(3758489600L));
        assertEquals("<b>8,589,934,592 GiB</b>",
                Util.readableSize(Long.MAX_VALUE));
    }

    @Test
    public void readableLine() throws Exception {
        StringWriter out = new StringWriter();
        // hmmm - where do meaningful tests start?
        Util.readableLine(42, out, null, null, null, null);
        assertEquals("\n<a class=\"l\" name=\"42\" href=\"#42\">42</a>",
                out.toString());

        out.getBuffer().setLength(0); // clear buffer
        Util.readableLine(110, out, null, null, null, null);
        assertEquals("\n<a class=\"hl\" name=\"110\" href=\"#110\">110</a>",
                out.toString());
    }

    @Test
    public void path2uid() {
        assertEquals("\u0000etc\u0000passwd\u0000date",
                Util.path2uid("/etc/passwd", "date"));
    }

    @Test
    public void fixPathIfWindows() {
        if (PlatformUtils.isWindows()) {
            assertEquals("/var/opengrok",
                    Util.fixPathIfWindows("\\var\\opengrok"));
        }
    }

    @Test
    public void uid2url() {
        assertEquals("/etc/passwd", Util.uid2url(
                Util.path2uid("/etc/passwd", "date")));
    }

    @Test
    public void URIEncode() {
        assertEquals("", Util.URIEncode(""));
        assertEquals("a+b", Util.URIEncode("a b"));
        assertEquals("a%23b", Util.URIEncode("a#b"));
        assertEquals("a%2Fb", Util.URIEncode("a/b"));
        assertEquals("README.txt", Util.URIEncode("README.txt"));
    }

    @Test
    public void URIEncodePath() {
        assertEquals("", Util.URIEncodePath(""));
        assertEquals("/", Util.URIEncodePath("/"));
        assertEquals("a", Util.URIEncodePath("a"));
        assertEquals("%09", Util.URIEncodePath("\t"));
        assertEquals("a%2Bb", Util.URIEncodePath("a+b"));
        assertEquals("a%20b", Util.URIEncodePath("a b"));
        assertEquals("/a//x/yz/%23%23/%20/%20%3F",
                Util.URIEncodePath("/a//x/yz/##/ / ?"));
        assertEquals("foo%3A%3Abar%3A%3Atest.js",
                Util.URIEncodePath("foo::bar::test.js"));
        assertEquals("bl%C3%A5b%C3%A6rsyltet%C3%B8y",
                Util.URIEncodePath("bl\u00E5b\u00E6rsyltet\u00F8y"));
    }

    @Test
    public void formQuoteEscape() {
        assertEquals("", Util.formQuoteEscape(null));
        assertEquals("abc", Util.formQuoteEscape("abc"));
        assertEquals("&quot;abc&quot;", Util.formQuoteEscape("\"abc\""));
        assertEquals("&amp;aring;", Util.formQuoteEscape("&aring;"));
    }

    @Test
    public void diffline() {
        String[][] tests = {
                {
                        "if (a < b && foo < bar && c > d)",
                        "if (a < b && foo > bar && c > d)",
                        "if (a &lt; b &amp;&amp; foo <span class=\"d\">&lt;</span> bar &amp;&amp; c &gt; d)",
                        "if (a &lt; b &amp;&amp; foo <span class=\"a\">&gt;</span> bar &amp;&amp; c &gt; d)"
                },
                {
                        "foo << 1",
                        "foo >> 1",
                        "foo <span class=\"d\">&lt;&lt;</span> 1",
                        "foo <span class=\"a\">&gt;&gt;</span> 1"
                },
                {
                        "\"(ses_id, mer_id, pass_id, \" + refCol +\" , mer_ref, amnt, "
                                + "cur, ps_id, ret_url, d_req_time, d_req_mil, h_resp_time, "
                                + "h_resp_mil) \"",
                        "\"(ses_id, mer_id, pass_id, \" + refCol +\" , mer_ref, amnt, "
                                + "cur, ps_id, ret_url, exp_url, d_req_time, d_req_mil, "
                                + "h_resp_time, h_resp_mil) \"",
                        "&quot;(ses_id, mer_id, pass_id, &quot; + refCol +&quot; , mer_ref, amnt, "
                                + "cur, ps_id, ret_url, d_req_time, d_req_mil, h_resp_time, "
                                + "h_resp_mil) &quot;",
                        "&quot;(ses_id, mer_id, pass_id, &quot; + refCol +&quot; , mer_ref, amnt, "
                                + "cur, ps_id, ret_url, <span class=\"a\">exp_url, "
                                + "</span>d_req_time, d_req_mil, h_resp_time, h_resp_mil) &quot;"
                },
                {
                        "\"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)\", values);",
                        "\"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)\", values);",
                        "&quot;VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)&quot;, values);",
                        "&quot;VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?<span "
                                + "class=\"a\">, ?</span>)&quot;, values);"
                },
                {
                        "char    *config_list = NULL;",
                        "char    **config_list = NULL;",
                        "char    *config_list = NULL;",
                        "char    *<span class=\"a\">*</span>config_list = NULL;"
                },
                {
                        "char    **config_list = NULL;",
                        "char    *config_list = NULL;",
                        "char    *<span class=\"d\">*</span>config_list = NULL;",
                        "char    *config_list = NULL;"
                },
                {
                        "* An error occured or there is non-numeric stuff at the end",
                        "* An error occurred or there is non-numeric stuff at the end",
                        "* An error occured or there is non-numeric stuff at the end",
                        "* An error occur<span class=\"a\">r</span>ed or there is "
                                + "non-numeric stuff at the end"
                }
        };
        for (int i = 0; i < tests.length; i++) {
            String[] strings = Util.diffline(
                    new StringBuilder(tests[i][0]),
                    new StringBuilder(tests[i][1]));
            assertEquals("" + i + "," + 0, tests[i][2], strings[0]);
            assertEquals("" + i + "," + 1, tests[i][3], strings[1]);
        }
    }

    @Test
    public void testEncode() {
        String[][] tests = new String[][] {
                {"Test <code>title</code>", "Test&nbsp;&#60;code&#62;title&#60;/code&#62;"},
                {"ahoj", "ahoj"},
                {"<>|&\"'", "&#60;&#62;|&#38;&#34;&#39;"},
                {"tab\ttab", "tab&nbsp;&nbsp;&nbsp;&nbsp;tab"},
                {"multi\nline\t\nstring", "multi&lt;br/&gt;line&nbsp;&nbsp;&nbsp;&nbsp;&lt;br/&gt;string"}
        };

        for (String[] test : tests) {
            assertEquals(test[1], Util.encode(test[0]));
        }
    }

    @Test
    public void dumpConfiguration() throws Exception {
        StringBuilder out = new StringBuilder();
        Util.dumpConfiguration(out);
        String s = out.toString();

        // Verify that we got a table.
        assertTrue(s.startsWith("<table"));

        // Verify that the output is well-formed.
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + s;
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void jsStringLiteral() {
        assertEquals("\"abc\\n\\r\\\"\\\\\"",
                Util.jsStringLiteral("abc\n\r\"\\"));
    }

    @Test
    public void stripPathPrefix() {
        assertEquals("/", Util.stripPathPrefix("/", "/"));
        assertEquals("/abc", Util.stripPathPrefix("/abc", "/abc"));
        assertEquals("/abc/", Util.stripPathPrefix("/abc", "/abc/"));
        assertEquals("/abc", Util.stripPathPrefix("/abc/", "/abc"));
        assertEquals("/abc/", Util.stripPathPrefix("/abc/", "/abc/"));
        assertEquals("abc", Util.stripPathPrefix("/", "/abc"));
        assertEquals("abc/def", Util.stripPathPrefix("/", "/abc/def"));
        assertEquals("def", Util.stripPathPrefix("/abc", "/abc/def"));
        assertEquals("def", Util.stripPathPrefix("/abc/", "/abc/def"));
        assertEquals("/abcdef", Util.stripPathPrefix("/abc", "/abcdef"));
        assertEquals("/abcdef", Util.stripPathPrefix("/abc/", "/abcdef"));
        assertEquals("def/ghi", Util.stripPathPrefix("/abc", "/abc/def/ghi"));
        assertEquals("def/ghi", Util.stripPathPrefix("/abc/", "/abc/def/ghi"));
    }

    @Test
    public void testSlider() {
        /*
         * Test if contains all five pages for 55 results paginated by 10
         */
        for (int i = 0; i < 10; i++) {
            for (int j = 1; j <= 5; j++) {
                assertTrue("Contains page " + j, Util.createSlider(i * 10, 10, 55).contains(">" + j + "<"));
            }
        }

        assertFalse("Does not contain page 1", Util.createSlider(0, 10, 4).contains(">1<"));
        assertFalse("Does not contain page 5", Util.createSlider(0, 10, 2).contains(">5<"));
        assertFalse("Does not contain page 1", Util.createSlider(0, 10, 0).contains(">1<"));
    }

    @Test
    public void testIsUrl() {
        assertTrue(Util.isHttpUri("http://www.example.com"));
        assertTrue(Util.isHttpUri("http://example.com"));
        assertTrue(Util.isHttpUri("https://example.com"));
        assertTrue(Util.isHttpUri("https://www.example.com"));
        assertTrue(Util.isHttpUri("http://www.example.com?param=1&param2=2"));
        assertTrue(Util.isHttpUri("http://www.example.com/other/page"));
        assertTrue(Util.isHttpUri("https://www.example.com?param=1&param2=2"));
        assertTrue(Util.isHttpUri("https://www.example.com/other/page"));
        assertTrue(Util.isHttpUri("http://www.example.com:80/other/page"));
        assertTrue(Util.isHttpUri("http://www.example.com:8080/other/page"));
        assertTrue(Util.isHttpUri("https://www.example.com:80/other/page"));
        assertTrue(Util.isHttpUri("https://www.example.com:8080/other/page"));

        assertFalse(Util.isHttpUri("git@github.com:OpenGrok/OpenGrok"));
        assertFalse(Util.isHttpUri("hg@mercurial.com:OpenGrok/OpenGrok"));
        assertFalse(Util.isHttpUri("ssh://git@github.com:OpenGrok/OpenGrok"));
        assertFalse(Util.isHttpUri("ldap://example.com/OpenGrok/OpenGrok"));
        assertFalse(Util.isHttpUri("smtp://example.com/OpenGrok/OpenGrok"));
    }

    @Test
    public void testRedactUrl() {
        assertEquals("/foo/bar", Util.redactUrl("/foo/bar"));
        assertEquals("http://foo/bar?r=xxx", Util.redactUrl("http://foo/bar?r=xxx"));
        assertEquals("http://" + Util.REDACTED_USER_INFO + "@foo/bar?r=xxx",
                Util.redactUrl("http://user@foo/bar?r=xxx"));
        assertEquals("http://" + Util.REDACTED_USER_INFO + "@foo/bar?r=xxx",
                Util.redactUrl("http://user:pass@foo/bar?r=xxx"));
    }

    @Test
    public void testLinkify() throws URISyntaxException, MalformedURLException {
        assertTrue(Util.linkify("http://www.example.com")
                .matches("<a.*?href=\"http://www\\.example\\.com\".*?>.*?</a>"));
        assertTrue(Util.linkify("https://example.com")
                .matches("<a.*?href=\"https://example\\.com\".*?>.*?</a>"));
        assertTrue(Util.linkify("http://www.example.com?param=1&param2=2")
                .matches("<a.*?href=\"http://www\\.example\\.com\\?param=1&param2=2\".*?>.*?</a>"));
        assertTrue(Util.linkify("https://www.example.com:8080/other/page")
                .matches("<a.*?href=\"https://www\\.example\\.com:8080/other/page\".*?>.*?</a>"));

        assertTrue(Util.linkify("http://www.example.com", true).contains("target=\"_blank\""));
        assertTrue(Util.linkify("https://example.com", true).contains("target=\"_blank\""));
        assertTrue(Util.linkify("http://www.example.com?param=1&param2=2", true).contains("target=\"_blank\""));
        assertTrue(Util.linkify("https://www.example.com:8080/other/page", true).contains("target=\"_blank\""));

        assertFalse(Util.linkify("http://www.example.com", false).contains("target=\"_blank\""));
        assertFalse(Util.linkify("https://example.com", false).contains("target=\"_blank\""));
        assertFalse(Util.linkify("http://www.example.com?param=1&param2=2", false).contains("target=\"_blank\""));
        assertFalse(Util.linkify("https://www.example.com:8080/other/page", false).contains("target=\"_blank\""));

        assertEquals("git@github.com:OpenGrok/OpenGrok", Util.linkify("git@github.com:OpenGrok/OpenGrok"));
        assertEquals("hg@mercurial.com:OpenGrok/OpenGrok", Util.linkify("hg@mercurial.com:OpenGrok/OpenGrok"));
        assertEquals("ssh://git@github.com:OpenGrok/OpenGrok", Util.linkify("ssh://git@github.com:OpenGrok/OpenGrok"));
        assertEquals("ldap://example.com/OpenGrok/OpenGrok", Util.linkify("ldap://example.com/OpenGrok/OpenGrok"));
        assertEquals("smtp://example.com/OpenGrok/OpenGrok", Util.linkify("smtp://example.com/OpenGrok/OpenGrok"));
        assertEquals("just some crazy text", Util.linkify("just some crazy text"));

        // escaping url
        assertTrue(Util.linkify("http://www.example.com/\"quotation\"/else")
                .contains("href=\"" + Util.encodeURL("http://www.example.com/\"quotation\"/else") + "\""));
        assertTrue(Util.linkify("https://example.com/><\"")
                .contains("href=\"" + Util.encodeURL("https://example.com/><\"") + "\""));
        assertTrue(Util.linkify("http://www.example.com?param=1&param2=2&param3=\"quoted>\"")
                .contains("href=\"" + Util.encodeURL("http://www.example.com?param=1&param2=2&param3=\"quoted>\"") + "\""));
        // escaping titles
        assertTrue(Util.linkify("http://www.example.com/\"quotation\"/else")
                .contains("title=\"Link to " + Util.encode("http://www.example.com/\"quotation\"/else") + "\""));
        assertTrue(Util.linkify("https://example.com/><\"")
                .contains("title=\"Link to " + Util.encode("https://example.com/><\"") + "\""));
        assertTrue(Util.linkify("http://www.example.com?param=1&param2=2&param3=\"quoted>\"")
                .contains("title=\"Link to " + Util.encode("http://www.example.com?param=1&param2=2&param3=\"quoted>\"") + "\""));
    }

    @Test
    public void testBuildLink() throws URISyntaxException, MalformedURLException {
        assertEquals("<a href=\"http://www.example.com\">link</a>", Util.buildLink("link", "http://www.example.com"));
        assertEquals("<a href=\"http://www.example.com?url=xasw&beta=gama\">link</a>",
                Util.buildLink("link", "http://www.example.com?url=xasw&beta=gama"));

        String link = Util.buildLink("link", "http://www.example.com", true);
        assertTrue(link.contains("href=\"http://www.example.com\""));
        assertTrue(link.contains("target=\"_blank\""));

        link = Util.buildLink("link", "http://www.example.com?url=xasw&beta=gama", true);
        assertTrue(link.contains("href=\"http://www.example.com?url=xasw&beta=gama\""));
        assertTrue(link.contains("target=\"_blank\""));

        Map<String, String> attrs = new TreeMap<>();
        attrs.put("href", "https://www.example.com/abcd/acbd");
        attrs.put("title", "Some important title");
        attrs.put("data-id", "123456");

        link = Util.buildLink("link", attrs);
        assertTrue(link.contains("href=\"https://www.example.com/abcd/acbd\""));
        assertTrue(link.contains("title=\"Some important title\""));
        assertTrue(link.contains("data-id=\"123456\""));
    }

    @Test(expected = MalformedURLException.class)
    public void testBuildLinkInvalidUrl1() throws URISyntaxException, MalformedURLException {
        Util.buildLink("link", "www.example.com"); // invalid protocol
    }

    @Test(expected = URISyntaxException.class)
    public void testBuildLinkInvalidUrl2() throws URISyntaxException, MalformedURLException {
        Util.buildLink("link", "http://www.exa\"mp\"le.com"); // invalid authority
    }

    @Test
    public void testLinkifyPattern() {
        String text
                = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, "
                + "sed do eiusmod tempor incididunt as per 12345698 ut labore et dolore magna "
                + "aliqua. bug3333fff Ut enim ad minim veniam, quis nostrud exercitation "
                + "ullamco laboris nisi ut aliquip ex ea introduced in 9791216541 commodo consequat. "
                + "Duis aute irure dolor in reprehenderit in voluptate velit "
                + "esse cillum dolore eu fixes 132469187 fugiat nulla pariatur. Excepteur sint "
                + "occaecat bug6478abc cupidatat non proident, sunt in culpa qui officia "
                + "deserunt mollit anim id est laborum.";
        String expected
                = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, "
                + "sed do eiusmod tempor incididunt as per "
                + "<a href=\"http://www.example.com?bug=12345698\" target=\"_blank\">12345698</a> ut labore et dolore magna "
                + "aliqua. bug3333fff Ut enim ad minim veniam, quis nostrud exercitation "
                + "ullamco laboris nisi ut aliquip ex ea introduced in "
                + "<a href=\"http://www.example.com?bug=9791216541\" target=\"_blank\">9791216541</a> commodo consequat. "
                + "Duis aute irure dolor in reprehenderit in voluptate velit "
                + "esse cillum dolore eu fixes "
                + "<a href=\"http://www.example.com?bug=132469187\" target=\"_blank\">132469187</a> fugiat nulla pariatur. Excepteur sint "
                + "occaecat bug6478abc cupidatat non proident, sunt in culpa qui officia "
                + "deserunt mollit anim id est laborum.";
        String expected2
                = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, "
                + "sed do eiusmod tempor incididunt as per 12345698 ut labore et dolore magna "
                + "aliqua. "
                + "<a href=\"http://www.other-example.com?bug=3333\" target=\"_blank\">bug3333fff</a>"
                + " Ut enim ad minim veniam, quis nostrud exercitation "
                + "ullamco laboris nisi ut aliquip ex ea introduced in 9791216541 commodo consequat. "
                + "Duis aute irure dolor in reprehenderit in voluptate velit "
                + "esse cillum dolore eu fixes 132469187 fugiat nulla pariatur. Excepteur sint "
                + "occaecat "
                + "<a href=\"http://www.other-example.com?bug=6478\" target=\"_blank\">bug6478abc</a>"
                + " cupidatat non proident, sunt in culpa qui officia "
                + "deserunt mollit anim id est laborum.";

        assertEquals(expected, Util.linkifyPattern(text, Pattern.compile("\\b([0-9]{8,})\\b"), "$1", "http://www.example.com?bug=$1"));
        assertEquals(expected2, Util.linkifyPattern(text, Pattern.compile("\\b(bug([0-9]{4})\\w{3})\\b"), "$1",
                "http://www.other-example.com?bug=$2"));
    }

    @Test
    public void testCompleteUrl() {
        HttpServletRequest req = new DummyHttpServletRequest() {
            @Override
            public int getServerPort() {
                return 8080;
            }

            @Override
            public String getServerName() {
                return "www.example.com";
            }

            @Override
            public String getScheme() {
                return "http";
            }

            @Override
            public StringBuffer getRequestURL() {
                return new StringBuffer(getScheme() + "://" + getServerName() + ':' + getServerPort() + "/source/location/undefined");
            }
        };

        /**
         * Absolute including hostname.
         */
        assertEquals("http://opengrok.com/user=", Util.completeUrl("http://opengrok.com/user=", req));
        assertEquals("http://opengrok.cz.grok.com/user=", Util.completeUrl("http://opengrok.cz.grok.com/user=", req));
        assertEquals("http://opengrok.com/user=123&id=", Util.completeUrl("http://opengrok.com/user=123&id=", req));

        /**
         * Absolute/relative without the hostname.
         */
        assertEquals("http://www.example.com:8080/cgi-bin/user=", Util.completeUrl("/cgi-bin/user=", req));
        assertEquals("http://www.example.com:8080/cgi-bin/user=123&id=", Util.completeUrl("/cgi-bin/user=123&id=", req));
        assertEquals("http://www.example.com:8080/source/location/undefined/cgi-bin/user=", Util.completeUrl("cgi-bin/user=", req));
        assertEquals("http://www.example.com:8080/source/location/undefined/cgi-bin/user=123&id=", Util.completeUrl("cgi-bin/user=123&id=", req));

        assertEquals("http://www.example.com:8080/source/location/undefined", Util.completeUrl("", req));
        /**
         * Escaping should work.
         */
        assertEquals("http://www.example.com:8080/cgi-%22bin/user=123&id=", Util.completeUrl("/cgi-\"bin/user=123&id=", req));
    }

    @Test(expected = IllegalArgumentException.class)
    public void getQueryParamsNullTest() {
        Util.getQueryParams(null);
    }

    @Test
    public void getQueryParamsEmptyTest() throws MalformedURLException {
        URL url = new URL("http://test.com/test");
        assertTrue(Util.getQueryParams(url).isEmpty());
    }

    @Test
    public void getQueryParamsEmptyTest2() throws MalformedURLException {
        URL url = new URL("http://test.com/test?");
        assertTrue(Util.getQueryParams(url).isEmpty());
    }

    @Test
    public void getQueryParamsSingleTest() throws MalformedURLException {
        URL url = new URL("http://test.com?param1=value1");
        Map<String, List<String>> params = Util.getQueryParams(url);

        assertEquals(1, params.size());

        assertThat(params.get("param1"), contains("value1"));
    }

    @Test
    public void getQueryParamsMultipleTest() throws MalformedURLException {
        URL url = new URL("http://test.com?param1=value1&param2=value2");
        Map<String, List<String>> params = Util.getQueryParams(url);

        assertEquals(2, params.size());

        assertThat(params.get("param1"), contains("value1"));
        assertThat(params.get("param2"), contains("value2"));
    }

    @Test
    public void getQueryParamsMultipleSameTest() throws MalformedURLException {
        URL url = new URL("http://test.com?param1=value1&param1=value2");
        Map<String, List<String>> params = Util.getQueryParams(url);

        assertEquals(1, params.size());

        assertThat(params.get("param1"), contains("value1", "value2"));
    }

    @Test
    public void getQueryParamsEncodedTest() throws MalformedURLException {
        URL url = new URL("http://test.com?param1=%3Fvalue%3F");
        Map<String, List<String>> params = Util.getQueryParams(url);

        assertEquals(1, params.size());

        assertThat(params.get("param1"), contains("?value?"));
    }

    @Test
    public void getQueryParamsEmptyValueTest() throws MalformedURLException {
        URL url = new URL("http://test.com?param1=");

        Map<String, List<String>> params = Util.getQueryParams(url);

        assertThat(params.get("param1"), contains(""));
    }

    @Test
    public void getQueryParamsEmptyAndNormalValuesCombinedTest() throws MalformedURLException {
        URL url = new URL("http://test.com?param1=value1&param2=&param3&param4=value4");

        Map<String, List<String>> params = Util.getQueryParams(url);

        assertThat(params.get("param1"), contains("value1"));
        assertThat(params.get("param2"), contains(""));
        assertTrue(params.containsKey("param3"));
        assertThat(params.get("param4"), contains("value4"));
    }

}
