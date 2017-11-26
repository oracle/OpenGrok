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
 * Copyright (c) 2006, 2017, Oracle and/or its affiliates. All rights reserved.
 * Portions Copyright (c) 2017, Chris Fraire <cfraire@me.com>.
 */

/*
 * Cross reference a JavaScript file
 */

package org.opensolaris.opengrok.analysis.javascript;

import org.opensolaris.opengrok.analysis.JFlexXrefSimple;
import org.opensolaris.opengrok.util.StringUtils;
import org.opensolaris.opengrok.web.HtmlConsts;
import org.opensolaris.opengrok.web.Util;
%%
%public
%class JavaScriptXref
%extends JFlexXrefSimple
%unicode
%int
%include CommonLexer.lexh

File = [a-zA-Z]{FNameChar}* "." ([Jj][Ss] |
    [Pp][Rr][Oo][Pp][Ee][Rr][Tt][Ii][Ee][Ss] | [Pp][Rr][Oo][Pp][Ss] |
    [Xx][Mm][Ll] | [Cc][Oo][Nn][Ff] | [Tt][Xx][Tt] | [Hh][Tt][Mm][Ll]? |
    [Ii][Nn][Ii] | [Dd][Ii][Ff][Ff] | [Pp][Aa][Tt][Cc][Hh])

%state  STRING COMMENT SCOMMENT QSTRING

%include Common.lexh
%include CommonURI.lexh
%include CommonPath.lexh
%include JavaScript.lexh
%%
<YYINITIAL>{

{Identifier} {
    String id = yytext();
    writeSymbol(id, Consts.kwd, yyline);
}

"<" ({File}|{FPath}) ">" {
        out.write("&lt;");
        String path = yytext();
        path = path.substring(1, path.length() - 1);
        out.write("<a href=\""+urlPrefix+"path=");
        out.write(path);
        appendProject();
        out.write("\">");
        out.write(path);
        out.write("</a>");
        out.write("&gt;");
}

 {Number}    {
    disjointSpan(HtmlConsts.NUMBER_CLASS);
    out.write(yytext());
    disjointSpan(null);
 }

 \"     {
    pushSpan(STRING, HtmlConsts.STRING_CLASS);
    out.write(htmlize(yytext()));
 }
 \'     {
    pushSpan(QSTRING, HtmlConsts.STRING_CLASS);
    out.write(htmlize(yytext()));
 }
 "/*"   {
    pushSpan(COMMENT, HtmlConsts.COMMENT_CLASS);
    out.write(yytext());
 }
 "//"   {
    pushSpan(SCOMMENT, HtmlConsts.COMMENT_CLASS);
    out.write(yytext());
 }
}

<STRING> {
 \\[\"\\] |
 \" {WhiteSpace} \"    { out.write(htmlize(yytext())); }
 \"     {
    out.write(htmlize(yytext()));
    yypop();
 }
}

<QSTRING> {
 \\[\'\\] |
 \' {WhiteSpace} \'    { out.write(htmlize(yytext())); }
 \'     {
    out.write(htmlize(yytext()));
    yypop();
 }
}

<COMMENT> {
"*/"    { out.write(yytext()); yypop(); }
}

<SCOMMENT> {
  {WhspChar}*{EOL}    {
    yypop();
    startNewLine();
  }
}

<YYINITIAL, STRING, COMMENT, SCOMMENT, QSTRING> {
[&<>\'\"]    { out.write(htmlize(yytext())); }
{WhspChar}*{EOL}      { startNewLine(); }
 {WhiteSpace}   { out.write(yytext()); }
 [!-~]  { out.write(yycharat(0)); }
 [^\n]      { writeUnicodeChar(yycharat(0)); }
}

<STRING, COMMENT, SCOMMENT, QSTRING> {
{FPath}
        { out.write(Util.breadcrumbPath(urlPrefix+"path=",yytext(),'/'));}

{File}
        {
        String path = yytext();
        out.write("<a href=\""+urlPrefix+"path=");
        out.write(path);
        appendProject();
        out.write("\">");
        out.write(path);
        out.write("</a>");}

{FNameChar}+ "@" {FNameChar}+ "." {FNameChar}+
        {
          writeEMailAddress(yytext());
        }
}

<STRING, SCOMMENT> {
    {BrowseableURI}    {
        appendLink(yytext(), true);
    }
}

<COMMENT> {
    {BrowseableURI}    {
        appendLink(yytext(), true, StringUtils.END_C_COMMENT);
    }
}

<QSTRING> {
    {BrowseableURI}    {
        appendLink(yytext(), true, StringUtils.APOS_NO_BSESC);
    }
}
