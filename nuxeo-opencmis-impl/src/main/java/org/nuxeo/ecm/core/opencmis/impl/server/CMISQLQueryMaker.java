/*
 * (C) Copyright 2008-2010 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Florent Guillaume
 */

package org.nuxeo.ecm.core.opencmis.impl.server;

import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.antlr.runtime.tree.Tree;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinitionContainer;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.server.support.query.AbstractQueryConditionProcessor;
import org.apache.chemistry.opencmis.server.support.query.CmisQueryWalker;
import org.apache.chemistry.opencmis.server.support.query.CmisSelector;
import org.apache.chemistry.opencmis.server.support.query.ColumnReference;
import org.apache.chemistry.opencmis.server.support.query.FunctionReference;
import org.apache.chemistry.opencmis.server.support.query.FunctionReference.CmisQlFunction;
import org.apache.chemistry.opencmis.server.support.query.QueryObject;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.common.utils.StringUtils;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.LifeCycleConstants;
import org.nuxeo.ecm.core.opencmis.impl.server.CMISQLQueryMaker.NamedColumn;
import org.nuxeo.ecm.core.opencmis.impl.util.TypeManagerImpl;
import org.nuxeo.ecm.core.query.QueryFilter;
import org.nuxeo.ecm.core.schema.FacetNames;
import org.nuxeo.ecm.core.schema.TypeConstants;
import org.nuxeo.ecm.core.storage.StorageException;
import org.nuxeo.ecm.core.storage.sql.Model;
import org.nuxeo.ecm.core.storage.sql.ModelProperty;
import org.nuxeo.ecm.core.storage.sql.Session.PathResolver;
import org.nuxeo.ecm.core.storage.sql.jdbc.QueryMaker;
import org.nuxeo.ecm.core.storage.sql.jdbc.SQLInfo;
import org.nuxeo.ecm.core.storage.sql.jdbc.SQLInfo.MapMaker;
import org.nuxeo.ecm.core.storage.sql.jdbc.SQLInfo.SQLInfoSelect;
import org.nuxeo.ecm.core.storage.sql.jdbc.db.Column;
import org.nuxeo.ecm.core.storage.sql.jdbc.db.Database;
import org.nuxeo.ecm.core.storage.sql.jdbc.db.Select;
import org.nuxeo.ecm.core.storage.sql.jdbc.db.Table;
import org.nuxeo.ecm.core.storage.sql.jdbc.db.TableAlias;
import org.nuxeo.ecm.core.storage.sql.jdbc.dialect.Dialect;
import org.nuxeo.ecm.core.storage.sql.jdbc.dialect.Dialect.FulltextMatchInfo;

/**
 * Transformer of CMISQL queries into real SQL queries for the actual database.
 */
