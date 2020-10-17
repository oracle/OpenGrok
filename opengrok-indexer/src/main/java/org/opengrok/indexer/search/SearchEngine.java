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
 * Copyright (c) 2005, 2020, Oracle and/or its affiliates. All rights reserved.
 * Portions Copyright (c) 2018-2019, Chris Fraire <cfraire@me.com>.
 */
package org.opengrok.indexer.search;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import org.apache.lucene.analysis.charfilter.HTMLStripCharFilter;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.opengrok.indexer.analysis.AbstractAnalyzer;
import org.opengrok.indexer.analysis.CompatibleAnalyser;
import org.opengrok.indexer.analysis.Definitions;
import org.opengrok.indexer.analysis.Scopes;
import org.opengrok.indexer.configuration.Project;
import org.opengrok.indexer.configuration.RuntimeEnvironment;
import org.opengrok.indexer.configuration.SuperIndexSearcher;
import org.opengrok.indexer.history.HistoryException;
import org.opengrok.indexer.index.IndexDatabase;
import org.opengrok.indexer.logger.LoggerFactory;
import org.opengrok.indexer.search.Summary.Fragment;
import org.opengrok.indexer.search.context.Context;
import org.opengrok.indexer.search.context.HistoryContext;
import org.opengrok.indexer.util.TandemPath;
import org.opengrok.indexer.web.Prefix;

/**
 * This is an encapsulation of the details on how to search in the index database.
 * This is used for searching via the REST API.
 *
 * @author Trond Norbye 2005
 * @author Lubos Kosco - upgrade to lucene 3.x, 4.x, 5.x
 */
