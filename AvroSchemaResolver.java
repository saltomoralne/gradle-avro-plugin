package org.example;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import java.io.File;
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

        File[] files = folder.listFiles((dir, name) -> name.endsWith(AVSC_SUFFIX));
        if (files == null || files.length == 0) {
            System.err.println("No .avsc files found in folder");
            System.exit(1);
        }

        // Step 1: Parse schemas and collect each schema separately
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

        // Step 2: Determine dependencies for each schema (resolve short names relative to namespace)
        for (SchemaFile schema : nameToSchema.values()) {
            String currentNamespace = getNamespace(schema.root);
            Set<String> deps = findDependencies(schema.root, nameToSchema.keySet(), currentNamespace);
            deps.remove(schema.name); // Remove self-dependency if any
            schema.dependencies.addAll(deps);
        }

        // Step 3: Topological sort to order dependencies first
        List<SchemaFile> sortedSchemas = topologicalSort(nameToSchema);

        // Step 4: Write output schemas separately to files with numbered prefix and schema full name
        ObjectWriter writer = mapper.writerWithDefaultPrettyPrinter();
        int index = 1;

        for (SchemaFile schema : sortedSchemas) {
            // Replace dots with underscores for safe filename
            String safeName = schema.name.replace('.', '_') + AVSC_SUFFIX;
            String outputFileName = String.format("%02d_%s", index++, safeName);
            File outputFile = new File(folder, outputFileName);

            writer.writeValue(outputFile, schema.root);

            System.out.println("Output: " + outputFile.getName());
        }
    }

    /**
     * Extract full name (namespace + name) of the schema node.
     * Returns null if "name" is missing.
     */
    private static String getFullName(JsonNode root) {
        if (!root.has("name")) {
            return null;
        }
        String name = root.get("name").asText();
        String namespace = getNamespace(root);

        if (name.contains(".")) {
            // Already qualified
            return name;
        }
        return namespace.isEmpty() ? name : namespace + "." + name;
    }

    /**
     * Helper to get the namespace string or empty if not present.
     */
    private static String getNamespace(JsonNode node) {
        if (node.has("namespace") && node.get("namespace").isTextual()) {
            return node.get("namespace").asText();
        }
        return "";
    }

    /**
     * Recursively find all schema dependencies in the node.
     * Resolves short type names relative to the given current namespace.
     * Only returns dependencies that exist in knownTypes.
     */
    private static Set<String> findDependencies(JsonNode node, Set<String> knownTypes, String currentNamespace) {
        Set<String> deps = new HashSet<>();
        if (node == null) return deps;

        if (node.isObject()) {
            JsonNode typeNode = node.get("type");

            if (typeNode != null) {
                if (typeNode.isTextual()) {
                    String typeName = typeNode.asText();
                    String fullTypeName = resolveFullName(typeName, currentNamespace);
                    if (knownTypes.contains(fullTypeName)) {
                        deps.add(fullTypeName);
                    }
                    // else primitive or unknown, ignore
                } else if (typeNode.isArray()) {
                    // Union type - array of types
                    for (JsonNode subtype : typeNode) {
                        deps.addAll(findDependencies(subtype, knownTypes, currentNamespace));
                    }
                } else if (typeNode.isObject()) {
                    String complexType = typeNode.has("type") ? typeNode.get("type").asText() : null;
                    if ("array".equals(complexType)) {
                        deps.addAll(findDependencies(typeNode.get("items"), knownTypes, currentNamespace));
                    } else if ("map".equals(complexType)) {
                        deps.addAll(findDependencies(typeNode.get("values"), knownTypes, currentNamespace));
                    } else {
                        // Inline nested complex types (record, enum, fixed)
                        deps.addAll(findDependencies(typeNode, knownTypes, currentNamespace));
                    }
                }
            }

            // Recurse into other fields too (e.g., fields array)
            Iterator<String> fieldsIter = node.fieldNames();
            while (fieldsIter.hasNext()) {
                String field = fieldsIter.next();
                if (!"type".equals(field)) {
                    deps.addAll(findDependencies(node.get(field), knownTypes, currentNamespace));
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                deps.addAll(findDependencies(item, knownTypes, currentNamespace));
            }
        } else if (node.isTextual()) {
            // Node itself is a type name string
            String typeName = node.asText();
            String fullTypeName = resolveFullName(typeName, currentNamespace);
            if (knownTypes.contains(fullTypeName)) {
                deps.add(fullTypeName);
            }
        }
        // others (number, boolean, null) are ignored

        return deps;
    }

    /**
     * Resolve a possibly short type name (without dot) relative to current namespace,
     * or return as is if already fully qualified.
     */
    private static String resolveFullName(String typeName, String currentNamespace) {
        if (typeName.contains(".")) {
            return typeName;
        }
        return currentNamespace.isEmpty() ? typeName : currentNamespace + "." + typeName;
    }

    /**
     * Perform topological sorting of schemas by dependencies using Kahn's algorithm.
     * Throws RuntimeException on cyclic dependencies.
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

        // Reverse so dependencies appear first, dependents later
        Collections.reverse(sorted);
        return sorted;
    }

    private static class SchemaFile {
        final String name;
        final File sourceFile;
        final JsonNode root;
        final Set<String> dependencies = new HashSet<>();

        SchemaFile(String name, File sourceFile, JsonNode root) {
            this.name = name;
            this.sourceFile = sourceFile;
            this.root = root;
        }
    }
}