public class CMISQLQueryMaker extends AbstractQueryConditionProcessor implements
        QueryMaker {

    private static final Log log = LogFactory.getLog(CMISQLQueryMaker.class);

    public static final String TYPE = "CMISQL";

    private static final String CMIS_PREFIX = "cmis:";

    public static final String DC_FRAGMENT_NAME = "dublincore";

    public static final String DC_CREATOR_KEY = "creator";

    public static final String DC_CREATED_KEY = "created";

    public static final String DC_MODIFIED_KEY = "modified";

    protected Database database;

    protected Dialect dialect;

    protected Model model;

    protected Table hierTable;

    /** propertyInfos uppercased names mapped to normal name. */
    protected Map<String, String> propertyInfoNames;

    /** propertyInfoNames + specialPropNames */
    protected Map<String, String> allPropNames;

    // ----- filled during the tree walk -----

    /** columns referenced, keyed by full quoted name (includes alias name) */
    public Map<String, Column> columns = new HashMap<String, Column>();

    /** qualifier to set of columns full quoted names, used to identify joins */
    public Map<String, Set<String>> columnsPerQual = new LinkedHashMap<String, Set<String>>();

    /** column aliases useable in ORDER BY */
    public final Map<String, SelectedColumn> columnAliases = new HashMap<String, SelectedColumn>();

    /** joins added by fulltext match */
    public final List<org.nuxeo.ecm.core.storage.sql.jdbc.db.Join> ftJoins = new LinkedList<org.nuxeo.ecm.core.storage.sql.jdbc.db.Join>();

    public List<String> errorMessages = new LinkedList<String>();

    public boolean hasScoreInSelect;

    public boolean hasContains;

    public FulltextMatchInfo fulltextMatchInfo;

    protected static class Join {
        /** INNER / LEFT / RIGHT */
        String kind;

        /** Table name. */
        String table;

        /** Correlation name (qualifier), or null. */
        String corr;

        String on1;

        String on2;
    }

    @Override
    public String getName() {
        return TYPE;
    }

    @Override
    public boolean accepts(String queryType) {
        return queryType.equals(TYPE);
    }

    /**
     * A selected column can be either an explicit (direct) column, an
     * expression computed in SQL (SCORE), a virtual column computed in Java
     * after fetch, or a special keyword (STAR).
     */
    public static abstract class SelectedColumn {
        /** The column alias. */
        public String alias;
    }

    /**
     * Column for the * of a SELECT *, can be used with a qualifier. Will be
     * expanded into the actual columns needed during preprocessing.
     */
    public static class StarColumn extends SelectedColumn {
        public final String qual;

        public StarColumn(String qual) {
            this.qual = qual;
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + '('
                    + (qual == null ? "" : qual) + ')';
        }
    }

    /**
     * Column for SCORE().
     */
    public static class ScoreColumn extends SelectedColumn {
        public ScoreColumn() {
            alias = "SEARCH_SCORE"; // default, from spec
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + '('
                    + (alias == null ? "" : alias) + ')';
        }
    }

    /**
     * Column that has CMIS property name.
     */
    public static abstract class NamedColumn extends SelectedColumn {

        public final String name;

        public final String qual;

        public NamedColumn(String name, String qual) {
            this.name = name;
            this.qual = qual;
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + '('
                    + (qual == null ? name : qual + '.' + name)
                    + (alias == null ? "" : " AS " + alias) + ')';
        }

        protected String identity() {
            return qual + "." + name;
        }

        @Override
        public int hashCode() {
            return identity().hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (!(obj instanceof NamedColumn)) {
                return false;
            }
            return identity().equals(((NamedColumn) obj).identity());
        }
    }

    /**
     * Column backed by an actual dialect column. Only those can appear in a
     * WHERE clause.
     */
    public static class DirectColumn extends NamedColumn {
        /**
         * Column used to get the value from the result set.
         */
        public final Column column;

        public DirectColumn(String name, String qual, Column column) {
            super(name, qual);
            this.column = column;
        }
    }

    /**
     * Column computed from a property expression in Java after fetch.
     */
    public static class VirtualColumn extends NamedColumn {
        public VirtualColumn(String name, String qual) {
            super(name, qual);
        }
    }

    private void computeAllPropNames(TypeManagerImpl typeManager) {
        propertyInfoNames = new HashMap<String, String>();
        for (String name : model.getPropertyInfoNames()) {
            propertyInfoNames.put(name.toUpperCase(), name);
        }
        allPropNames = new HashMap<String, String>(propertyInfoNames);
        for (TypeDefinitionContainer tc : typeManager.getTypeDescendants(null,
                -1, Boolean.TRUE)) {
            computeAllPropNames(tc);
        }
    }

    private void computeAllPropNames(TypeDefinitionContainer tc) {
        for (PropertyDefinition<?> pd : tc.getTypeDefinition().getPropertyDefinitions().values()) {
            String name = pd.getId();
            allPropNames.put(name.toUpperCase(), name);
        }
        List<TypeDefinitionContainer> types = tc.getChildren();
        if (types != null) {
            for (TypeDefinitionContainer type : types) {
                computeAllPropNames(type);
            }
        }
    }

    @Override
    public Query buildQuery(SQLInfo sqlInfo, Model model,
            PathResolver pathResolver, String statement,
            QueryFilter queryFilter, Object... params) throws StorageException {
        NuxeoCmisService service = (NuxeoCmisService) params[0];
        TypeManagerImpl typeManager = service.repository.getTypeManager();

        boolean addSystemColumns = params.length >= 2
                && Boolean.TRUE.equals(params[1]);
        database = sqlInfo.database;
        dialect = sqlInfo.dialect;
        this.model = model;

        // TODO precompute this only once
        computeAllPropNames(typeManager);

        hierTable = database.getTable(model.HIER_TABLE_NAME);

        QueryObject query = new QueryObject(typeManager, this);
        CmisQueryWalker walker;
        try {
            walker = AbstractQueryConditionProcessor.getWalker(statement);
            walker.query(query);
        } catch (Exception e) {
            throw new QueryMakerException("Cannot parse query: "
                    + e.getMessage(), e);
        }
        String err = walker.getErrorMessageString();
        if (err != null) {
            throw new QueryMakerException("Cannot parse query: " + err);
        }

        boolean distinct = false; // walker.select_distinct;

        // whether to ignore hidden and trashed documents
        boolean skipHidden = true; // let system user see them?

        /** The columns we'll return. */
        List<SelectedColumn> columnsWhat = new ArrayList<SelectedColumn>();

        // prepare data from the QueryObject

        Map<String, String> froms = query.getTypes();
        if (froms.size() > 1) {
            throw new UnsupportedOperationException("JOIN not yet supported");
        }
        Join jj = new Join();
        Entry<String, String> ee = froms.entrySet().iterator().next();
        jj.table = ee.getValue();
        jj.corr = ee.getKey();
        if (jj.table.equals(jj.corr)) {
            jj.corr = null;
        }
        List<Join> walker_from_joins = Collections.singletonList(jj);

        /*
         * Interpret * in SELECT now that tables are known.
         */

        VirtualColumn sampleVirtualColumn = null;

        // for (SelectedColumn sc : walker_select_what) {
        for (CmisSelector sel : query.getSelectReferences()) {
            if (sel instanceof FunctionReference) {
                FunctionReference fr = (FunctionReference) sel;
                if (fr.getFunction() != CmisQlFunction.SCORE) {
                    throw new CmisRuntimeException("Unknown function: "
                            + fr.getFunction());
                }
                columnsWhat.add(new ScoreColumn());
                continue;
            }
            ColumnReference col = (ColumnReference) sel;
            if (!"*".equals(col.getName())) {
                NamedColumn nc = recordColumnReference(col);
                columnsWhat.add(nc);
                if (nc instanceof VirtualColumn) {
                    sampleVirtualColumn = (VirtualColumn) nc;
                }
                continue;
            }

            // STAR

            String qual = col.getTypeQueryName();
            // find the joined table with this correlation qualifier and add all
            // its columns to the select
            for (Join j : walker_from_joins) {
                if (!sameString(qual, j.corr)) {
                    continue;
                }
                // Type type = conn.getRepository().getType(j.table);
                Object type = null;
                // TODO getTypeByQueryName
                if (type == null) {
                    // type =
                    // conn.getRepository().getType(j.table.toLowerCase());
                    if (type == null) {
                        throw new QueryMakerException("Unknown type: "
                                + j.table);
                    }
                }
                // for (PropertyDefinition pd : type.getPropertyDefinitions())
                for (Object pd : Collections.emptyList()) {
                    // if (pd.isMultiValued()) {
                    // continue;
                    // }
                    String pd_id = "";
                    try {
                        NamedColumn nc = referToColumnInSelect(pd_id, qual);
                        columnsWhat.add(nc);
                        if (nc instanceof VirtualColumn) {
                            sampleVirtualColumn = (VirtualColumn) nc;
                        }
                    } catch (QueryMakerException e) {
                        // ignore, non-mappable column
                    }
                }
            }
        }

        /*
         * Find info about fragments needed.
         */

        // find all columns mentioned for each type
        for (CmisSelector sel : query.getSelectReferences()) {
            recordCmisSelector(sel, false);
        }
        for (CmisSelector sel : query.getWhereReferences()) {
            recordCmisSelector(sel, true);
        }
        // TODO join
        // TODO order by

        // add main id to all qualifiers if
        // - we have no DISTINCT (in which case more columns don't matter), or
        // - we have virtual columns, or
        // - system columns are requested
        // check no added columns would bias the DISTINCT
        List<DirectColumn> addedSystemColumns = new ArrayList<DirectColumn>(0);

        for (String qual : allQualifiers) {
            // TODO do we need PropertyIds.OBJECT_TYPE_ID here?
            for (String propertyId : Arrays.asList(PropertyIds.OBJECT_ID)) {
                ColumnReference col = new ColumnReference(qual, propertyId);
                col.setTypeDefinition(propertyId, null);
                DirectColumn dc = recordCmisSelector(col, false);
                if (dc != null) {
                    addedSystemColumns.add(dc);
                }
            }
            if (skipHidden) {
                // add lifecycle state column
                Table table = getTable(
                        database.getTable(model.MISC_TABLE_NAME), qual);
                Column col = table.getColumn(model.MISC_LIFECYCLE_STATE_KEY);
                DirectColumn dc = new DirectColumn("ECM:LIFECYCLESTATE", qual,
                        col);
                recordDirectColumn(qual, dc);
            }
        }

        // additional columns to select on
        if (!distinct) {
            columnsWhat.addAll(addedSystemColumns);
        } else {
            if (!addedSystemColumns.isEmpty()) {
                if (sampleVirtualColumn != null) {
                    throw new QueryMakerException(
                            "Cannot use DISTINCT with virtual column "
                                    + getColumnName(sampleVirtualColumn));
                }
                if (addSystemColumns) {
                    throw new QueryMakerException(
                            "Cannot use DISTINCT without explicit "
                                    + PropertyIds.OBJECT_ID);
                }
                // don't add system columns as it would prevent DISTINCT from
                // working
            }
        }

        // per qualifier, include hier in fragments
        for (String qual : allQualifiers) {
            Table table = getTable(hierTable, qual);
            String fragment = table.getName();
            // Map<String, Map<String, DirectColumn>> colsByFragment =
            // allColumns.get(qual);
            // if (!colsByFragment.containsKey(fragment)) {
            // // add empty map to be sure the hier table is seen
            // Map<String, DirectColumn> colsByKey = new HashMap<String,
            // DirectColumn>();
            // colsByFragment.put(fragment, colsByKey);
            // }
            Map<String, Table> tablesByFragment = allTables.get(qual);
            if (!tablesByFragment.containsKey(fragment)) {
                tablesByFragment.put(fragment, table);
            }
        }

        List<String> whereClauses = new LinkedList<String>();
        List<Serializable> whereParams = new LinkedList<Serializable>();

        /*
         * Walk joins.
         */

        StringBuilder from = new StringBuilder();
        List<Serializable> fromParams = new LinkedList<Serializable>();
        int njoin = 0;
        for (Join j : walker_from_joins) {
            njoin++;
            boolean firstJoin = njoin == 1;

            // table this join is about

            String qual = j.corr;
            Table qualHierTable = getTable(hierTable, qual);
            Table table = null;
            if (firstJoin) {
                // start with hier
                table = qualHierTable;
            } else {
                // do requested join
                if (j.kind.equals("LEFT") || j.kind.equals("RIGHT")) {
                    from.append(" ");
                    from.append(j.kind);
                }
                from.append(" JOIN ");
                // find which table in on1/on2 refers to current qualifier
                Set<String> fqns = columnsPerQual.get(qual);
                for (String fqn : Arrays.asList(j.on1, j.on2)) {
                    if (!fqns.contains(fqn)) {
                        continue;
                    }
                    Column col = columns.get(fqn);
                    table = col.getTable();
                }
                if (table == null) {
                    throw new StorageException(
                            "Bad query, qualifier not found: " + qual);
                }
            }
            String tableMainId = table.getColumn(model.MAIN_KEY).getFullQuotedName();

            // join requested table

            String name;
            if (table.isAlias()) {
                name = table.getRealTable().getQuotedName() + " "
                        + table.getQuotedName();
            } else {
                name = table.getQuotedName();
            }
            from.append(name);

            if (!firstJoin) {
                // emit actual join requested
                from.append(" ON ");
                from.append(j.on1);
                from.append(" = ");
                from.append(j.on2);
            }

            // join other fragments for qualifier

            for (Table t : allTables.get(qual).values()) {
                if (t.getName().equals(table.getName())) {
                    // this one was the first, already done
                    continue;
                }
                String n;
                if (t.isAlias()) {
                    n = t.getRealTable().getQuotedName() + " "
                            + t.getQuotedName();
                } else {
                    n = t.getQuotedName();
                }
                from.append(" LEFT JOIN ");
                from.append(n);
                from.append(" ON ");
                from.append(t.getColumn(model.MAIN_KEY).getFullQuotedName());
                from.append(" = ");
                from.append(tableMainId);
            }

            // restrict to relevant primary types TODO XXX XXXXXXXXXXX

            String nuxeoType;
            boolean skipFolderish;
            if (j.table.equalsIgnoreCase(BaseTypeId.CMIS_DOCUMENT.value())) {
                nuxeoType = TypeConstants.DOCUMENT;
                skipFolderish = true;
            } else if (j.table.equalsIgnoreCase(BaseTypeId.CMIS_FOLDER.value())) {
                nuxeoType = "Folder"; // TODO extract constant
                skipFolderish = false;
            } else {
                nuxeoType = j.table;
                skipFolderish = false;
            }
            Set<String> subTypes = model.getDocumentSubTypes(nuxeoType);
            if (subTypes == null) {
                throw new QueryMakerException("Unknown type: " + j.table);
            }
            List<String> types = new ArrayList<String>();
            List<String> qms = new ArrayList<String>();
            for (String type : subTypes) {
                Set<String> facets = model.getDocumentTypeFacets(type);
                if (skipFolderish && facets.contains(FacetNames.FOLDERISH)) {
                    continue;
                }
                if (skipHidden
                        && facets.contains(FacetNames.HIDDEN_IN_NAVIGATION)) {
                    continue;
                }
                if (type.equals(model.ROOT_TYPE)) {
                    continue;
                }
                types.add(type);
                qms.add("?");
            }
            if (types.isEmpty()) {
                types.add("__NOSUCHTYPE__");
                qms.add("?");
            }
            whereClauses.add(String.format(
                    "%s IN (%s)",
                    qualHierTable.getColumn(model.MAIN_PRIMARY_TYPE_KEY).getFullQuotedName(),
                    StringUtils.join(qms, ", ")));
            for (String type : types) {
                whereParams.add(type);
            }

            // lifecycle not deleted filter

            if (skipHidden) {
                Table misc = getTable(database.getTable(model.MISC_TABLE_NAME),
                        qual);
                Column lscol = misc.getColumn(model.MISC_LIFECYCLE_STATE_KEY);
                whereClauses.add(String.format("%s <> ?",
                        lscol.getFullQuotedName()));
                whereParams.add(LifeCycleConstants.DELETED_STATE);
            }

            // security check

            if (queryFilter != null && queryFilter.getPrincipals() != null) {
                Serializable principals;
                Serializable permissions;
                if (dialect.supportsArrays()) {
                    principals = queryFilter.getPrincipals();
                    permissions = queryFilter.getPermissions();
                } else {
                    principals = StringUtils.join(queryFilter.getPrincipals(),
                            '|');
                    permissions = StringUtils.join(
                            queryFilter.getPermissions(), '|');
                }
                if (dialect.supportsReadAcl()) {
                    /* optimized read acl */
                    String readAclTable;
                    String readAclIdCol;
                    String readAclAclIdCol;
                    if (walker_from_joins.size() == 1) {
                        readAclTable = model.HIER_READ_ACL_TABLE_NAME;
                        readAclIdCol = model.HIER_READ_ACL_TABLE_NAME + '.'
                                + model.HIER_READ_ACL_ID;
                        readAclAclIdCol = model.HIER_READ_ACL_TABLE_NAME + '.'
                                + model.HIER_READ_ACL_ACL_ID;
                    } else {
                        String alias = "nxr" + njoin;
                        readAclTable = model.HIER_READ_ACL_TABLE_NAME + " "
                                + alias; // TODO dialect
                        readAclIdCol = alias + '.' + model.HIER_READ_ACL_ID;
                        readAclAclIdCol = alias + '.'
                                + model.HIER_READ_ACL_ACL_ID;
                    }
                    whereClauses.add(dialect.getReadAclsCheckSql(readAclAclIdCol));
                    whereParams.add(principals);
                    from.append(String.format(" JOIN %s ON %s = %s",
                            readAclTable, tableMainId, readAclIdCol));
                } else {
                    whereClauses.add(dialect.getSecurityCheckSql(tableMainId));
                    whereParams.add(principals);
                    whereParams.add(permissions);
                }
            }
        }

        /*
         * Joins for the external fulltext matches.
         */

        Collections.sort(ftJoins); // implicit JOINs last (PostgreSQL)
        for (org.nuxeo.ecm.core.storage.sql.jdbc.db.Join join : ftJoins) {
            from.append(join.toString());
            if (join.tableParam != null) {
                fromParams.add(join.tableParam);
            }
        }

        /*
         * Where clause.
         */

        String walker_select_where = null;
        if (walker_select_where != null) {
            whereClauses.add('(' + walker_select_where + ')');
            List<String> walker_select_where_params = Collections.emptyList(); // XXX
            whereParams.addAll(walker_select_where_params);
        }

        /*
         * What we select.
         */

        CMISQLMapMaker mapMaker = new CMISQLMapMaker(columnsWhat);
        String what = StringUtils.join(mapMaker.selectWhat, ", ");
        if (distinct) {
            what = "DISTINCT " + what;
        }
        List<Serializable> whatParams = mapMaker.selectWhatParams;

        /*
         * Create the whole select.
         */

        Select select = new Select(null);
        select.setWhat(what);
        select.setFrom(from.toString());
        // TODO(fromParams); // TODO add before whereParams
        select.setWhere(StringUtils.join(whereClauses, " AND "));
        String[] walker_select_orderby = new String[0]; // XXX
        select.setOrderBy(StringUtils.join(walker_select_orderby, ", "));

        Query q = new Query();
        q.selectInfo = new SQLInfoSelect(select.getStatement(), mapMaker);
        q.selectParams = whatParams;
        q.selectParams.addAll(fromParams);
        q.selectParams.addAll(whereParams);
        return q;
    }

    protected Set<String> allQualifiers = new HashSet<String>();

    /** Map of qualifier -> fragment -> table */
    protected Map<String, Map<String, Table>> allTables = new HashMap<String, Map<String, Table>>();

    /** Map of qualifier -> fragment -> column key -> column */
    protected Map<String, Map<String, Map<String, DirectColumn>>> allColumns = new HashMap<String, Map<String, Map<String, DirectColumn>>>();

    /** Records and returns direct column if added. */
    protected DirectColumn recordCmisSelector(CmisSelector sel, boolean inWhere) {
        if (!(sel instanceof ColumnReference)) {
            return null;
        }
        ColumnReference col = (ColumnReference) sel;
        String qual = col.getTypeQueryName();
        String id = col.getPropertyId();
        NamedColumn nc = findColumn(id, qual, false);
        if (nc instanceof DirectColumn) {
            DirectColumn dc = (DirectColumn) nc;
            return recordDirectColumn(qual, dc);
        } else if (inWhere) {
            throw new QueryMakerException("Column " + id + " is not queryable");
        } else {
            // virtual column
            return null;
        }
    }

    /** Records direct column. */
    protected NamedColumn recordColumnReference(ColumnReference col) {
        String qual = col.getTypeQueryName();
        String id = col.getPropertyId();
        NamedColumn nc = findColumn(id, qual, false);
        if (nc instanceof DirectColumn) {
            recordDirectColumn(qual, (DirectColumn) nc);
        }
        // else virtual column
        return nc;
    }

    /** Records and returns if added (not already there). */
    protected DirectColumn recordDirectColumn(String qual, DirectColumn dc) {
        Table table = dc.column.getTable();
        String fragment = table.getName();

        allQualifiers.add(qual);

        // put in allTables multi-level map
        Map<String, Table> tablesByFragment = allTables.get(qual);
        if (tablesByFragment == null) {
            tablesByFragment = new HashMap<String, Table>();
            allTables.put(qual, tablesByFragment);
        }
        tablesByFragment.put(fragment, table);

        // put in allColumns multi-level map
        Map<String, Map<String, DirectColumn>> colsByFragment = allColumns.get(qual);
        if (colsByFragment == null) {
            colsByFragment = new HashMap<String, Map<String, DirectColumn>>();
            allColumns.put(qual, colsByFragment);
        }
        Map<String, DirectColumn> colsByKey = colsByFragment.get(fragment);
        if (colsByKey == null) {
            colsByKey = new HashMap<String, DirectColumn>();
            colsByFragment.put(fragment, colsByKey);
        }
        DirectColumn prev = colsByKey.put(dc.column.getKey(), dc);

        return prev == null ? dc : null;
    }

    /**
     * Map maker that can deal with aliased column names and computed values.
     */
    public class CMISQLMapMaker implements MapMaker {

        /** column names to select */
        public final List<String> selectWhat;

        /** parameters in columns names */
        public final List<Serializable> selectWhatParams;

        /** result set columns */
        public final List<Column> columns;

        /** result set keys in map */
        public final List<String> keys;

        /** virtual columns */
        public final List<VirtualColumn> virtual;

        // public final NuxeoConnection conn;

        public CMISQLMapMaker(List<SelectedColumn> columnsWhat) {
            // this.conn = conn;
            selectWhat = new ArrayList<String>(columnsWhat.size());
            selectWhatParams = new ArrayList<Serializable>(0);
            columns = new ArrayList<Column>(columnsWhat.size());
            keys = new ArrayList<String>(columnsWhat.size());
            virtual = new ArrayList<VirtualColumn>();
            for (SelectedColumn sc : columnsWhat) {
                if (sc instanceof DirectColumn) {
                    DirectColumn dc = (DirectColumn) sc;
                    Column col = dc.column;
                    columns.add(col);
                    selectWhat.add(col.getFullQuotedName());
                    keys.add(getColumnName(dc));
                } else if (sc instanceof VirtualColumn) {
                    virtual.add((VirtualColumn) sc);
                } else if (sc instanceof ScoreColumn) {
                    if (fulltextMatchInfo == null) {
                        throw new QueryMakerException(
                                "Cannot use SCORE() without CONTAINS()");
                    }
                    columns.add(fulltextMatchInfo.scoreCol);
                    selectWhat.add(fulltextMatchInfo.scoreExpr);
                    if (fulltextMatchInfo.scoreExprParam != null) {
                        selectWhatParams.add(fulltextMatchInfo.scoreExprParam);
                    }
                    keys.add(sc.alias);
                }
            }
        }

        @Override
        public Map<String, Serializable> makeMap(ResultSet rs)
                throws SQLException {
            // compute map from result set
            Map<String, Serializable> map = new HashMap<String, Serializable>();
            int i = 1;
            for (Column column : columns) {
                String key = keys.get(i - 1);
                Serializable value = column.getFromResultSet(rs, i++);
                if (value instanceof Double) {
                    value = BigDecimal.valueOf(((Double) value).doubleValue());
                }
                map.put(key, value);
            }

            // virtual values
            Map<String, DocumentModel> docs = null;
            for (VirtualColumn vc : virtual) {
                String qual = vc.qual;
                if (docs == null) {
                    docs = new HashMap<String, DocumentModel>(2);
                }
                DocumentModel doc = docs.get(qual);
                if (doc == null) {
                    // find main id for this qualifier in the result set
                    // (main id always included in joins)
                    NamedColumn idsc = getSpecialColumn(PropertyIds.OBJECT_ID,
                            qual);
                    String id = (String) map.get(getColumnName(idsc));
                    try {
                        // reentrant call to the same session, but the MapMaker
                        // is only called from the IterableQueryResult in
                        // queryAndFetch which manipulates no session state
                        // doc = conn.session.getDocument(new IdRef(id));
                        doc = null;
                        throw new ClientException();
                    } catch (ClientException e) {
                        log.error("Cannot get document: " + id, e);
                    }
                    docs.put(qual, doc);
                }
                Serializable v;
                if (doc == null) {
                    // could not fetch
                    v = null;
                } else {
                    // TODO avoid fecthing doc and using a NuxeoProperty to
                    // compute things like cmis:baseTypeId
                    // PropertyDefinition pd =
                    // SimpleType.PROPS_MAP.get(vc.name);
                    // Property p = NuxeoProperty.construct(vc.name, pd,
                    // new DocHolder(doc));
                    // v = p.getValue();
                    v = null;
                }
                map.put(getColumnName(vc), v);
            }

            return map;
        }

    }

    // called from parser
    public SelectedColumn referToAllColumns(String qual) {
        return new StarColumn(qual);
    }

    // called from parser in select
    public SelectedColumn referToScoreInSelect() {
        if (hasScoreInSelect) {
            throw new QueryMakerException("At most one SCORE() is allowed");
        }
        hasScoreInSelect = true;
        SelectedColumn sc = new ScoreColumn();
        columnAliases.put(sc.alias, sc); // alias may be redefined later

        // make sure we have a default qualifier present
        String qual = null;
        Set<String> fqns = columnsPerQual.get(qual);
        if (fqns == null) {
            columnsPerQual.put(qual, fqns = new LinkedHashSet<String>());
        }

        return sc;
    }

    // called from parser
    public NamedColumn referToColumnInSelect(String c, String qual) {
        NamedColumn nc = findColumn(c, qual, false);
        if (nc instanceof DirectColumn) {
            recordCol(((DirectColumn) nc).column, qual);
        }
        return nc;
    }

    // called from parser
    public String referToColumnInWhere(String c, String qual) {
        NamedColumn nc = findColumn(c, qual, false);
        if (!(nc instanceof DirectColumn)) {
            throw new QueryMakerException("Column " + c + " is not queryable");
        }
        Column col = ((DirectColumn) nc).column;
        recordCol(col, qual);
        return col.getFullQuotedName();
    }

    // called from parser
    public String referToColumnInOrderBy(String c, String qual) {
        NamedColumn nc;
        if (qual == null && columnAliases.containsKey(c)) {
            SelectedColumn sc = columnAliases.get(c);
            if (sc instanceof ScoreColumn) {
                return fulltextMatchInfo.scoreAlias;
            }
            nc = (NamedColumn) sc;
        } else {
            nc = findColumn(c, qual, false);
            // check there's no alias to this column
            // (if there is, it must be used instead of the column itself)
            for (SelectedColumn asc : columnAliases.values()) {
                // only NamedColumn has equals() but that's enough here
                if (columnAliases.values().contains(nc)) {
                    throw new QueryMakerException(
                            "Column "
                                    + c
                                    + " cannot be used in ORDER BY because it is aliased");
                }
            }
        }
        if (!(nc instanceof DirectColumn)) {
            throw new QueryMakerException("Column " + c
                    + " cannot be used in ORDER BY because it is virtual");
        }
        Column col = ((DirectColumn) nc).column;
        recordCol(col, qual);
        return col.getFullQuotedName();
    }

    protected void recordCol(Column col, String qual) {
        String fqn = col.getFullQuotedName();
        columns.put(fqn, col);
        Set<String> fqns = columnsPerQual.get(qual);
        if (fqns == null) {
            columnsPerQual.put(qual, fqns = new LinkedHashSet<String>());
        }
        fqns.add(fqn);
    }

    // called from parser
    public void aliasColumn(SelectedColumn sc, String alias) {
        sc.alias = alias;
        columnAliases.put(alias, sc);
    }

    // finds a multi-valued column, assumed to not be a cmis: one
    // called from parser
    public Column findMultiColumn(String name, String qual) {
        NamedColumn nc = findColumn(name, qual, true);
        if (!(nc instanceof DirectColumn)) {
            throw new QueryMakerException("Not a valid multi-valued property: "
                    + name);
        }
        return ((DirectColumn) nc).column;
    }

    protected NamedColumn findColumn(String name, String qual, boolean multi) {
        String ucname = name.toUpperCase();
        if (ucname.startsWith(CMIS_PREFIX.toUpperCase())) {
            if (multi) {
                throw new QueryMakerException(
                        "Must use multi-valued property instead of " + name);
            }
            return getSpecialColumn(name, qual);
        } else {
            String propertyName = propertyInfoNames.get(ucname);
            if (propertyName == null) {
                throw new QueryMakerException("Unknown field: " + name);
            }
            ModelProperty propertyInfo = model.getPropertyInfo(propertyName);
            if (multi != propertyInfo.propertyType.isArray()) {
                String msg = multi ? "Must use multi-valued property instead of %s"
                        : "Cannot use multi-valued property %s";
                throw new QueryMakerException(String.format(msg, name));
            }
            Table table = getTable(
                    database.getTable(propertyInfo.fragmentName), qual);
            Column col = table.getColumn(multi ? model.COLL_TABLE_VALUE_KEY
                    : propertyInfo.fragmentKey);
            String cname = allPropNames.get(ucname);
            return new DirectColumn(cname, qual, col);
        }
    }

    protected Table getTable(Table table, String qual) {
        if (qual == null) {
            return table;
        } else {
            return new TableAlias(table, getTableAlias(table, qual));
        }
    }

    protected String getTableAlias(Table table, String qual) {
        return "_" + qual + "_" + table.getName();
    }

    protected static Map<String, String> systemPropNames = new HashMap<String, String>();
    static {
        for (String prop : Arrays.asList( //
                PropertyIds.OBJECT_ID, //
                PropertyIds.OBJECT_TYPE_ID, //
                PropertyIds.PARENT_ID, //
                PropertyIds.NAME, //
                PropertyIds.CREATED_BY, //
                PropertyIds.CREATION_DATE, //
                PropertyIds.LAST_MODIFICATION_DATE //
        )) {
            systemPropNames.put(prop.toUpperCase(), prop);
        }
    }

    /**
     * Returns either a column if there is a direct mapping, or a string for
     * things that will need to be post-computed.
     */
    protected NamedColumn getSpecialColumn(String name, String qual) {
        String cname = allPropNames.get(name.toUpperCase());
        if (cname == null) {
            throw new QueryMakerException("Unknown field: " + name);
        }
        Column col = getSpecialColumn(cname);
        if (col != null) {
            // alias table according to qualifier
            if (qual != null) {
                col = getTable(col.getTable(), qual).getColumn(col.getKey());
                // TODO ensure key == name, or add getName()
            }
            return new DirectColumn(cname, qual, col);
        } else {
            // use computed values for the rest
            return new VirtualColumn(cname, qual);
        }
    }

    // called with canonicalized name
    protected Column getSpecialColumn(String name) {
        if (name.equals(PropertyIds.OBJECT_ID)) {
            return hierTable.getColumn(model.MAIN_KEY);
        }
        if (name.equals(PropertyIds.OBJECT_TYPE_ID)) {
            // joinedHierTable
            return hierTable.getColumn(model.MAIN_PRIMARY_TYPE_KEY);
        }
        if (name.equals(PropertyIds.PARENT_ID)) {
            return hierTable.getColumn(model.HIER_PARENT_KEY);
        }
        if (name.equals(PropertyIds.NAME)) {
            return hierTable.getColumn(model.HIER_CHILD_NAME_KEY);
        }
        if (name.equals(PropertyIds.CREATED_BY)) {
            return database.getTable(DC_FRAGMENT_NAME).getColumn(DC_CREATOR_KEY);
        }
        if (name.equals(PropertyIds.CREATION_DATE)) {
            return database.getTable(DC_FRAGMENT_NAME).getColumn(DC_CREATED_KEY);
        }
        if (name.equals(PropertyIds.LAST_MODIFICATION_DATE)) {
            return database.getTable(DC_FRAGMENT_NAME).getColumn(
                    DC_MODIFIED_KEY);
        }
        return null;
    }

    protected static String getColumnName(NamedColumn nc) {
        String key = nc.name;
        if (nc.qual != null) {
            key = nc.qual + '.' + key;
        }
        return key;
    }

    protected String getInFolderSql(String qual, String arg,
            List<Serializable> params) {
        String idCol = referToColumnInWhere(PropertyIds.PARENT_ID, qual);
        params.add(arg);
        return idCol + " = ?";
    }

    protected String getInTreeSql(String qual, String arg,
            List<Serializable> params) {
        String idCol = referToColumnInWhere(PropertyIds.OBJECT_ID, qual);
        params.add(arg);
        return dialect.getInTreeSql(idCol);
    }

    protected String getContainsSql(String qual, String arg,
            List<Serializable> params) {
        if (hasContains) {
            throw new QueryMakerException("At most one CONTAINS() is allowed");
        }
        hasContains = true;
        Column mainColumn = hierTable.getColumn(model.MAIN_KEY);
        FulltextMatchInfo info = dialect.getFulltextScoredMatchInfo(arg,
                Model.FULLTEXT_DEFAULT_INDEX, 1, mainColumn, model, database);
        fulltextMatchInfo = info;
        for (org.nuxeo.ecm.core.storage.sql.jdbc.db.Join join : info.joins) {
            if (join.kind == org.nuxeo.ecm.core.storage.sql.jdbc.db.Join.LEFT) {

            }
        }
        if (info.joins != null) {
            ftJoins.addAll(info.joins);
        }
        String sql = info.whereExpr;
        if (info.whereExprParam != null) {
            params.add(info.whereExprParam);
        }
        return sql;
    }

    private boolean sameString(String s1, String s2) {
        return s1 == null ? s2 == null : s1.equals(s2);
    }

    /*
     * ********************************************************************
     * ********************************************************************
     * ********************************************************************
     * ********************************************************************
     */

    @Override
    public void onStartProcessing(Tree node) {
    }

    @Override
    public void onStopProcessing() {
    }

    @Override
    public void onEquals(Tree eqNode, Tree leftNode, Tree rightNode) {
    }

    @Override
    public void onNotEquals(Tree neNode, Tree leftNode, Tree rightNode) {
    }

    @Override
    public void onGreaterThan(Tree gtNode, Tree leftNode, Tree rightNode) {
    }

    @Override
    public void onGreaterOrEquals(Tree geNode, Tree leftNode, Tree rightNode) {
    }

    @Override
    public void onLessThan(Tree ltNode, Tree leftNode, Tree rightNode) {
    }

    @Override
    public void onLessOrEquals(Tree leqNode, Tree leftNode, Tree rightNode) {
    }

    @Override
    public void onPreNot(Tree opNode, Tree leftNode) {
    }

    @Override
    public void onNot(Tree opNode, Tree leftNode) {
    }

    @Override
    public void onPostNot(Tree opNode, Tree leftNode) {
    }

    @Override
    public void onPreAnd(Tree opNode, Tree leftNode, Tree rightNode) {
    }

    @Override
    public void onAnd(Tree opNode, Tree leftNode, Tree rightNode) {
    }

    @Override
    public void onPostAnd(Tree opNode, Tree leftNode, Tree rightNode) {
    }

    @Override
    public void onPreOr(Tree opNode, Tree leftNode, Tree rightNode) {
    }

    @Override
    public void onOr(Tree opNode, Tree leftNode, Tree rightNode) {
    }

    @Override
    public void onPostOr(Tree opNode, Tree leftNode, Tree rightNode) {
    }

    @Override
    public void onIn(Tree node, Tree colNode, Tree listNode) {
    }

    @Override
    public void onNotIn(Tree nod, Tree colNode, Tree listNode) {
    }

    @Override
    public void onInAny(Tree node, Tree colNode, Tree listNode) {
    }

    @Override
    public void onNotInAny(Tree node, Tree colNode, Tree listNode) {
    }

    @Override
    public void onEqAny(Tree node, Tree literalNode, Tree colNode) {
    }

    @Override
    public void onIsNull(Tree nullNode, Tree colNode) {
    }

    @Override
    public void onIsNotNull(Tree notNullNode, Tree colNode) {
    }

    @Override
    public void onIsLike(Tree node, Tree colNode, Tree stringNode) {
    }

    @Override
    public void onIsNotLike(Tree node, Tree colNode, Tree stringNode) {
    }

    @Override
    public void onContains(Tree node, Tree colNode, Tree paramNode) {
    }

    @Override
    public void onInFolder(Tree node, Tree colNode, Tree paramNode) {
    }

    @Override
    public void onInTree(Tree node, Tree colNode, Tree paramNode) {
    }

    @Override
    public void onScore(Tree node) {
    }

}
