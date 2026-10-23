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

import org.moqui.context.ArtifactExecutionInfo;
import org.moqui.context.ExecutionContext;
import org.moqui.entity.EntityList;
import org.moqui.entity.EntityValue;
import org.moqui.util.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Executes read-only SQL only against individually authorized Moqui DataDocument indices. */
public final class McpBiSqlExecutor {
    public static final int DEFAULT_MAX_ROWS = 100;
    public static final int MAX_ROWS = 500;
    private static final String DATA_DOCUMENT_ARTIFACT_PREFIX = "bi.datadocument.";
    private static final Pattern LIMIT_PATTERN = Pattern.compile("(?is)\\bLIMIT\\s+(\\d+)(?:\\s+OFFSET\\s+\\d+)?\\s*;?\\s*$");

    private McpBiSqlExecutor() { }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> execute(ExecutionContext ec, String sql, Integer maxRows) {
        Map<String, Object> analysis = McpSqlSupport.analyze(sql);
        int safeMaxRows = normalizeMaxRows(maxRows);
        requireBoundedResult(sql, safeMaxRows);
        Map<String, String> documentByIndex = findDataDocumentsByIndex(ec);
        List<String> dataDocumentIds = new ArrayList<>();
        List<ArtifactExecutionInfo> artifacts = new ArrayList<>();

        try {
            for (String relation : (List<String>) analysis.get("tables")) {
                String dataDocumentId = documentByIndex.get(normalizeRelation(relation));
                if (dataDocumentId == null) {
                    throw new IllegalArgumentException("Relation " + relation + " is not an authorized Moqui DataDocument index");
                }
                ArtifactExecutionInfo artifact = ec.getArtifactExecution().push(
                        DATA_DOCUMENT_ARTIFACT_PREFIX + dataDocumentId,
                        ArtifactExecutionInfo.AT_OTHER, ArtifactExecutionInfo.AUTHZA_VIEW, true);
                artifacts.add(artifact);
                dataDocumentIds.add(dataDocumentId);
            }

            Map<String, Object> request = new LinkedHashMap<>();
            request.put("query", sql);
            request.put("fetch_size", safeMaxRows);
            RestClient.RestResponse response = ec.getElastic().getDefault().call(
                    RestClient.POST, null, "_plugins/_sql", null, request);
            if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
                String responseText = response.text();
                if (responseText != null && responseText.contains("no handler found")) {
                    throw new IllegalStateException("The configured OpenSearch node does not have the version-matched opensearch-sql plugin");
                }
                throw new IllegalArgumentException("OpenSearch SQL rejected the query");
            }
            Object body = response.jsonObject();
            if (!(body instanceof Map)) throw new IllegalStateException("OpenSearch SQL returned an invalid response");

            analysis.put("analysisOnly", false);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("analysis", analysis);
            result.put("dataDocumentIds", dataDocumentIds);
            result.put("maxRows", safeMaxRows);
            result.put("result", body);
            return result;
        } finally {
            for (int index = artifacts.size() - 1; index >= 0; index--) ec.getArtifactExecution().pop(artifacts.get(index));
        }
    }

    private static Map<String, String> findDataDocumentsByIndex(ExecutionContext ec) {
        EntityList documents = ec.getEntity().find("moqui.entity.document.DataDocument").list();
        Map<String, String> documentByIndex = new LinkedHashMap<>();
        for (EntityValue document : documents) {
            String dataDocumentId = document.getString("dataDocumentId");
            if (dataDocumentId != null) documentByIndex.put(toIndexName(dataDocumentId), dataDocumentId);
        }
        return documentByIndex;
    }

    private static int normalizeMaxRows(Integer maxRows) {
        int value = maxRows != null ? maxRows : DEFAULT_MAX_ROWS;
        if (value < 1 || value > MAX_ROWS) throw new IllegalArgumentException("maxRows must be between 1 and " + MAX_ROWS);
        return value;
    }

    private static void requireBoundedResult(String sql, int maxRows) {
        Matcher matcher = LIMIT_PATTERN.matcher(sql);
        if (!matcher.find()) throw new IllegalArgumentException("SQL must end with LIMIT no greater than maxRows");
        int limit = Integer.parseInt(matcher.group(1));
        if (limit < 1 || limit > maxRows) throw new IllegalArgumentException("SQL LIMIT must be between 1 and maxRows");
    }

    private static String normalizeRelation(String relation) {
        if (relation == null || relation.isEmpty() || relation.indexOf('.') >= 0) {
            throw new IllegalArgumentException("SQL relations must be unqualified Moqui DataDocument index names");
        }
        return relation.toLowerCase(Locale.ROOT);
    }

    private static String toIndexName(String dataDocumentId) {
        return dataDocumentId.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("([A-Z])([A-Z][a-z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
