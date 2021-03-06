/*
 * (C) Copyright 2006-2011 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Olivier Grisel
 *     Florent Guillaume
 */
package org.nuxeo.ecm.directory.sql;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.nuxeo.common.xmap.annotation.XNode;
import org.nuxeo.common.xmap.annotation.XObject;
import org.nuxeo.ecm.core.storage.sql.ColumnType;
import org.nuxeo.ecm.core.storage.sql.jdbc.db.Delete;
import org.nuxeo.ecm.core.storage.sql.jdbc.db.Insert;
import org.nuxeo.ecm.core.storage.sql.jdbc.db.Select;
import org.nuxeo.ecm.core.storage.sql.jdbc.db.Table;
import org.nuxeo.ecm.core.storage.sql.jdbc.db.Table.IndexType;
import org.nuxeo.ecm.core.storage.sql.jdbc.dialect.Dialect;
import org.nuxeo.ecm.directory.AbstractReference;
import org.nuxeo.ecm.directory.Directory;
import org.nuxeo.ecm.directory.DirectoryException;
import org.nuxeo.ecm.directory.PermissionDescriptor;

@XObject(value = "tableReference")
public class TableReference extends AbstractReference implements Cloneable {

    @XNode("@field")
    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    @Override
    @XNode("@directory")
    public void setTargetDirectoryName(String targetDirectoryName) {
        this.targetDirectoryName = targetDirectoryName;
    }

    @XNode("@table")
    protected String tableName;

    @XNode("@sourceColumn")
    protected String sourceColumn;

    @XNode("@targetColumn")
    protected String targetColumn;

    @XNode("@schema")
    protected String schemaName;

    @XNode("@dataFile")
    protected String dataFileName;

    private Table table;

    private Dialect dialect;

    private boolean initialized = false;

    private SQLDirectory getSQLSourceDirectory() throws DirectoryException {
        Directory dir = getSourceDirectory();
        return (SQLDirectory) dir;
    }

    private void initialize(SQLSession sqlSession) throws DirectoryException {
        SQLDirectory directory = getSQLSourceDirectory();
        String createTablePolicy = directory.getDescriptor().createTablePolicy;
        Table table = getTable();
        SQLHelper helper = new SQLHelper(sqlSession.sqlConnection, table, dataFileName, createTablePolicy);
        helper.setupTable();
    }

    @Override
    public void addLinks(String sourceId, List<String> targetIds) throws DirectoryException {
        if (targetIds == null) {
            return;
        }
        try (SQLSession session = getSQLSession()) {
            addLinks(sourceId, targetIds, session);
        }
    }

    @Override
    public void addLinks(List<String> sourceIds, String targetId) throws DirectoryException {
        if (sourceIds == null) {
            return;
        }
        try (SQLSession session = getSQLSession()) {
            addLinks(sourceIds, targetId, session);
        }
    }

    public void addLinks(String sourceId, List<String> targetIds, SQLSession session) throws DirectoryException {
        if (targetIds == null) {
            return;
        }
        for (String targetId : targetIds) {
            addLink(sourceId, targetId, session, true);
        }
    }

    public void addLinks(List<String> sourceIds, String targetId, SQLSession session) throws DirectoryException {
        if (sourceIds == null) {
            return;
        }
        for (String sourceId : sourceIds) {
            addLink(sourceId, targetId, session, true);
        }
    }

