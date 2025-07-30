package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.avro.Schema;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AvroSchemaResolver {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java AvroSchemaResolver <inputDir> <outputFile>");
            System.exit(1);
        }
        Path inputDir = Paths.get(args[0]);
        Path outputFile = Paths.get(args[1]);

        ObjectMapper mapper = new ObjectMapper();

        // Map schema fullname -> JSON Schema string
        Map<String, String> schemaJsons = new LinkedHashMap<>();

        // Read all .avsc files, parse possibly multiple schemas inside each file
        try (Stream<Path> stream = Files.walk(inputDir)) {
            List<Path> files = stream
                    .filter(path -> path.toString().endsWith(".avsc"))
                    .collect(Collectors.toList());

            for (Path file : files) {
                String jsonText = Files.readString(file, StandardCharsets.UTF_8);
                if (jsonText.startsWith("\uFEFF")) {
                    jsonText = jsonText.substring(1);
                }

                JsonNode root = mapper.readTree(jsonText);

                if (root.isArray()) {
                    // Multiple schemas in JSON array
                    for (JsonNode schemaNode : root) {
                        processSchemaNode(schemaNode, schemaJsons, mapper);
                    }
                } else if (root.has("protocol") && root.has("types")) {
                    // Avro protocol file with "types" array
                    JsonNode typesNode = root.get("types");
                    for (JsonNode schemaNode : typesNode) {
                        processSchemaNode(schemaNode, schemaJsons, mapper);
                    }
                } else {
                    // Single schema object
                    processSchemaNode(root, schemaJsons, mapper);
                }
            }
        }

        // Extract dependencies from each schema JSON
        Map<String, Set<String>> dependencies = new HashMap<>();
        for (Map.Entry<String, String> entry : schemaJsons.entrySet()) {
            JsonNode root = mapper.readTree(entry.getValue());
            Set<String> deps = new HashSet<>();
            findDependencies(root, deps, schemaJsons.keySet());
            dependencies.put(entry.getKey(), deps);
        }

        // Topological sort schemas by dependency
        List<String> sortedNames = topologicalSort(schemaJsons.keySet(), dependencies);

        System.out.println("Schema parse order (dependencies first):");
        for (String n : sortedNames) {
            System.out.println("  " + n);
        }

        // Parse schemas in dependency order using one Avro Schema.Parser instance
        Schema.Parser parser = new Schema.Parser();
        List<Schema> parsedSchemas = new ArrayList<>();
        for (String name : sortedNames) {
            String schemaJson = schemaJsons.get(name);
            if (schemaJson == null) {
                throw new RuntimeException("Missing JSON content for schema " + name);
            }
            parsedSchemas.add(parser.parse(schemaJson));
        }

        // Write combined schemas as JSON array to output file
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8))) {
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

    private static void processSchemaNode(JsonNode schemaNode, Map<String, String> schemaJsons, ObjectMapper mapper) throws IOException {
        String fullName = getFullName(schemaNode);
        if (fullName == null) {
            throw new RuntimeException("Schema missing 'name' field or namespace");
        }
        String jsonStr = mapper.writeValueAsString(schemaNode);
        if (schemaJsons.containsKey(fullName)) {
            System.err.println("Warning: duplicate schema detected for '" + fullName + "'. Overriding previous definition.");
        }
        schemaJsons.put(fullName, jsonStr);
    }

    static String getFullName(JsonNode root) {
        if (!root.has("name")) {
            return null;
        }
        String name = root.get("name").asText();
        if (root.has("namespace")) {
            return root.get("namespace").asText() + "." + name;
        }
        return name;
    }

    /**
     * Recursively find user-defined schema dependencies from JSON node.
     * @param node JSON schema or type node
     * @param deps a set to collect full names of dependencies found
     * @param knownNames all known schema full names
     */
    static void findDependencies(JsonNode node, Set<String> deps, Set<String> knownNames) {
        if (node == null || node.isNull()) return;

        if (node.isTextual()) {
            String typeName = node.asText();
            if (knownNames.contains(typeName)) {
                deps.add(typeName);
            }
            return;
        }

        if (node.isObject()) {
            JsonNode typeNode = node.get("type");
            if (typeNode == null) {
                // No "type" field, check all child nodes recursively
                for (JsonNode child : node) {
                    findDependencies(child, deps, knownNames);
                }
                return;
            }

            if (typeNode.isTextual()) {
                String type = typeNode.asText();
                switch (type) {
                    case "record":
                    case "error":
                        JsonNode fields = node.get("fields");
                        if (fields != null) {
                            for (JsonNode field : fields) {
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
                        JsonNode types = node.get("types");
                        if (types != null) {
                            for (JsonNode t : types) {
                                findDependencies(t, deps, knownNames);
                            }
                        }
                        break;
                    default:
                        if (knownNames.contains(type)) {
                            deps.add(type);
                        }
                }
            } else if (typeNode.isArray()) {
                // Defensive: type node array (rare)
                for (JsonNode t : typeNode) {
                    findDependencies(t, deps, knownNames);
                }
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
     * Topological sort based on dependency graph.
     * @param names set of schema full names
     * @param dependencies map: schema full name -> set of dependencies
     * @return list of schema full names sorted so dependencies come before dependents
     */
    static List<String> topologicalSort(Set<String> names, Map<String, Set<String>> dependencies) {
        List<String> sorted = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();

        for (String name : names) {
            dfs(name, dependencies, visited, visiting, sorted);
        }
        // DFS adds dependencies before dependents, so no reversal needed
        return sorted;
    }

    private static void dfs(String name, Map<String, Set<String>> dependencies, Set<String> visited,
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
