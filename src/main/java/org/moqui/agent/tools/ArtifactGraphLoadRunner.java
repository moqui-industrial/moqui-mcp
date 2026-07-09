package org.moqui.agent.tools;

import org.moqui.Moqui;
import org.moqui.context.ArtifactExecutionInfo;
import org.moqui.context.ExecutionContext;

import java.util.LinkedHashMap;
import java.util.Map;

public class ArtifactGraphLoadRunner {
    public static void main(String[] args) {
        String verticesFilePath = System.getProperty("artifactGraph.verticesFilePath");
        String edgesFilePath = System.getProperty("artifactGraph.edgesFilePath");
        if (verticesFilePath == null || verticesFilePath.isBlank()) {
            throw new IllegalArgumentException("Missing -DartifactGraph.verticesFilePath");
        }
        if (edgesFilePath == null || edgesFilePath.isBlank()) {
            throw new IllegalArgumentException("Missing -DartifactGraph.edgesFilePath");
        }

        System.setProperty("moqui.init.static", "true");
        ExecutionContext ec = Moqui.getExecutionContext();
        ArtifactExecutionInfo aei = null;
        try {
            ec.getArtifactExecution().disableAuthz();
            aei = ec.getArtifactExecution().push("artifactGraphLoadRunner",
                    ArtifactExecutionInfo.AT_OTHER, ArtifactExecutionInfo.AUTHZA_ALL, false);
            ec.getArtifactExecution().setAnonymousAuthorizedAll();
            ec.getUser().loginAnonymousIfNoUser();

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("graphId", System.getProperty("artifactGraph.graphId", "AgentArtifactGraph"));
            params.put("graphName", System.getProperty("artifactGraph.graphName", "Agent Artifact Graph"));
            params.put("graphDescription", System.getProperty("artifactGraph.graphDescription",
                    "Materialized structural graph for offline-generated Moqui agent artifacts."));
            params.put("mathModelId", System.getProperty("artifactGraph.mathModelId", "AgentMoquiRagModel_v1"));
            params.put("verticesFilePath", verticesFilePath);
            params.put("edgesFilePath", edgesFilePath);
            params.put("clearExisting", Boolean.parseBoolean(System.getProperty("artifactGraph.clearExisting", "true")));
            params.put("vertexChunkSize", Integer.parseInt(System.getProperty("artifactGraph.vertexChunkSize", "1000")));
            params.put("edgeChunkSize", Integer.parseInt(System.getProperty("artifactGraph.edgeChunkSize", "1000")));

            String summaryFilePath = System.getProperty("artifactGraph.summaryFilePath");
            if (summaryFilePath != null && !summaryFilePath.isBlank()) params.put("summaryFilePath", summaryFilePath);
            String graphTypeEnumId = System.getProperty("artifactGraph.graphTypeEnumId");
            if (graphTypeEnumId != null && !graphTypeEnumId.isBlank()) params.put("graphTypeEnumId", graphTypeEnumId);

            Map<?, ?> result = ec.getService().sync()
                    .name("org.moqui.agent.AgentDocumentServices.load#ArtifactGraphChunked")
                    .parameters(params)
                    .call();
            System.out.println(result);
        } finally {
            try {
                if (aei != null) ec.getArtifactExecution().pop(aei);
            } catch (Throwable ignored) { }
            Moqui.destroyActiveExecutionContext();
            Moqui.destroyActiveExecutionContextFactory();
        }
    }
}
