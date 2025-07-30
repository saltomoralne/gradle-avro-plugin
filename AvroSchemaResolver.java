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

        // 1. Read all schemas from all .avsc files with support for multiple schemas per file
        Map<String, String> schemaJsons = new LinkedHashMap<>();

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
                    // Multiple schema array
                    for (JsonNode schemaNode : root) {
                        processSchemaNode(schemaNode, schemaJsons, mapper);
                    }
                } else if (root.has("protocol") && root.has("types")) {
                    // Avro protocol JSON with "types" array of schemas
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

        // 2. Extract dependencies for each schema
        Map<String, Set<String>> dependencies = new HashMap<>();
        for (Map.Entry<String, String> entry : schemaJsons.entrySet()) {
            JsonNode root = mapper.readTree(entry.getValue());
            Set<String> deps = new HashSet<>();
            findDependencies(root, deps, schemaJsons.keySet());
            dependencies.put(entry.getKey(), deps);
        }

        // 3. Topological sort of schemas by dependencies
        List<String> sortedNames = topologicalSort(schemaJsons.keySet(), dependencies);

        System.out.println("Schema parse order (dependencies first):");
        for (String name : sortedNames) {
            System.out.println("  " + name);
        }

        // 4. Parse schemas in correct order using one shared parser
        Schema.Parser parser = new Schema.Parser();
        List<Schema> parsedSchemas = new ArrayList<>();
        for (String name : sortedNames) {
            String schemaJson = schemaJsons.get(name);
            if (schemaJson == null) {
                throw new RuntimeException("Schema JSON missing for: " + name);
            }
            parsedSchemas.add(parser.parse(schemaJson));
        }

        // 5. Write combined schemas as JSON array to output file
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

    /**
     * Extract the fully qualified name of a schema from its JSON representation.
     * If 'namespace' is missing, just uses 'name'.
     */
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
     * Recursively find user-defined schema dependencies in the given JSON node.
     * @param node the JSON node to inspect
     * @param deps set of dependencies collected (full names)
     * @param knownNames set of known schema full names to detect user-defined types
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
                // Defensive: no "type" field, traverse children
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
                            for (JsonNode typeEl : types) {
                                findDependencies(typeEl, deps, knownNames);
                            }
                        }
                        break;
                    default:
                        if (knownNames.contains(type)) {
                            deps.add(type);
                        }
                }
            } else if (typeNode.isArray()) {
                for (JsonNode typeEl : typeNode) {
                    findDependencies(typeEl, deps, knownNames);
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
     * Perform topological sort of schemas by dependencies.
     * Dependencies will come before dependents in the returned list.
     * Throws RuntimeException on cycle detection.
     */
    static List<String> topologicalSort(Set<String> names, Map<String, Set<String>> dependencies) {
        List<String> sorted = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();

        for (String name : names) {
            dfs(name, dependencies, visited, visiting, sorted);
        }
        // DFS adds dependencies before dependents, so no reversal required
        return sorted;
    }

    private static void dfs(String name, Map<String, Set<String>> dependencies,
                            Set<String> visited, Set<String> visiting, List<String> sorted) {
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
