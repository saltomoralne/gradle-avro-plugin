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
            System.err.println("Usage: java AvroSchemaResolver <inputDir> <outputDir>");
            System.exit(1);
        }

        Path inputDir = Paths.get(args[0]);
        Path outputDir = Paths.get(args[1]);
        Files.createDirectories(outputDir);

        ObjectMapper mapper = new ObjectMapper();

        // Step 1: Load all schemas
        Map<String, String> schemaJsons = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.walk(inputDir)) {
            for (Path path : stream.filter(p -> p.toString().endsWith(".avsc")).collect(Collectors.toList())) {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                if (content.startsWith("\uFEFF")) content = content.substring(1);
                JsonNode root = mapper.readTree(content);

                if (root.isArray()) {
                    for (JsonNode node : root) processSchemaNode(node, schemaJsons, mapper);
                } else if (root.has("protocol") && root.has("types")) {
                    for (JsonNode node : root.get("types")) processSchemaNode(node, schemaJsons, mapper);
                } else {
                    processSchemaNode(root, schemaJsons, mapper);
                }
            }
        }

        // Step 2: Build dependency graph
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        for (var entry : schemaJsons.entrySet()) {
            JsonNode root = mapper.readTree(entry.getValue());
            Set<String> deps = new HashSet<>();
            findDependencies(root, deps, schemaJsons.keySet());
            dependencies.put(entry.getKey(), deps);
        }

        // Step 3: Topological sort
        List<String> sortedSchemas = topologicalSort(schemaJsons.keySet(), dependencies);

        System.out.println("Schema output order:");
        for (int i = 0; i < sortedSchemas.size(); i++) {
            System.out.printf("  %02d: %s%n", i + 1, sortedSchemas.get(i));
        }

        // Step 4: Parse schemas with one parser
        Schema.Parser parser = new Schema.Parser();
        Map<String, Schema> fullNameToSchema = new HashMap<>();
        for (String fullName : sortedSchemas) {
            Schema schema = parser.parse(schemaJsons.get(fullName));
            fullNameToSchema.put(fullName, schema);
        }

        // Step 5: Output each schema to its own file
        for (int i = 0; i < sortedSchemas.size(); i++) {
            String fullName = sortedSchemas.get(i);
            Schema schema = replaceInlineWithParsedSchemas(fullNameToSchema.get(fullName), fullNameToSchema);

            String fileName = String.format("%02d_%s.avsc", i + 1, fullName.replaceAll("[^A-Za-z0-9_.]", "_"));
            Path out = outputDir.resolve(fileName);

            try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
                writer.println(schema.toString(true));
            }

            System.out.println("✓ Wrote " + fileName);
        }
    }

    private static void processSchemaNode(JsonNode node, Map<String, String> schemaMap, ObjectMapper mapper) throws IOException {
        String fullName = getFullName(node);
        String json = mapper.writeValueAsString(node);

        if (fullName == null) throw new RuntimeException("Missing name/namespace in schema");

        if (schemaMap.containsKey(fullName)) {
            System.err.println("Warning: duplicate schema for " + fullName + ", overriding.");
        }
        schemaMap.put(fullName, json);
    }

    private static String getFullName(JsonNode node) {
        if (!node.has("name")) return null;
        String name = node.get("name").asText();
        return node.has("namespace") ? node.get("namespace").asText() + "." + name : name;
    }

    private static void findDependencies(JsonNode node, Set<String> deps, Set<String> knownNames) {
        if (node == null || node.isNull()) return;

        if (node.isTextual()) {
            String type = node.asText();
            String full = resolveFullName(type, knownNames);
            if (full != null) deps.add(full);
            return;
        }

        if (node.isObject()) {
            JsonNode typeNode = node.get("type");
            if (typeNode == null) {
                for (JsonNode child : node) findDependencies(child, deps, knownNames);
                return;
            }

            if (typeNode.isTextual()) {
                String typeValue = typeNode.asText();
                switch (typeValue) {
                    case "record":
                    case "enum":
                    case "fixed":
                        if (node.has("fields")) {
                            for (JsonNode f : node.get("fields")) {
                                findDependencies(f.get("type"), deps, knownNames);
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
                            for (JsonNode t : node.get("types")) findDependencies(t, deps, knownNames);
                        }
                        break;
                    default:
                        String resolved = resolveFullName(typeValue, knownNames);
                        if (resolved != null) deps.add(resolved);
                }
            } else if (typeNode.isArray()) {
                for (JsonNode t : typeNode) findDependencies(t, deps, knownNames);
            }
        }

        if (node.isArray()) {
            for (JsonNode child : node) findDependencies(child, deps, knownNames);
        }
    }

    private static String resolveFullName(String typeName, Set<String> known) {
        if (known.contains(typeName)) return typeName;
        if (!typeName.contains(".")) {
            List<String> matches = known.stream()
                    .filter(name -> name.endsWith("." + typeName))
                    .collect(Collectors.toList());
            if (matches.size() == 1) return matches.get(0);
            if (matches.size() > 1) {
                System.err.println("Ambiguous simple type name: " + typeName + " → " + matches);
            }
        }
        return null;
    }

    private static List<String> topologicalSort(Set<String> schemaNames, Map<String, Set<String>> deps) {
        List<String> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();

        for (String name : schemaNames) {
            dfs(name, deps, visited, visiting, result);
        }

        return result;
    }

    private static void dfs(String name, Map<String, Set<String>> deps,
                            Set<String> visited, Set<String> visiting, List<String> result) {
        if (visited.contains(name)) return;
        if (visiting.contains(name)) throw new RuntimeException("Cycle detected at: " + name);

        visiting.add(name);
        for (String dep : deps.getOrDefault(name, Set.of())) {
            dfs(dep, deps, visited, visiting, result);
        }
        visiting.remove(name);
        visited.add(name);
        result.add(name);
    }

    private static Schema replaceInlineWithParsedSchemas(Schema schema, Map<String, Schema> fullNameToSchema) {
        switch (schema.getType()) {
            case RECORD:
                List<Schema.Field> fields = new ArrayList<>();
                for (Schema.Field f : schema.getFields()) {
                    Schema ref = replaceInlineWithParsedSchemas(f.schema(), fullNameToSchema);
                    if (isNamedType(ref)) {
                        Schema shared = fullNameToSchema.get(ref.getFullName());
                        if (shared != null) ref = shared;
                    }
                    fields.add(new Schema.Field(f.name(), ref, f.doc(), f.defaultVal(), f.order()));
                }
                Schema copy = Schema.createRecord(schema.getName(), schema.getDoc(), schema.getNamespace(), false);
                copy.setFields(fields);
                return copy;

            case ARRAY:
                return Schema.createArray(replaceInlineWithParsedSchemas(schema.getElementType(), fullNameToSchema));

            case MAP:
                return Schema.createMap(replaceInlineWithParsedSchemas(schema.getValueType(), fullNameToSchema));

            case UNION:
                List<Schema> unionItems = new ArrayList<>();
                for (Schema s : schema.getTypes()) {
                    Schema ref = replaceInlineWithParsedSchemas(s, fullNameToSchema);
                    if (isNamedType(ref)) {
                        Schema shared = fullNameToSchema.get(ref.getFullName());
                        if (shared != null) ref = shared;
                    }
                    unionItems.add(ref);
                }
                return Schema.createUnion(unionItems);

            default:
                return schema;
        }
    }

    private static boolean isNamedType(Schema s) {
        return s.getType() == Schema.Type.RECORD ||
               s.getType() == Schema.Type.ENUM ||
               s.getType() == Schema.Type.FIXED;
    }
}
