package dev.agentguard.gateway.tools;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.util.TablesNamesFinder;

/**
 * Checks SQL sent to run_query by parsing it, not by searching the text.
 *
 * Accepted: exactly one SELECT, on allowed tables and columns only, using
 * allowed functions only. Refused: anything else, including comments (often
 * used to hide content), SELECT *, FOR UPDATE, database links, system views.
 * The accepted query is wrapped so that a row limit always applies.
 *
 * Behind this guard, the finance service also runs the query inside an
 * Oracle read-only transaction, so a write that slipped through would fail.
 */
public final class SqlGuard {

    /** Allowed tables and the columns that may be read from each. No names, identity, contact, iban or salary. */
    static final Map<String, Set<String>> ALLOWED = Map.of(
        "DEPARTMENT", Set.of("ID", "CODE", "NAME"),
        "EMPLOYEE", Set.of("ID", "DEPARTMENT_ID", "ROLE", "HIRED_ON"),
        "SUPPLIER", Set.of("ID", "DEPARTMENT_ID", "MANAGER_ID", "ONBOARDED_ON"),
        "INVOICE", Set.of("ID", "SUPPLIER_ID", "MANAGER_ID", "DEPARTMENT_ID", "DUE_AT", "AMOUNT_MAD", "STATUS"));

    static final Set<String> ALLOWED_FUNCTIONS = Set.of(
        "COUNT", "SUM", "AVG", "MIN", "MAX", "ROUND", "TRUNC", "UPPER", "LOWER", "NVL", "COALESCE", "TO_CHAR");

    private SqlGuard() {
    }

    public record Result(boolean accepted, String reason, String rewrittenSql) {
        static Result refuse(String reason) {
            return new Result(false, reason, null);
        }
    }

    public static Result check(String sql, int maxRows) {
        if (sql == null || sql.isBlank()) {
            return Result.refuse("empty query");
        }
        String trimmed = sql.strip();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).strip();
        }
        if (trimmed.contains("--") || trimmed.contains("/*") || trimmed.contains("*/")) {
            return Result.refuse("comments are not allowed");
        }
        if (trimmed.contains("@")) {
            return Result.refuse("database links are not allowed");
        }

        Statement statement;
        try {
            Statements all = CCJSqlParserUtil.parseStatements(trimmed);
            if (all.size() != 1) {
                return Result.refuse("exactly one statement is allowed");
            }
            statement = all.get(0);
        } catch (JSQLParserException | RuntimeException e) {
            return Result.refuse("the query could not be parsed");
        }
        if (!(statement instanceof Select select)) {
            return Result.refuse("only SELECT is allowed");
        }
        if (select instanceof PlainSelect plain && (plain.getForMode() != null || plain.getIntoTables() != null)) {
            return Result.refuse("SELECT ... FOR UPDATE and SELECT INTO are not allowed");
        }

        Inspector inspector = new Inspector();
        Set<String> tables;
        try {
            tables = inspector.getTables((Statement) select);
        } catch (RuntimeException e) {
            return Result.refuse("the query uses a construct the guard does not support");
        }
        if (inspector.problem != null) {
            return Result.refuse(inspector.problem);
        }

        Set<String> readable = new HashSet<>();
        for (String raw : tables) {
            String table = unqualified(raw);
            if (table == null || !ALLOWED.containsKey(table)) {
                return Result.refuse("table " + raw + " is not allowed");
            }
            readable.addAll(ALLOWED.get(table));
        }
        for (String column : inspector.columns) {
            if (!readable.contains(column) && !inspector.aliases.contains(column)) {
                return Result.refuse("column " + column + " is not allowed");
            }
        }

        String limited = "SELECT * FROM (" + select + ") FETCH FIRST " + maxRows + " ROWS ONLY";
        return new Result(true, null, limited);
    }

    /** Accepts TABLE or FINANCE.TABLE; anything else (SYS.X, other schemas) returns null. */
    private static String unqualified(String name) {
        String n = name.toUpperCase(Locale.ROOT).replace("\"", "");
        int dot = n.lastIndexOf('.');
        if (dot < 0) {
            return n;
        }
        return n.substring(0, dot).equals("FINANCE") ? n.substring(dot + 1) : null;
    }

    /** Walks the whole statement and records every column, function and alias it uses. */
    private static final class Inspector extends TablesNamesFinder<Void> {
        final Set<String> columns = new HashSet<>();
        final Set<String> aliases = new HashSet<>();
        String problem;
        /** How many function calls we are inside; COUNT(*) puts a bare * inside a function. */
        private int functionDepth;

        @Override
        public <S> Void visit(Column column, S context) {
            columns.add(column.getColumnName().toUpperCase(Locale.ROOT).replace("\"", ""));
            return super.visit(column, context);
        }

        @Override
        public <S> Void visit(Function function, S context) {
            String name = function.getName() == null ? "" : function.getName().toUpperCase(Locale.ROOT);
            if (!ALLOWED_FUNCTIONS.contains(name) && problem == null) {
                problem = "function " + name + " is not allowed";
            }
            functionDepth++;
            try {
                return super.visit(function, context);
            } finally {
                functionDepth--;
            }
        }

        @Override
        public <S> Void visit(AllColumns allColumns, S context) {
            if (problem == null && functionDepth == 0) {
                problem = "SELECT * is not allowed, name the columns";
            }
            return super.visit(allColumns, context);
        }

        @Override
        public <S> Void visit(AllTableColumns allTableColumns, S context) {
            if (problem == null) {
                problem = "SELECT t.* is not allowed, name the columns";
            }
            return super.visit(allTableColumns, context);
        }

        @Override
        public <S> Void visit(PlainSelect plainSelect, S context) {
            for (SelectItem<?> item : plainSelect.getSelectItems()) {
                if (item.getAlias() != null) {
                    aliases.add(item.getAlias().getName().toUpperCase(Locale.ROOT).replace("\"", ""));
                }
            }
            return super.visit(plainSelect, context);
        }
    }
}
