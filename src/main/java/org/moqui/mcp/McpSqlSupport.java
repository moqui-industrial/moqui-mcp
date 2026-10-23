/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <https://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.mcp;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.GroupByElement;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SelectVisitorAdapter;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;

/** Read-only SQL syntax analysis used before a DataDocument query is authorized or executed. */
public final class McpSqlSupport {
    public static final int MAX_SQL_LENGTH = 20000;

    private McpSqlSupport() { }

    public static Map<String, Object> analyze(String sql) {
        if (sql == null || sql.trim().isEmpty()) throw new IllegalArgumentException("sql is required");
        if (sql.length() > MAX_SQL_LENGTH) throw new IllegalArgumentException("sql exceeds " + MAX_SQL_LENGTH + " characters");

        final Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(sql);
        } catch (JSQLParserException e) {
            throw new IllegalArgumentException("Invalid SQL: " + safeMessage(e));
        }
        if (statements.size() != 1) throw new IllegalArgumentException("Exactly one SQL statement is required");

        Statement statement = statements.get(0);
        if (!(statement instanceof Select)) {
            throw new IllegalArgumentException("Only read-only SELECT statements are allowed");
        }

        Select select = (Select) statement;
        rejectWriteCapableSelect(select);
        Set<String> tables = new LinkedHashSet<>(new TablesNamesFinder<>().getTables(statement));
        Set<String> columns = new LinkedHashSet<>();
        collectSelect(select, columns);

        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("statementType", "SELECT");
        analysis.put("tables", new ArrayList<>(tables));
        analysis.put("columns", new ArrayList<>(columns));
        analysis.put("hasWith", select.getWithItemsList() != null && !select.getWithItemsList().isEmpty());
        analysis.put("analysisOnly", true);
        return analysis;
    }

    private static void collectSelect(Select select, Set<String> columns) {
        if (select == null) return;
        List<WithItem<?>> withItems = select.getWithItemsList();
        if (withItems != null) {
            for (WithItem<?> withItem : withItems) collectSelect(withItem.getSelect(), columns);
        }
        if (select instanceof PlainSelect) collectPlainSelect((PlainSelect) select, columns);
        if (select instanceof SetOperationList) {
            for (Select nestedSelect : ((SetOperationList) select).getSelects()) collectSelect(nestedSelect, columns);
        }
        if (select instanceof ParenthesedSelect) collectSelect(((ParenthesedSelect) select).getSelect(), columns);
        List<OrderByElement> orderByElements = select.getOrderByElements();
        if (orderByElements != null) {
            for (OrderByElement orderByElement : orderByElements) collectExpression(orderByElement.getExpression(), columns);
        }
    }

    private static void collectPlainSelect(PlainSelect plainSelect, Set<String> columns) {
        for (SelectItem<?> selectItem : plainSelect.getSelectItems()) {
            Expression expression = selectItem.getExpression();
            if (expression instanceof AllTableColumns) {
                columns.add(((AllTableColumns) expression).getTable().getFullyQualifiedName() + ".*");
            } else if (expression instanceof AllColumns) {
                columns.add("*");
            } else {
                collectExpression(expression, columns);
            }
        }
        collectExpression(plainSelect.getWhere(), columns);
        collectExpression(plainSelect.getHaving(), columns);
        collectExpression(plainSelect.getQualify(), columns);
        GroupByElement groupBy = plainSelect.getGroupBy();
        if (groupBy != null && groupBy.getGroupByExpressions() != null) {
            for (Object expression : groupBy.getGroupByExpressions().getExpressions()) {
                if (expression instanceof Expression) collectExpression((Expression) expression, columns);
            }
        }
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                collectFromItem(join.getRightItem(), columns);
                if (join.getOnExpressions() != null) {
                    for (Expression expression : join.getOnExpressions()) collectExpression(expression, columns);
                }
                if (join.getUsingColumns() != null) {
                    for (Column column : join.getUsingColumns()) columns.add(column.getFullyQualifiedName());
                }
            }
        }
        collectFromItem(plainSelect.getFromItem(), columns);
    }

    private static void collectFromItem(Object fromItem, Set<String> columns) {
        if (fromItem instanceof ParenthesedSelect) collectSelect(((ParenthesedSelect) fromItem).getSelect(), columns);
        if (fromItem instanceof Select && !(fromItem instanceof PlainSelect)) collectSelect((Select) fromItem, columns);
    }

    private static void collectExpression(Expression expression, Set<String> columns) {
        if (expression == null) return;
        ColumnCollector visitor = new ColumnCollector(columns);
        expression.accept(visitor, null);
    }

    private static void rejectWriteCapableSelect(Select select) {
        if (select == null) return;
        List<WithItem<?>> withItems = select.getWithItemsList();
        if (withItems != null) {
            for (WithItem<?> withItem : withItems) {
                if (withItem.getSelect() == null) throw new IllegalArgumentException("Only read-only SELECT statements are allowed");
                rejectWriteCapableSelect(withItem.getSelect());
            }
        }
        if (select instanceof PlainSelect) {
            List<?> intoTables = ((PlainSelect) select).getIntoTables();
            if (intoTables != null && !intoTables.isEmpty()) throw new IllegalArgumentException("SELECT INTO is not allowed");
        }
        if (select.getForUpdateTable() != null || select.getForClause() != null || select.isSkipLocked()) {
            throw new IllegalArgumentException("Locking SELECT statements are not allowed");
        }
        if (select instanceof SetOperationList) {
            for (Select nestedSelect : ((SetOperationList) select).getSelects()) rejectWriteCapableSelect(nestedSelect);
        }
        if (select instanceof ParenthesedSelect) rejectWriteCapableSelect(((ParenthesedSelect) select).getSelect());
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isEmpty()) return exception.getClass().getSimpleName();
        return message.replaceAll("[\\r\\n]+", " ");
    }

    private static final class ColumnCollector extends ExpressionVisitorAdapter<Void> {
        private final Set<String> columns;

        private ColumnCollector(Set<String> columns) {
            this.columns = columns;
            setSelectVisitor(new SelectVisitorAdapter<Void>() {
                @Override
                public <S> Void visit(PlainSelect plainSelect, S context) {
                    collectPlainSelect(plainSelect, ColumnCollector.this.columns);
                    return null;
                }

                @Override
                public <S> Void visit(ParenthesedSelect parenthesedSelect, S context) {
                    collectSelect(parenthesedSelect.getSelect(), ColumnCollector.this.columns);
                    return null;
                }

                @Override
                public <S> Void visit(SetOperationList setOperationList, S context) {
                    for (Select select : setOperationList.getSelects()) collectSelect(select, ColumnCollector.this.columns);
                    return null;
                }
            });
        }

        @Override
        public <S> Void visit(Column column, S context) {
            columns.add(column.getFullyQualifiedName());
            return null;
        }
    }
}
