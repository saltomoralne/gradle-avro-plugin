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
        if (args.length < 1) {
            System.err.println("Usage: java AvroSchemaSorter <folder1> [folder2 folder3 ...]");
            System.exit(1);
        }

        // Collect all .avsc files from all input directories
        List<File> schemaFiles = new ArrayList<>();
        for (String folderPath : args) {
            File folder = new File(folderPath);
            if (!folder.isDirectory()) {
                System.err.println("Warning: " + folderPath + " is not a directory. Skipping.");
                continue;
            }
            File[] files = folder.listFiles((dir, name) -> name.endsWith(AVSC_SUFFIX));
            if (files != null) {
                schemaFiles.addAll(Arrays.asList(files));
            }
        }

        if (schemaFiles.isEmpty()) {
            System.err.println("No .avsc files found in given directories.");
            System.exit(1);
        }

        // Step 1: Parse all schemas (multiple dirs supported)
        Map<String, SchemaFile> nameToSchema = new HashMap<>();
        for (File file : schemaFiles) {
            JsonNode root = mapper.readTree(file);
            if (root.isArray()) {
                for (JsonNode item : root) {
                    String fullName = getFullName(item);
                    if (fullName == null) {
                        throw new IllegalArgumentException("Schema in file " + file.getName() + " missing name");
                    }
                    nameToSchema.put(fullName, new SchemaFile(fullName, file, item));
                }
            } else if (root.isObject()) {
                String fullName = getFullName(root);
                if (fullName == null) {
                    throw new IllegalArgumentException("Schema in file " + file.getName() + " missing name");
                }
                nameToSchema.put(fullName, new SchemaFile(fullName, file, root));
            } else {
                throw new IllegalArgumentException("Schema in file " + file.getName() + " is neither an object nor array");
            }
        }

        // Step 2: Determine dependencies
        for (SchemaFile schema : nameToSchema.values()) {
            String currentNamespace = getNamespace(schema.root);
            Set<String> deps = findDependencies(schema.root, nameToSchema.keySet(), currentNamespace);
            deps.remove(schema.name); // Remove self
            schema.dependencies.addAll(deps);
        }

        // Step 3: Topological sort
        List<SchemaFile> sortedSchemas = topologicalSort(nameToSchema);

        // Step 4: Output files to the first input folder given (can be changed)
        File outputFolder = new File(args[0]);
        if (!outputFolder.isDirectory()) {
            System.err.println("First input argument is not a directory. Cannot output files.");
            System.exit(1);
        }

        ObjectWriter writer = mapper.writerWithDefaultPrettyPrinter();
        int index = 1;

        for (SchemaFile schema : sortedSchemas) {
            String safeName = schema.name.replace('.', '_') + AVSC_SUFFIX;
            String outputFileName = String.format("%02d_%s", index++, safeName);
            File outputFile = new File(outputFolder, outputFileName);

            writer.writeValue(outputFile, schema.root);
            System.out.println("Output: " + outputFile.getAbsolutePath());
        }
    }

    private static String getFullName(JsonNode root) {
        if (!root.has("name")) return null;
        String name = root.get("name").asText();
        String namespace = getNamespace(root);
        if (name.contains(".")) return name;
        return namespace.isEmpty() ? name : namespace + "." + name;
    }

    private static String getNamespace(JsonNode node) {
        return (node.has("namespace") && node.get("namespace").isTextual())
                ? node.get("namespace").asText()
                : "";
    }

    private static Set<String> findDependencies(JsonNode node, Set<String> knownTypes, String currentNamespace) {
        Set<String> deps = new HashSet<>();
        if (node == null) return deps;

        if (node.isObject()) {
            JsonNode typeNode = node.get("type");

            if (typeNode != null) {
                if (typeNode.isTextual()) {
                    String typeName = typeNode.asText();
                    String fullTypeName = resolveFullName(typeName, currentNamespace);
                    if (knownTypes.contains(fullTypeName)) deps.add(fullTypeName);
                } else if (typeNode.isArray()) {
                    for (JsonNode subtype : typeNode) {
                        deps.addAll(findDependencies(subtype, knownTypes, currentNamespace));
                    }
                } else if (typeNode.isObject()) {
                    String cType = typeNode.has("type") ? typeNode.get("type").asText() : null;
                    if ("array".equals(cType)) {
                        deps.addAll(findDependencies(typeNode.get("items"), knownTypes, currentNamespace));
                    } else if ("map".equals(cType)) {
                        deps.addAll(findDependencies(typeNode.get("values"), knownTypes, currentNamespace));
                    } else {
                        deps.addAll(findDependencies(typeNode, knownTypes, currentNamespace));
                    }
                }
            }

            Iterator<String> fields = node.fieldNames();
            while (fields.hasNext()) {
                String f = fields.next();
                if (!"type".equals(f)) {
                    deps.addAll(findDependencies(node.get(f), knownTypes, currentNamespace));
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) deps.addAll(findDependencies(item, knownTypes, currentNamespace));
        } else if (node.isTextual()) {
            String typeName = node.asText();
            String fullTypeName = resolveFullName(typeName, currentNamespace);
            if (knownTypes.contains(fullTypeName)) deps.add(fullTypeName);
        }
        return deps;
    }

    private static String resolveFullName(String typeName, String currentNamespace) {
        if (typeName.contains(".")) return typeName;
        return currentNamespace.isEmpty() ? typeName : currentNamespace + "." + typeName;
    }

    private static List<SchemaFile> topologicalSort(Map<String, SchemaFile> schemas) {
        Map<String, Integer> indegree = new HashMap<>();
        for (String key : schemas.keySet()) indegree.put(key, 0);
        for (SchemaFile sf : schemas.values()) {
            for (String d : sf.dependencies) {
                indegree.put(d, indegree.get(d) + 1);
            }
        }

        Queue<SchemaFile> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> e : indegree.entrySet()) {
            if (e.getValue() == 0) queue.add(schemas.get(e.getKey()));
        }

        List<SchemaFile> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            SchemaFile sf = queue.poll();
            sorted.add(sf);
            for (String d : sf.dependencies) {
                indegree.put(d, indegree.get(d) - 1);
                if (indegree.get(d) == 0) queue.add(schemas.get(d));
            }
        }

        if (sorted.size() != schemas.size()) {
            throw new RuntimeException("Cyclic dependencies or missing references detected.");
        }

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
