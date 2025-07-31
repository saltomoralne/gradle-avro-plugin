import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

public class AvroSchemaSorter {

    private static final String AVSC_SUFFIX = ".avsc";
    private static final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: java AvroSchemaSorter <folder_with_avsc_files>");
            System.exit(1);
        }

        File folder = new File(args[0]);
        if (!folder.isDirectory()) {
            System.err.println("Provided path is not a directory");
            System.exit(1);
        }

        File[] files = folder.listFiles((dir, name) -> name.endsWith(AVSC_SUFFIX));
        if (files == null || files.length == 0) {
            System.err.println("No .avsc files found in folder");
            System.exit(1);
        }

        // Step 1: Parse schemas and collect type info (support single object or array roots)
        Map<String, SchemaFile> nameToSchema = new HashMap<>();
        for (File file : files) {
            JsonNode root = mapper.readTree(file);

            if (root.isArray()) {
                // Multiple schemas in one file
                for (JsonNode item : root) {
                    String fullName = getFullName(item);
                    nameToSchema.put(fullName, new SchemaFile(fullName, file, item));
                }
            } else if (root.isObject()) {
                // Single schema per file
                String fullName = getFullName(root);
                nameToSchema.put(fullName, new SchemaFile(fullName, file, root));
            } else {
                throw new IllegalArgumentException("Schema in file " + file.getName() + " is neither an object nor array");
            }
        }

        // Step 2: Determine dependencies for each schema
        for (SchemaFile schema : nameToSchema.values()) {
            Set<String> deps = findDependencies(schema.root, nameToSchema.keySet());
            deps.remove(schema.name); // Avoid self-dependency
            schema.dependencies.addAll(deps);
        }

        // Step 3: Topologically sort to ensure dependencies come first
        List<SchemaFile> sorted = topologicalSort(nameToSchema);

        // Step 4: Write files with numbered prefixes
        int i = 1;
        for (SchemaFile schema : sorted) {
            String newName = String.format("%02d_%s", i++, schema.file.getName());
            File outputFile = new File(folder, newName);
            Files.copy(schema.file.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Output: " + outputFile.getName());
        }
    }

    /**
     * Extract full name from schema node (namespace.name or just name).
     */
    private static String getFullName(JsonNode root) {
        String namespace = "";
        if (root.has("namespace")) {
            JsonNode ns = root.get("namespace");
            if (ns != null && ns.isTextual()) {
                namespace = ns.asText();
            }
        }
        String name = root.get("name").asText();

        // If name already qualified (contains dot), use as is
        if (name.contains(".")) {
            return name;
        }

        return namespace.isEmpty() ? name : namespace + "." + name;
    }

    /**
     * Recursively find all known schema dependencies in the node.
     * Handles unions (arrays), arrays, maps, nested records/enums/fixed, etc.
     */
    private static Set<String> findDependencies(JsonNode node, Set<String> knownTypes) {
        Set<String> deps = new HashSet<>();
        if (node == null) return deps;

        if (node.isObject()) {
            JsonNode typeNode = node.get("type");

            if (typeNode != null) {
                if (typeNode.isTextual()) {
                    String typeName = typeNode.asText();
                    if (knownTypes.contains(typeName)) {
                        deps.add(typeName);
                    }
                    // ignore primitives and unknowns
                } else if (typeNode.isArray()) {
                    // Union types
                    for (JsonNode subType : typeNode) {
                        deps.addAll(findDependencies(subType, knownTypes));
                    }
                } else if (typeNode.isObject()) {
                    // Complex types: array, map, nested records/enums, etc.
                    String complexType = typeNode.get("type").asText();
                    if ("array".equals(complexType)) {
                        deps.addAll(findDependencies(typeNode.get("items"), knownTypes));
                    } else if ("map".equals(complexType)) {
                        deps.addAll(findDependencies(typeNode.get("values"), knownTypes));
                    } else {
                        // Nested schema type (record/enum/fixed inline)
                        deps.addAll(findDependencies(typeNode, knownTypes));
                    }
                }
            }

            // Also recurse other fields to catch nested definitions (like fields array)
            for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
                String field = it.next();
                if (!"type".equals(field)) {
                    deps.addAll(findDependencies(node.get(field), knownTypes));
                }
            }

        } else if (node.isArray()) {
            for (JsonNode item : node) {
                deps.addAll(findDependencies(item, knownTypes));
            }
        } else if (node.isTextual()) {
            // Node itself is just a type name string
            String typeName = node.asText();
            if (knownTypes.contains(typeName)) {
                deps.add(typeName);
            }
        }
        // Other JSON types (number, boolean...) can be ignored

        return deps;
    }

    /**
     * Perform topological sort using Kahn's algorithm.
     * Throws RuntimeException if cyclic dependencies detected.
     */
    private static List<SchemaFile> topologicalSort(Map<String, SchemaFile> nameToSchema) {
        Map<String, Integer> indegree = new HashMap<>();
        for (String name : nameToSchema.keySet()) {
            indegree.put(name, 0);
        }
        for (SchemaFile schema : nameToSchema.values()) {
            for (String dep : schema.dependencies) {
                indegree.put(dep, indegree.get(dep) + 1);
            }
        }

        Queue<SchemaFile> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(nameToSchema.get(entry.getKey()));
            }
        }

        List<SchemaFile> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            SchemaFile schema = queue.poll();
            sorted.add(schema);

            for (String dep : schema.dependencies) {
                indegree.put(dep, indegree.get(dep) - 1);
                if (indegree.get(dep) == 0) {
                    queue.add(nameToSchema.get(dep));
                }
            }
        }

        if (sorted.size() != nameToSchema.size()) {
            throw new RuntimeException("Cyclic dependency detected or missing schema reference.");
        }

        // Reverse to put dependencies before dependents
        Collections.reverse(sorted);

        return sorted;
    }

    /**
     * Representation of one schema and its dependencies.
     */
    private static class SchemaFile {
        final String name;
        final File file;
        final JsonNode root;
        final Set<String> dependencies = new HashSet<>();

        SchemaFile(String name, File file, JsonNode root) {
            this.name = name;
            this.file = file;
            this.root = root;
        }
    }
}
