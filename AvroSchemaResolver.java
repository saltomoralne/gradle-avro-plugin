
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

public class AvroSchemaResolver {

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

        // Read .avsc files
        File[] files = folder.listFiles((dir, name) -> name.endsWith(AVSC_SUFFIX));
        if (files == null || files.length == 0) {
            System.err.println("No .avsc files found in folder");
            System.exit(1);
        }

        // Step 1: Parse schemas and collect type info
        Map<String, SchemaFile> nameToSchema = new HashMap<>();
        for (File file : files) {
            JsonNode root = mapper.readTree(file);
            String fullName = getFullName(root);
            nameToSchema.put(fullName, new SchemaFile(fullName, file, root));
        }

        // Step 2: Determine dependencies for each schema
        for (SchemaFile schema : nameToSchema.values()) {
            Set<String> deps = findDependencies(schema.root, nameToSchema.keySet());
            // Remove self-dependency if any (should not occur but just in case)
            deps.remove(schema.name);
            schema.dependencies.addAll(deps);
        }

        // Optional debug: print dependencies
        /*
        System.out.println("Dependencies:");
        for (SchemaFile schema : nameToSchema.values()) {
            System.out.println(schema.name + " depends on " + schema.dependencies);
        }
        */

        // Step 3: Topologically sort schemas so dependencies come first
        List<SchemaFile> sorted = topologicalSort(nameToSchema);

        // Step 4: Write files back with numbered prefixes
        int i = 1;
        for (SchemaFile schema : sorted) {
            String newName = String.format("%02d_%s", i++, schema.file.getName());
            File outputFile = new File(folder, newName);
            Files.copy(schema.file.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Output: " + outputFile.getName());
        }
    }

    /**
     * Build full name from schema: namespace + name or just name.
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

        // Some schemas may have names with dots, respect them as full names
        if (name.contains(".")) {
            // already qualified
            return name;
        }

        return namespace.isEmpty() ? name : namespace + "." + name;
    }

    /**
     * Recursively find all dependencies (type references) in the provided JSON node,
     * considering arrays, maps, unions, nested records, enums, fixed, etc.
     *
     * Only dependencies present in knownTypes (all schema full names) are included.
     */
    private static Set<String> findDependencies(JsonNode node, Set<String> knownTypes) {
        Set<String> deps = new HashSet<>();
        if (node == null) return deps;

        if (node.isObject()) {
            // Handle "type" field
            JsonNode typeNode = node.get("type");

            if (typeNode != null) {
                if (typeNode.isTextual()) {
                    // Primitive or named type
                    String typeName = typeNode.asText();
                    if (knownTypes.contains(typeName)) {
                        deps.add(typeName);
                    }
                    // else primitive or unknown, ignore
                } else if (typeNode.isArray()) {
                    // Union type: array of types
                    for (JsonNode subType : typeNode) {
                        deps.addAll(findDependencies(subType, knownTypes));
                    }
                } else if (typeNode.isObject()) {
                    // Complex types: array, map, record, fixed, enum, etc.
                    String complexType = typeNode.get("type").asText();
                    if ("array".equals(complexType)) {
                        deps.addAll(findDependencies(typeNode.get("items"), knownTypes));
                    } else if ("map".equals(complexType)) {
                        deps.addAll(findDependencies(typeNode.get("values"), knownTypes));
                    } else {
                        // Could be nested record, fixed, or enum inline declaration
                        deps.addAll(findDependencies(typeNode, knownTypes));
                    }
                }
            }

            // Additionally recurse all other fields to cover nested fields
            for (Iterator<String> it = node.fieldNames(); it.hasNext();) {
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
            // Here node itself is a textual type name?
            String typeName = node.asText();
            if (knownTypes.contains(typeName)) {
                deps.add(typeName);
            }
        }
        // Other JSON node types (number, boolean, null) do not affect dependencies

        return deps;
    }

    /**
     * Perform topological sort using Kahn's algorithm on schemas.
     */
    private static List<SchemaFile> topologicalSort(Map<String, SchemaFile> nameToSchema) throws RuntimeException {
        // Compute indegree for each node
        Map<String, Integer> indegree = new HashMap<>();
        for (String name : nameToSchema.keySet()) {
            indegree.put(name, 0);
        }
        for (SchemaFile schema : nameToSchema.values()) {
            for (String dep : schema.dependencies) {
                indegree.put(dep, indegree.get(dep) + 1);
            }
        }

        // Queue of schemas with zero indegree (no dependencies)
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

            // Decrease indegree of dependencies
            for (String dep : schema.dependencies) {
                indegree.put(dep, indegree.get(dep) - 1);
                if (indegree.get(dep) == 0) {
                    queue.add(nameToSchema.get(dep));
                }
            }
        }

        if (sorted.size() != nameToSchema.size()) {
            throw new RuntimeException("Cyclic dependency detected or missing schema references.");
        }

        // The output order is: no dependencies first, then ones that depend on them.
        // But we want dependencies first and dependents later.
        // The algorithm as is places dependencies last. Reverse to get dependencies first.
        Collections.reverse(sorted);

        return sorted;
    }

    /**
     * Helper class representing one schema file and its dependencies.
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