public class SearchEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(SearchEngine.class);

    /**
     * Message text used when logging exceptions thrown when searching.
     */
    private static final String SEARCH_EXCEPTION_MSG = "Exception searching {0}";
    //NOTE below will need to be changed after new lucene upgrade, if they
    //increase the version - every change of below makes us incompatible with the
    //old index and we need to ask for reindex
    /**
     * Version of Lucene index common for the whole application.
     */
    public static final Version LUCENE_VERSION = Version.LATEST;
    public static final String LUCENE_VERSION_HELP = LUCENE_VERSION.major + "_" + LUCENE_VERSION.minor + "_" + LUCENE_VERSION.bugfix;

    private final RuntimeEnvironment env;

    /**
     * Holds value of property definition.
     */
    private String definition;
    /**
     * Holds value of property file.
     */
    private String file;
    /**
     * Holds value of property freetext.
     */
    private String freetext;
    /**
     * Holds value of property history.
     */
    private String history;
    /**
     * Holds value of property symbol.
     */
    private String symbol;
    /**
     * Holds value of property type.
     */
    private String type;
    /**
     * Holds value of property indexDatabase.
     */
    private Query query;
    private QueryBuilder queryBuilder;
    private final CompatibleAnalyser analyzer = new CompatibleAnalyser();
    private Context sourceContext;
    private HistoryContext historyContext;
    private Summarizer summarizer;
    // internal structure to hold the results from lucene
    private final List<Document> docs;
    private final char[] content = new char[1024 * 8];
    private String source;
    private String data;
    private int hitsPerPage;
    private int cachePages;
    private int totalHits;
    private ScoreDoc[] hits;
    private TopScoreDocCollector collector;
    private IndexReader reader;
    private SettingsHelper settingsHelper;
    private IndexSearcher searcher;
    private boolean allCollected;
    private final ArrayList<SuperIndexSearcher> searcherList = new ArrayList<>();

    /**
     * Creates a new instance of SearchEngine.
     */
    public SearchEngine() {
        env = RuntimeEnvironment.getInstance();
        docs = new ArrayList<>();
        hitsPerPage = env.getHitsPerPage();
        cachePages = env.getCachePages();
    }

    /**
     * Create a QueryBuilder using the fields that have been set on this
     * SearchEngine.
     *
     * @return a query builder
     */
    private QueryBuilder createQueryBuilder() {
        return new QueryBuilder()
                .setFreetext(freetext)
                .setDefs(definition)
                .setRefs(symbol)
                .setPath(file)
                .setHist(history)
                .setType(type);
    }

    public boolean isValidQuery() {
        boolean ret;
        try {
            query = createQueryBuilder().build();
            ret = (query != null);
        } catch (ParseException e) {
            ret = false;
        }

        return ret;
    }

    /**
     * Search one index. This is used if no projects are set up.
     * @param paging whether to use paging (if yes, first X pages will load
     * faster)
     * @param root which db to search
     */
    private void searchSingleDatabase(File root, boolean paging) throws IOException {
        reader = DirectoryReader.open(FSDirectory.open(root.toPath()));
        settingsHelper = new SettingsHelper(reader);
        searcher = new IndexSearcher(reader);
        searchReader(paging);
    }

    /**
     * Perform search on multiple indexes in parallel.
     * @param paging whether to use paging (if yes, first X pages will load
     * faster)
     * @param root list of projects to search
     */
    private void searchMultiDatabase(List<Project> root, boolean paging) throws IOException {
        SortedSet<String> projects = new TreeSet<>();
        for (Project p : root) {
            projects.add(p.getName());
        }

        // We use MultiReader even for single project. This should
        // not matter given that MultiReader is just a cheap wrapper
        // around set of IndexReader objects.
        MultiReader searchables = env.getMultiReader(projects, searcherList);
        reader = searchables;
        settingsHelper = new SettingsHelper(reader);
        searcher = new IndexSearcher(searchables);
        searchReader(paging);
    }

    private void searchReader(boolean paging) throws IOException {
        collector = TopScoreDocCollector.create(hitsPerPage * cachePages, Short.MAX_VALUE);
        searcher.search(query, collector);
        totalHits = collector.getTotalHits();
        if (!paging && totalHits > 0) {
            collector = TopScoreDocCollector.create(totalHits, Short.MAX_VALUE);
            searcher.search(query, collector);
        }
        hits = collector.topDocs().scoreDocs;
        for (ScoreDoc hit : hits) {
            int docId = hit.doc;
            Document d = searcher.doc(docId);
            docs.add(d);
        }
    }

    /**
     * Gets the instance from {@code search(...)} if it was called.
     * @return defined instance or {@code null}
     */
    public String getQuery() {
        return query != null ? query.toString() : null;
    }

    /**
     * Gets the instance from {@code search(...)} if it was called.
     * @return defined instance or {@code null}
     */
    public Query getQueryObject() {
        return query;
    }

    /**
     * Gets the builder from {@code search(...)} if it was called.
     * <p>
     * (Modifying the builder will have no effect on this
     * {@link SearchEngine}.)
     * @return defined instance or {@code null}
     */
    public QueryBuilder getQueryBuilder() {
        return queryBuilder;
    }

    /**
     * Gets the searcher from {@code search(...)} if it was called.
     * @return defined instance or {@code null}
     */
    public IndexSearcher getSearcher() {
        return searcher;
    }

    /**
     * Execute a search aware of current request, limited to specific project names.
     *
     * This filters out all projects which are not allowed for the current request.
     *
     * Before calling this function,
     * you must set the appropriate search criteria with the set-functions. Note
     * that this search will return the first cachePages of hitsPerPage, for
     * more you need to call more.
     *
     * Call to search() must be eventually followed by call to destroy()
     * so that IndexSearcher objects are properly freed.
     *
     * @param projects projects to search
     * @return The number of hits
     */
    public int search(List<Project> projects) {
        return search(projects, new File(env.getDataRootFile(), IndexDatabase.INDEX_DIR));
    }

    /**
     * Execute a search without authorization.
     *
     * Before calling this function, you must set the
     * appropriate search criteria with the set-functions. Note that this search
     * will return the first cachePages of hitsPerPage, for more you need to
     * call more.
     *
     * Call to search() must be eventually followed by call to destroy()
     * so that IndexSearcher objects are properly freed.
     *
     * @return The number of hits
     */
    public int search() {
        return search(
                env.hasProjects() ? env.getProjectList() : new ArrayList<>(),
                new File(env.getDataRootFile(), IndexDatabase.INDEX_DIR));
    }

    /**
     * Execute a search on projects or root file.
     *
     * If @param projects is an empty list it tries to search in @code
     * searchSingleDatabase with root set to @param root
     *
     * Call to search() must be eventually followed by call to destroy()
     * so that IndexSearcher objects are properly freed.
     *
     * @return The number of hits
     */
    private int search(List<Project> projects, File root) {
        source = env.getSourceRootPath();
        data = env.getDataRootPath();
        docs.clear();

        QueryBuilder newBuilder = createQueryBuilder();
        try {
            query = newBuilder.build();
            if (query != null) {

                if (projects.isEmpty()) {
                    // search the index database
                    //NOTE this assumes that src does not contain any project, just
                    // data files - so no authorization can be enforced
                    searchSingleDatabase(root, true);
                } else {
                    // search all projects
                    //TODO support paging per project (in search.java)
                    //TODO optimize if only one project by falling back to SingleDatabase ?
                    //NOTE projects are already filtered if we accessed through web page @see search(HttpServletRequest)
                    searchMultiDatabase(projects, false);
                }
            }
        } catch (Exception e) {
            LOGGER.log(
                    Level.WARNING, SEARCH_EXCEPTION_MSG, e);
        }

        if (!docs.isEmpty()) {
            sourceContext = null;
            summarizer = null;
            try {
                sourceContext = new Context(query, newBuilder);
                if (sourceContext.isEmpty()) {
                    sourceContext = null;
                }
                summarizer = new Summarizer(query, analyzer);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "An error occurred while creating summary", e);
            }

            historyContext = null;
            try {
                historyContext = new HistoryContext(query);
                if (historyContext.isEmpty()) {
                    historyContext = null;
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "An error occurred while getting history context", e);
            }
        }
        int count = hits == null ? 0 : hits.length;
        queryBuilder = newBuilder;
        return count;
    }

    /**
     * Gets the queried score docs from {@code search(...)} if it was called.
     * @return a defined instance if a query succeeded, or {@code null}
     */
    public ScoreDoc[] scoreDocs() {
        return hits;
    }

    /**
     * Gets the document of the specified {@code docId} from
     * {@code search(...)} if it was called.
     *
     * @param docId document ID
     * @return a defined instance if a query succeeded
     * @throws java.io.IOException if an error occurs obtaining the Lucene
     * document by ID
     */
    public Document doc(int docId) throws IOException {
        if (searcher == null) {
            throw new IllegalStateException("search(...) did not succeed");
        }
        return searcher.doc(docId);
    }

    /**
     * Get results , if no search was started before, no results are returned.
     * This method will requery if {@code end} is more than first query from search,
     * hence performance hit applies, if you want results in later pages than
     * number of cachePages. {@code end} has to be bigger than {@code start} !
     *
     * @param start start of the hit list
     * @param end end of the hit list
     * @param ret list of results from start to end or null/empty if no search
     * was started
     */
    public void results(int start, int end, List<Hit> ret) {

        //return if no start search() was done
        if (hits == null || (end < start)) {
            ret.clear();
            return;
        }

        ret.clear();

        // TODO check if below fits for if end=old hits.length, or it should include it
        if (end > hits.length && !allCollected) {
            //do the requery, we want more than 5 pages
            collector = TopScoreDocCollector.create(totalHits, Short.MAX_VALUE);
            try {
                searcher.search(query, collector);
            } catch (Exception e) { // this exception should never be hit, since search() will hit this before
                LOGGER.log(
                        Level.WARNING, SEARCH_EXCEPTION_MSG, e);
            }
            hits = collector.topDocs().scoreDocs;
            for (int i = start; i < hits.length; i++) {
                int docId = hits[i].doc;
                Document d = null;
                try {
                    d = searcher.doc(docId);
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, SEARCH_EXCEPTION_MSG, e);
                }
                docs.add(d);
            }
            allCollected = true;
        }

        //TODO generation of ret(results) could be cashed and consumers of engine would just print them in whatever form
        // form they need, this way we could get rid of docs
        // the only problem is that count of docs is usually smaller than number of results
        for (int i = start; i < end; ++i) {
            boolean alt = (i % 2 == 0);
            boolean hasContext = false;
            try {
                int docId = hits[i].doc;
                Document doc = docs.get(i);
                if (doc == null) {
                    continue;
                }

                String filename = doc.get(QueryBuilder.PATH);
                AbstractAnalyzer.Genre genre = AbstractAnalyzer.Genre.get(doc.get(QueryBuilder.T));

                if (sourceContext != null) {
                    sourceContext.toggleAlt();
                    Project proj = Project.getProject(filename);
                    try {
                        if (AbstractAnalyzer.Genre.PLAIN == genre && (source != null)) {
                            int tabSize = settingsHelper.getTabSize(proj);
                            List<Hit> contextHits = sourceContext.getHits(
                                    searcher, docId, tabSize, filename);
                            if (contextHits != null) {
                                hasContext = true;
                                ret.addAll(contextHits);
                            }
                        } else if (AbstractAnalyzer.Genre.XREFABLE == genre && data != null && summarizer != null) {
                            int l;
                            /*
                             * For backward compatibility, read the
                             * OpenGrok-produced document using the system
                             * default charset.
                             */
                            try (Reader r = env.isCompressXref()
                                    ? new HTMLStripCharFilter(new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(
                                            TandemPath.join(data + Prefix.XREF_P + filename, ".gz"))))))
                                    : new HTMLStripCharFilter(new BufferedReader(new FileReader(data + Prefix.XREF_P + filename)))) {
                                l = r.read(content);
                            }
                            //TODO FIX below fragmenter according to either summarizer or context
                            // (to get line numbers, might be hard, since xref writers will need to be fixed too,
                            // they generate just one line of html code now :( )
                            Summary sum = summarizer.getSummary(new String(content, 0, l));
                            Fragment[] fragments = sum.getFragments();
                            for (Fragment fragment : fragments) {
                                String match = fragment.toString();
                                if (match.length() > 0) {
                                    if (!fragment.isEllipsis()) {
                                        Hit hit = new Hit(filename, fragment.toString(), "", true, alt);
                                        ret.add(hit);
                                    }
                                    hasContext = true;
                                }
                            }
                        } else {
                            LOGGER.log(Level.WARNING, "Unknown genre: {0} for {1}", new Object[]{genre, filename});
                            hasContext = getFallbackContext(ret, doc, filename);
                        }
                    } catch (FileNotFoundException exp) {
                        LOGGER.log(Level.WARNING, "Couldn''t read summary from {0} ({1})", new Object[]{filename, exp.getMessage()});
                        hasContext = getFallbackContext(ret, doc, filename);
                    }
                }
                if (historyContext != null) {
                    hasContext |= historyContext.getContext(source + filename, filename, ret);
                }
                if (!hasContext) {
                    ret.add(new Hit(filename, "...", "", false, alt));
                }
            } catch (IOException | ClassNotFoundException | HistoryException e) {
                LOGGER.log(
                        Level.WARNING, SEARCH_EXCEPTION_MSG, e);
            }
        }
    }

    public void destroy() {
        for (SuperIndexSearcher is : searcherList) {
            try {
                is.getSearcherManager().release(is);
            } catch (IOException ex) {
                LOGGER.log(Level.WARNING, "cannot release indexSearcher", ex);
            }
        }
    }

    /**
     * Getter for property definition.
     *
     * @return Value of property definition.
     */
    public String getDefinition() {
        return this.definition;
    }

    /**
     * Setter for property definition.
     *
     * @param definition New value of property definition.
     */
    public void setDefinition(String definition) {
        this.definition = definition;
    }

    /**
     * Getter for property file.
     *
     * @return Value of property file.
     */
    public String getFile() {
        return this.file;
    }

    /**
     * Setter for property file.
     *
     * @param file New value of property file.
     */
    public void setFile(String file) {
        this.file = file;
    }

    /**
     * Getter for property freetext.
     *
     * @return Value of property freetext.
     */
    public String getFreetext() {
        return this.freetext;
    }

    /**
     * Setter for property freetext.
     *
     * @param freetext New value of property freetext.
     */
    public void setFreetext(String freetext) {
        this.freetext = freetext;
    }

    /**
     * Getter for property history.
     *
     * @return Value of property history.
     */
    public String getHistory() {
        return this.history;
    }

    /**
     * Setter for property history.
     *
     * @param history New value of property history.
     */
    public void setHistory(String history) {
        this.history = history;
    }

    /**
     * Getter for property symbol.
     *
     * @return Value of property symbol.
     */
    public String getSymbol() {
        return this.symbol;
    }

    /**
     * Setter for property symbol.
     *
     * @param symbol New value of property symbol.
     */
    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    /**
     * Getter for property type.
     *
     * @return Value of property type.
     */
    public String getType() {
        return this.type;
    }

    /**
     * Setter for property type.
     *
     * @param fileType New value of property type.
     */
    public void setType(String fileType) {
        this.type = fileType;
    }

    private boolean getFallbackContext(List<Hit> ret, Document doc, String filename)
            throws ClassNotFoundException, IOException {
        Definitions tags = null;
        IndexableField tagsField = doc.getField(QueryBuilder.TAGS);
        if (tagsField != null) {
            tags = Definitions.deserialize(tagsField.binaryValue().bytes);
        }

        Scopes scopes = null;
        IndexableField scopesField = doc.getField(QueryBuilder.SCOPES);
        if (scopesField != null) {
            scopes = Scopes.deserialize(scopesField.binaryValue().bytes);
        }

        return sourceContext.getContext(null, null, null, null, filename, tags,
                false, false, ret, scopes);
    }
}
