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

        // 1. Read all schemas from all .avsc files (support multiple schemas per file)
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
                    for (JsonNode schemaNode : root) {
                        processSchemaNode(schemaNode, schemaJsons, mapper);
                    }
                } else if (root.has("protocol") && root.has("types")) {
                    JsonNode typesNode = root.get("types");
                    for (JsonNode schemaNode : typesNode) {
                        processSchemaNode(schemaNode, schemaJsons, mapper);
                    }
                } else {
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

        // 3. Topological sort schemas by dependencies
        List<String> sortedNames = topologicalSort(schemaJsons.keySet(), dependencies);

        System.out.println("Schema parse order (dependencies first):");
        for (String name : sortedNames) {
            System.out.println("  " + name);
        }

        // 4. Parse schemas with one Avro parser
        Schema.Parser parser = new Schema.Parser();
        Map<String, Schema> parsedSchemaMap = new HashMap<>();
        List<Schema> parsedSchemasOrdered = new ArrayList<>();
        for (String fullname : sortedNames) {
            String json = schemaJsons.get(fullname);
            if (json == null) {
                throw new RuntimeException("Schema JSON missing for: " + fullname);
            }
            Schema schema = parser.parse(json);
            parsedSchemaMap.put(fullname, schema);
            parsedSchemasOrdered.add(schema);
        }

        // 5. Replace inline named schema definitions with references
        List<Schema> outputSchemas = new ArrayList<>();
        for (Schema s : parsedSchemasOrdered) {
            outputSchemas.add(replaceInlineWithRefs(s, parsedSchemaMap));
        }

        // 6. Write combined schemas as JSON array
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8))) {
            writer.println("[");
            for (int i = 0; i < outputSchemas.size(); i++) {
                writer.print(outputSchemas.get(i).toString(true));
                if (i < outputSchemas.size() - 1) {
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
        if (!root.has("name")) return null;
        String name = root.get("name").asText();
        if (root.has("namespace")) {
            return root.get("namespace").asText() + "." + name;
        }
        return name;
    }

    static void findDependencies(JsonNode node, Set<String> deps, Set<String> knownNames) {
        if (node == null || node.isNull()) return;

        if (node.isTextual()) {
            String typeName = node.asText();
            String fullName = resolveFullName(knownNames, typeName);
            if (fullName != null) deps.add(fullName);
            return;
        }

        if (node.isObject()) {
            JsonNode typeNode = node.get("type");
            if (typeNode == null) {
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
                        String fullName = resolveFullName(knownNames, type);
                        if (fullName != null) deps.add(fullName);
                }
            } else if (typeNode.isArray()) {
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

    private static String resolveFullName(Set<String> knownNames, String typeName) {
        if (knownNames.contains(typeName)) {
            return typeName;
        }
        if (!typeName.contains(".")) {
            List<String> matches = knownNames.stream()
                    .filter(n -> n.equals(typeName) || n.endsWith("." + typeName))
                    .collect(Collectors.toList());
            if (matches.size() == 1) {
                return matches.get(0);
            } else if (matches.size() > 1) {
                System.err.println("Warning: Ambiguous unqualified type name '" + typeName + "' matched multiple schemas: " + matches);
            }
            return null;
        }
        return null;
    }

    static List<String> topologicalSort(Set<String> names, Map<String, Set<String>> dependencies) {
        List<String> sorted = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();

        for (String name : names) {
            dfs(name, dependencies, visited, visiting, sorted);
        }
        return sorted;
    }

    private static void dfs(String name, Map<String, Set<String>> dependencies,
                            Set<String> visited, Set<String> visiting, List<String> sorted) {
        if (visited.contains(name)) return;
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

    /**
     * Replace inline named schema definitions with references to fully qualified names.
     * This avoids embedding full schema definitions multiple times.
     */
    private static Schema replaceInlineWithRefs(Schema schema, Map<String, Schema> fullNameToSchema) {
        switch (schema.getType()) {
            case RECORD:
            case ERROR:
                List<Schema.Field> newFields = new ArrayList<>();
                for (Schema.Field field : schema.getFields()) {
                    Schema replaced = replaceInlineWithRefs(field.schema(), fullNameToSchema);
                    if (isNamedType(replaced) && fullNameToSchema.containsKey(replaced.getFullName())) {
                        replaced = Schema.createRef(replaced.getFullName());
                    }
                    Schema.Field newField = new Schema.Field(field.name(), replaced, field.doc(), field.defaultVal(), field.order());
                    newFields.add(newField);
                }
                Schema newRecord = Schema.createRecord(schema.getName(), schema.getDoc(), schema.getNamespace(), schema.isError());
                newRecord.setFields(newFields);
                return newRecord;

            case ARRAY:
                return Schema.createArray(replaceInlineWithRefs(schema.getElementType(), fullNameToSchema));

            case MAP:
                return Schema.createMap(replaceInlineWithRefs(schema.getValueType(), fullNameToSchema));

            case UNION:
                List<Schema> newTypes = new ArrayList<>();
                for (Schema s : schema.getTypes()) {
                    Schema replaced = replaceInlineWithRefs(s, fullNameToSchema);
                    if (isNamedType(replaced) && fullNameToSchema.containsKey(replaced.getFullName())) {
                        replaced = Schema.createRef(replaced.getFullName());
                    }
                    newTypes.add(replaced);
                }
                return Schema.createUnion(newTypes);

            default:
                return schema;
        }
    }

    private static boolean isNamedType(Schema schema) {
        Schema.Type t = schema.getType();
        return t == Schema.Type.RECORD || t == Schema.Type.ENUM || t == Schema.Type.FIXED || t == Schema.Type.ERROR;
    }
}
