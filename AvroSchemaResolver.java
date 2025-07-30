package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.avro.Schema;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class AvroSchemaResolver {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java AvroSchemaResolver <inputDir> <outputFile>");
            System.exit(1);
        }
        String inputDir = args[0];
        String outputFile = args[1];

        ObjectMapper mapper = new ObjectMapper();

        // 1. Read all .avsc files as raw JSON strings, keyed by schema full name
        Map<String, String> schemaJsons = new HashMap<>();

        Files.list(Paths.get(inputDir))
                .filter(path -> path.toString().endsWith(".avsc"))
                .forEach(path -> {
                    try {
                        String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                        if (json.startsWith("\uFEFF")) {
                            json = json.substring(1);
                        }
                        JsonNode root = mapper.readTree(json);
                        String fullName = getFullName(root);
                        if (fullName == null) {
                            throw new RuntimeException("Schema missing name or namespace in file: " + path);
                        }
                        schemaJsons.put(fullName, json);
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to read schema file: " + path, e);
                    }
                });

        // 2. Extract dependencies for each schema (without using Avro parser)
        Map<String, Set<String>> dependencies = new HashMap<>();

        for (Map.Entry<String, String> entry : schemaJsons.entrySet()) {
            JsonNode root = mapper.readTree(entry.getValue());
            Set<String> deps = new HashSet<>();
            findDependencies(root, deps, schemaJsons.keySet());
            dependencies.put(entry.getKey(), deps);
        }

        // 3. Topologically sort schemas by dependencies
        List<String> sortedNames = topologicalSort(schemaJsons.keySet(), dependencies);

        // Debug: print parse order
        System.out.println("Schema parse order (dependencies first):");
        for (String name : sortedNames) {
            System.out.println("  " + name);
        }

        // 4. Parse schemas in dependency order with a single shared Avro Schema.Parser
        Schema.Parser parser = new Schema.Parser();
        List<Schema> parsedSchemas = new ArrayList<>();
        for (String name : sortedNames) {
            String json = schemaJsons.get(name);
            if (json == null) {
                throw new RuntimeException("Schema JSON missing for: " + name);
            }
            Schema schema = parser.parse(json);
            parsedSchemas.add(schema);
        }

        // 5. Write combined schema as JSON array to output file
        try (PrintWriter writer = new PrintWriter(outputFile)) {
            writer.println("[");
            for (int i = 0; i < parsedSchemas.size(); i++) {
                writer.print(parsedSchemas.get(i).toString(true));
                if (i < parsedSchemas.size() - 1) {
                    writer.println(",");
                }
            }
            writer.println("\n]");
        }
        System.out.println("Combined schema written to: " + outputFile);
    }

    static String getFullName(JsonNode root) {
        if (!root.has("name")) {
            return null;
        }
        String name = root.get("name").asText();
        if (root.has("namespace")) {
            return root.get("namespace").asText() + "." + name;
        } else {
            return name;
        }
    }

    /**
     * Recursively find user-defined schema dependencies from JSON representation.
     *
     * @param node       JSON node representing schema or type
     * @param deps       set to collect full names of dependencies found
     * @param knownNames set of all known schema names (to detect references)
     */
    static void findDependencies(JsonNode node, Set<String> deps, Set<String> knownNames) {
        if (node == null || node.isNull()) {
            return;
        }

        if (node.isTextual()) {
            String typeName = node.asText();
            if (knownNames.contains(typeName)) {
                deps.add(typeName);
            }
            return;
        }

        if (node.isObject()) {
            String type = node.has("type") ? node.get("type").asText() : null;
            if (type == null) {
                // No "type" field? Check all child nodes
                for (JsonNode child : node) {
                    findDependencies(child, deps, knownNames);
                }
                return;
            }

            switch (type) {
                case "record":
                case "error":
                    if (node.has("fields")) {
                        for (JsonNode field : node.get("fields")) {
                            findDependencies(field.get("type"), deps, knownNames);
                        }
                    }
                    break;
                case "array":
                    findDependencies(node.get("items"), deps, knownNames);
                    break;
                case "map":
                    findDependencies(node.get("values"), deps, knownNames);
                    break;
                case "union":
                    if (node.has("types")) {
                        for (JsonNode typeNode : node.get("types")) {
                            findDependencies(typeNode, deps, knownNames);
                        }
                    }
                    break;
                default:
                    if (knownNames.contains(type)) {
                        deps.add(type);
                    }
                    break;
            }
            return;
        }

        if (node.isArray()) {
            for (JsonNode child : node) {
                findDependencies(child, deps, knownNames);
            }
        }
    }

    /**
     * Topological sort (dependency order) on the schema full names.
     * Dependencies come before dependents in the result list.
     *
     * @param names        Set of all schema full names.
     * @param dependencies Map of schema full name -> set of dependencies.
     * @return List of schema full names sorted in dependency order.
     */
    static List<String> topologicalSort(Set<String> names, Map<String, Set<String>> dependencies) {
        List<String> sorted = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();

        for (String name : names) {
            dfs(name, dependencies, visited, visiting, sorted);
        }

        // Remove reversal here because list is already correct
        // Collections.reverse(sorted);

        return sorted;
    }

     static void dfs(String name, Map<String, Set<String>> dependencies, Set<String> visited,
                            Set<String> visiting, List<String> sorted) {
        if (visited.contains(name)) {
            return;
        }
        if (visiting.contains(name)) {
            throw new RuntimeException("Cycle detected in schema dependencies at: " + name);
        }
        visiting.add(name);
        for (String dep : dependencies.getOrDefault(name, Collections.emptySet())) {
            dfs(dep, dependencies, visited, visiting, sorted);
        }
        visiting.remove(name);
        visited.add(name);
        sorted.add(name);
    }
}