    public boolean exists(String sourceId, String targetId, SQLSession session) throws DirectoryException {
        // String selectSql = String.format(
        // "SELECT COUNT(*) FROM %s WHERE %s = ? AND %s = ?", tableName,
        // sourceColumn, targetColumn);

        Table table = getTable();
        Select select = new Select(table);
        select.setFrom(table.getQuotedName());
        select.setWhat("count(*)");
        String whereString = String.format("%s = ? and %s = ?", table.getColumn(sourceColumn).getQuotedName(),
                table.getColumn(targetColumn).getQuotedName());

        select.setWhere(whereString);

        String selectSql = select.getStatement();
        if (session.logger.isLogEnabled()) {
            session.logger.logSQL(selectSql, Arrays.<Serializable> asList(sourceId, targetId));
        }

        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = session.sqlConnection.prepareStatement(selectSql);
            ps.setString(1, sourceId);
            ps.setString(2, targetId);
            rs = ps.executeQuery();
            rs.next();
            return rs.getInt(1) > 0;
        } catch (SQLException e) {
            throw new DirectoryException(String.format("error reading link from %s to %s", sourceId, targetId), e);
        } finally {
            try {
                if (rs != null) {
                    rs.close();
                }
                if (ps != null) {
                    ps.close();
                }
            } catch (SQLException sqle) {
                throw new DirectoryException(sqle);
            }
        }
    }

    public void addLink(String sourceId, String targetId, SQLSession session, boolean checkExisting)
            throws DirectoryException {
        // OG: the following query should have avoided the round trips but
        // does not work for some reason that might be related to a bug in the
        // JDBC driver:
        //
        // String sql = String.format(
        // "INSERT INTO %s (%s, %s) (SELECT ?, ? FROM %s WHERE %s = ? AND %s =
        // ? HAVING COUNT(*) = 0)", tableName, sourceColumn, targetColumn,
        // tableName, sourceColumn, targetColumn);

        // first step: check that this link does not exist yet
        if (checkExisting && exists(sourceId, targetId, session)) {
            return;
        }

        // second step: add the link

        // String insertSql = String.format(
        // "INSERT INTO %s (%s, %s) VALUES (?, ?)", tableName,
        // sourceColumn, targetColumn);
        Table table = getTable();
        Insert insert = new Insert(table);
        insert.addColumn(table.getColumn(sourceColumn));
        insert.addColumn(table.getColumn(targetColumn));
        String insertSql = insert.getStatement();
        if (session.logger.isLogEnabled()) {
            session.logger.logSQL(insertSql, Arrays.<Serializable> asList(sourceId, targetId));
        }

        PreparedStatement ps = null;
        try {
            ps = session.sqlConnection.prepareStatement(insertSql);
            ps.setString(1, sourceId);
            ps.setString(2, targetId);
            ps.execute();
        } catch (SQLException e) {
            throw new DirectoryException(String.format("error adding link from %s to %s", sourceId, targetId), e);
        } finally {
            try {
                if (ps != null) {
                    ps.close();
                }
            } catch (SQLException sqle) {
                throw new DirectoryException(sqle);
            }
        }
    }

    protected List<String> getIdsFor(String valueColumn, String filterColumn, String filterValue)
            throws DirectoryException {
        try (SQLSession session = getSQLSession()) {
            // String sql = String.format("SELECT %s FROM %s WHERE %s = ?",
            // table.getColumn(valueColumn), tableName, filterColumn);
            Table table = getTable();
            Select select = new Select(table);
            select.setWhat(table.getColumn(valueColumn).getQuotedName());
            select.setFrom(table.getQuotedName());
            select.setWhere(table.getColumn(filterColumn).getQuotedName() + " = ?");

            String sql = select.getStatement();
            if (session.logger.isLogEnabled()) {
                session.logger.logSQL(sql, Collections.<Serializable> singleton(filterValue));
            }

            List<String> ids = new LinkedList<String>();
            try (PreparedStatement ps = session.sqlConnection.prepareStatement(sql)) {
                ps.setString(1, filterValue);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getString(valueColumn));
                    }
                    return ids;
                }
            } catch (SQLException e) {
                throw new DirectoryException("error fetching reference values: ", e);
            }
        }
    }

    @Override
    public List<String> getSourceIdsForTarget(String targetId) throws DirectoryException {
        return getIdsFor(sourceColumn, targetColumn, targetId);
    }

    @Override
    public List<String> getTargetIdsForSource(String sourceId) throws DirectoryException {
        return getIdsFor(targetColumn, sourceColumn, sourceId);
    }

    public void removeLinksFor(String column, String entryId, SQLSession session) throws DirectoryException {
        Table table = getTable();
        String sql = String.format("DELETE FROM %s WHERE %s = ?", table.getQuotedName(), table.getColumn(column)
                                                                                              .getQuotedName());
        if (session.logger.isLogEnabled()) {
            session.logger.logSQL(sql, Collections.<Serializable> singleton(entryId));
        }
        PreparedStatement ps = null;
        try {
            ps = session.sqlConnection.prepareStatement(sql);
            ps.setString(1, entryId);
            ps.execute();
        } catch (SQLException e) {
            throw new DirectoryException("error remove links to " + entryId, e);
        } finally {
            try {
                if (ps != null) {
                    ps.close();
                }
            } catch (SQLException sqle) {
                throw new DirectoryException(sqle);
            }
        }
    }

    public void removeLinksForSource(String sourceId, SQLSession session) throws DirectoryException {
        removeLinksFor(sourceColumn, sourceId, session);
    }

    public void removeLinksForTarget(String targetId, SQLSession session) throws DirectoryException {
        removeLinksFor(targetColumn, targetId, session);
    }

    @Override
    public void removeLinksForSource(String sourceId) throws DirectoryException {
        try (SQLSession session = getSQLSession()) {
            removeLinksForSource(sourceId, session);
        }
    }

    @Override
    public void removeLinksForTarget(String targetId) throws DirectoryException {
        try (SQLSession session = getSQLSession()) {
            removeLinksForTarget(targetId, session);
        }
    }

    public void setIdsFor(String idsColumn, List<String> ids, String filterColumn, String filterValue,
            SQLSession session) throws DirectoryException {

        List<String> idsToDelete = new LinkedList<String>();
        Set<String> idsToAdd = new HashSet<String>();
        if (ids != null) { // ids may be null
            idsToAdd.addAll(ids);
        }
        Table table = getTable();

        // iterate over existing links to find what to add and what to remove
        String selectSql = String.format("SELECT %s FROM %s WHERE %s = ?", table.getColumn(idsColumn).getQuotedName(),
                table.getQuotedName(), table.getColumn(filterColumn).getQuotedName());
        PreparedStatement ps = null;
        try {
            ps = session.sqlConnection.prepareStatement(selectSql);
            ps.setString(1, filterValue);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String existingId = rs.getString(1);
                if (idsToAdd.contains(existingId)) {
                    // to not add already existing ids
                    idsToAdd.remove(existingId);
                } else {
                    // delete unwanted existing ids
                    idsToDelete.add(existingId);
                }
            }
            rs.close();
        } catch (SQLException e) {
            throw new DirectoryException("failed to fetch existing links for " + filterValue, e);
        } finally {
            try {
                if (ps != null) {
                    ps.close();
                }
            } catch (SQLException sqle) {
                throw new DirectoryException(sqle);
            }
        }

        if (!idsToDelete.isEmpty()) {
            // remove unwanted links

            // String deleteSql = String.format(
            // "DELETE FROM %s WHERE %s = ? AND %s = ?", tableName,
            // filterColumn, idsColumn);
            Delete delete = new Delete(table);
            String whereString = String.format("%s = ? AND %s = ?", table.getColumn(filterColumn).getQuotedName(),
                    table.getColumn(idsColumn).getQuotedName());
            delete.setWhere(whereString);
            String deleteSql = delete.getStatement();

            try {
                ps = session.sqlConnection.prepareStatement(deleteSql);
                for (String unwantedId : idsToDelete) {
                    if (session.logger.isLogEnabled()) {
                        session.logger.logSQL(deleteSql, Arrays.<Serializable> asList(filterValue, unwantedId));
                    }
                    ps.setString(1, filterValue);
                    ps.setString(2, unwantedId);
                    ps.execute();
                }
            } catch (SQLException e) {
                throw new DirectoryException("failed to remove unwanted links for " + filterValue, e);
            } finally {
                try {
                    if (ps != null) {
                        ps.close();
                    }
                } catch (SQLException sqle) {
                    throw new DirectoryException(sqle);
                }
            }
        }

        if (!idsToAdd.isEmpty()) {
            // add missing links
            if (filterColumn.equals(sourceColumn)) {
                for (String missingId : idsToAdd) {
                    addLink(filterValue, missingId, session, false);
                }
            } else {
                for (String missingId : idsToAdd) {
                    addLink(missingId, filterValue, session, false);
                }
            }
        }
    }

    public void setSourceIdsForTarget(String targetId, List<String> sourceIds, SQLSession session)
            throws DirectoryException {
        setIdsFor(sourceColumn, sourceIds, targetColumn, targetId, session);
    }

    public void setTargetIdsForSource(String sourceId, List<String> targetIds, SQLSession session)
            throws DirectoryException {
        setIdsFor(targetColumn, targetIds, sourceColumn, sourceId, session);
    }

    @Override
    public void setSourceIdsForTarget(String targetId, List<String> sourceIds) throws DirectoryException {
        try (SQLSession session = getSQLSession()) {
            setSourceIdsForTarget(targetId, sourceIds, session);
        }
    }

    @Override
    public void setTargetIdsForSource(String sourceId, List<String> targetIds) throws DirectoryException {
        try (SQLSession session = getSQLSession()) {
            setTargetIdsForSource(sourceId, targetIds, session);
        }
    }

    // TODO add support for the ListDiff type

    protected SQLSession getSQLSession() throws DirectoryException {
        if (!initialized) {
            try (SQLSession sqlSession = (SQLSession) getSourceDirectory().getSession()) {
                initialize(sqlSession);
                initialized = true;
            }
        }
        return (SQLSession) getSourceDirectory().getSession();
    }

    /**
     * Initialize if needed, using an existing session.
     *
     * @param sqlSession
     * @throws DirectoryException
     */
    protected void maybeInitialize(SQLSession sqlSession) throws DirectoryException {
        if (!initialized) {
            initialize(sqlSession);
            initialized = true;
        }
    }

    public Table getTable() throws DirectoryException {
        if (table == null) {
            boolean nativeCase = getSQLSourceDirectory().useNativeCase();
            table = SQLHelper.addTable(tableName, getDialect(), nativeCase);
            SQLHelper.addColumn(table, sourceColumn, ColumnType.STRING, nativeCase);
            SQLHelper.addColumn(table, targetColumn, ColumnType.STRING, nativeCase);
            // index added for Azure
            table.addIndex(null, IndexType.MAIN_NON_PRIMARY, sourceColumn);
        }
        return table;
    }

    private Dialect getDialect() throws DirectoryException {
        if (dialect == null) {
            dialect = getSQLSourceDirectory().getDialect();
        }
        return dialect;
    }

    public String getSourceColumn() {
        return sourceColumn;
    }

    public String getTargetColumn() {
        return targetColumn;
    }

    public String getTargetDirectoryName() {
        return targetDirectoryName;
    }

    public String getTableName() {
        return tableName;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public String getDataFileName() {
        return dataFileName;
    }

    /**
     * @since 5.6
     */
    @Override
    public TableReference clone() {
        TableReference clone = (TableReference) super.clone();
        // basic fields are already copied by super.clone()
        return clone;
    }

}
