package dev.agentguard.gateway.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Simple, explainable rules: each one has a name, and the flag in the audit
 * log says exactly which rule matched. Patterns are kept narrow to limit
 * false alarms on ordinary finance documents.
 */
@Component
public class RuleBasedClassifier implements ContentClassifier {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    static final Map<String, Pattern> RULES = new LinkedHashMap<>();

    static {
        RULES.put("ignore-instructions", Pattern.compile(
            "\\b(ignore|disregard|forget)\\s+(all\\s+|any\\s+|the\\s+)?(previous|prior|above|earlier|your)\\s+(instructions|rules|guidelines|prompts?)", FLAGS));
        RULES.put("fake-system-message", Pattern.compile(
            "\\[\\s*(system|assistant|developer)\\b[^\\]]{0,40}[:\\]]|^\\s*(system|assistant)\\s*:", FLAGS | Pattern.MULTILINE));
        RULES.put("hidden-comment", Pattern.compile("<!--[\\s\\S]*?-->"));
        RULES.put("addressed-to-ai", Pattern.compile(
            "\\b(the|this|you,?)\\s*(ai|assistant|agent|model|chatbot)\\b[^.\\n]{0,30}\\b(must|should|shall|needs? to|has to)\\b", FLAGS));
        RULES.put("tool-invocation", Pattern.compile(
            "\\b(export_report|get_employee_salaries|run_query|cancel_invoices|update_invoice|get_supplier_record|search_suppliers)\\b", FLAGS));
        RULES.put("authority-claim", Pattern.compile(
            "\\b(the\\s+user\\s+is\\s+(actually|really|now)\\s+(an?\\s+)?(admin|administrator|hr|manager))\\b", FLAGS));
        RULES.put("exfiltration", Pattern.compile(
            "\\b(send|forward|email|post|upload)\\s+(the\\s+|all\\s+|every\\s+)?(results?|data|records|list|export)\\b[^.\\n]{0,40}\\bto\\b", FLAGS));
        RULES.put("role-override", Pattern.compile(
            "\\b(you\\s+are\\s+now|from\\s+now\\s+on\\s+you|act\\s+as\\s+(an?\\s+)?(admin|administrator|system))\\b", FLAGS));
    }

    @Override
    public Verdict classify(String text) {
        if (text == null || text.isEmpty()) {
            return new Verdict(List.of());
        }
        List<String> flags = new ArrayList<>();
        RULES.forEach((name, pattern) -> {
            if (pattern.matcher(text).find()) {
                flags.add(name);
            }
        });
        return new Verdict(List.copyOf(flags));
    }
}
