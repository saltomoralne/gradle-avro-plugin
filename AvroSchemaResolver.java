import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import java.io.File;
import java.io.IOException;
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

        // Step 1: Parse schemas and collect type info - split all schemas
        Map<String, SchemaFile> nameToSchema = new HashMap<>();

        for (File file : files) {
            JsonNode root = mapper.readTree(file);

            if (root.isArray()) {
                // Multiple schemas in one file (array root)
                for (JsonNode item : root) {
                    String fullName = getFullName(item);
                    if (fullName == null) {
                        throw new IllegalArgumentException("Schema in file " + file.getName() + " missing name");
                    }
                    // Each schema knows its original file (for info, but writing separately)
                    nameToSchema.put(fullName, new SchemaFile(fullName, file, item));
                }
            } else if (root.isObject()) {
                // Single schema per file
                String fullName = getFullName(root);
                if (fullName == null) {
                    throw new IllegalArgumentException("Schema in file " + file.getName() + " missing name");
                }
                nameToSchema.put(fullName, new SchemaFile(fullName, file, root));
            } else {
                throw new IllegalArgumentException("Schema in file " + file.getName() + " is neither an object nor array");
            }
        }

        // Step 2: Detect dependencies per schema
        for (SchemaFile schema : nameToSchema.values()) {
            Set<String> deps = findDependencies(schema.root, nameToSchema.keySet());
            deps.remove(schema.name); // Remove self if present
            schema.dependencies.addAll(deps);
        }

        // Step 3: Topological sort to order dependencies first
        List<SchemaFile> sortedSchemas = topologicalSort(nameToSchema);

        // Step 4: Write output schemas per schema (not per original file)
        ObjectWriter writer = mapper.writerWithDefaultPrettyPrinter();
        int index = 1;

        for (SchemaFile schema : sortedSchemas) {
            // Replace dots with underscores in filename to avoid filesystem issues
            String safeName = schema.name.replace('.', '_') + AVSC_SUFFIX;
            String outputFileName = String.format("%02d_%s", index++, safeName);
            File outputFile = new File(folder, outputFileName);

            // Write the schema JSON node prettily to this file
            writer.writeValue(outputFile, schema.root);

            System.out.println("Output: " + outputFile.getName());
        }
    }

    /**
     * Get the fully qualified name from the schema JSON node.
     * Supports "namespace" + "name" or just "name".
     * Returns null if no "name" found.
     */
    private static String getFullName(JsonNode schemaNode) {
        if (!schemaNode.has("name")) {
            return null;
        }
        String name = schemaNode.get("name").asText();
        String namespace = "";

        if (schemaNode.has("namespace")) {
            JsonNode nsNode = schemaNode.get("namespace");
            if (nsNode.isTextual()) {
                namespace = nsNode.asText();
            }
        }
        // If name contains dot (already qualified), use as is
        if (name.contains(".")) {
            return name;
        }
        return namespace.isEmpty() ? name : namespace + "." + name;
    }

    /**
     * Recursively find dependencies of a schema JSON node.
     * Only dependencies present in knownTypes are returned.
     */
    private static Set<String> findDependencies(JsonNode node, Set<String> knownTypes) {
        Set<String> dependencies = new HashSet<>();
        if (node == null) {
            return dependencies;
        }

        if (node.isObject()) {
            JsonNode typeNode = node.get("type");

            if (typeNode != null) {
                if (typeNode.isTextual()) {
                    String typeName = typeNode.asText();
                    if (knownTypes.contains(typeName)) {
                        dependencies.add(typeName);
                    }
                    // else primitive or unknown: ignore
                } else if (typeNode.isArray()) {
                    // Union types - array of types
                    for (JsonNode subtype : typeNode) {
                        dependencies.addAll(findDependencies(subtype, knownTypes));
                    }
                } else if (typeNode.isObject()) {
                    // Complex types such as array, map, nested record etc.
                    String complexType = typeNode.has("type") ? typeNode.get("type").asText() : null;
                    if ("array".equals(complexType)) {
                        dependencies.addAll(findDependencies(typeNode.get("items"), knownTypes));
                    } else if ("map".equals(complexType)) {
                        dependencies.addAll(findDependencies(typeNode.get("values"), knownTypes));
                    } else {
                        // Possibly nested inline declared record, enum, fixed
                        dependencies.addAll(findDependencies(typeNode, knownTypes));
                    }
                }
            }

            // Recurse all other fields, common is "fields"
            Iterator<String> fields = node.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                if (!"type".equals(field)) {
                    dependencies.addAll(findDependencies(node.get(field), knownTypes));
                }
            }

        } else if (node.isArray()) {
            for (JsonNode item : node) {
                dependencies.addAll(findDependencies(item, knownTypes));
            }
        } else if (node.isTextual()) {
            // The node itself is a type name string
            String typeName = node.asText();
            if (knownTypes.contains(typeName)) {
                dependencies.add(typeName);
            }
        }
        // other node types (number, boolean, null) do not generate dependencies

        return dependencies;
    }

    /**
     * Topological sort of the schemas using Kahn's algorithm.
     * Throws RuntimeException if cycle detected.
     */
    private static List<SchemaFile> topologicalSort(Map<String, SchemaFile> nameToSchema) {
        Map<String, Integer> indegrees = new HashMap<>();
        for (String name : nameToSchema.keySet()) {
            indegrees.put(name, 0);
        }
        for (SchemaFile schema : nameToSchema.values()) {
            for (String dep : schema.dependencies) {
                indegrees.put(dep, indegrees.get(dep) + 1);
            }
        }

        Queue<SchemaFile> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : indegrees.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(nameToSchema.get(entry.getKey()));
            }
        }

        List<SchemaFile> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            SchemaFile sf = queue.poll();
            sorted.add(sf);

            for (String dep : sf.dependencies) {
                indegrees.put(dep, indegrees.get(dep) - 1);
                if (indegrees.get(dep) == 0) {
                    queue.add(nameToSchema.get(dep));
                }
            }
        }

        if (sorted.size() != nameToSchema.size()) {
            throw new RuntimeException("Cyclic dependency detected or missing schema reference.");
        }

        // Reverse to have dependencies first, dependents after
        Collections.reverse(sorted);
        return sorted;
    }

    /**
     * Internal data class representing a schema and its info.
     */
    private static class SchemaFile {
        final String name; // full name (namespace.name)
        final File sourceFile; // original file (for info)
        final JsonNode root; // JSON schema node
        final Set<String> dependencies = new HashSet<>();

        SchemaFile(String name, File sourceFile, JsonNode root) {
            this.name = name;
            this.sourceFile = sourceFile;
            this.root = root;
        }
    }
}
