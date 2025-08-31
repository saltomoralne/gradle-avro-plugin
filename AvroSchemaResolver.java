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



/*
repositories {
    mavenCentral()
}

dependencies {
    implementation 'com.github.davidmc24.gradle.plugin:gradle-avro-plugin:1.9.1'
    implementation 'org.apache.avro:avro:1.11.0'
    implementation 'org.apache.commons:commons-collections4:4.4'
    implementation 'org.slf4j:slf4j-api:1.7.30'
    implementation 'org.slf4j:slf4j-simple:1.7.30'
}

////////////////////////////
package com.example

import com.github.davidmc24.gradle.plugin.avro.ProcessingState
import com.github.davidmc24.gradle.plugin.avro.FileState
import com.github.davidmc24.gradle.plugin.avro.MapUtils
import org.apache.avro.Schema
import org.apache.avro.SchemaParseException
//import org.apache.commons.collections4.MapUtils
import org.gradle.api.GradleException

import java.util.regex.Matcher
import java.util.regex.Pattern
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException

class ProcessSchemaInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(ProcessSchemaInterceptor)
    private static final Pattern ERROR_UNKNOWN_TYPE = Pattern.compile(".*Undefined name:.*")
    private static final Pattern ERROR_DUPLICATE_TYPE = Pattern.compile(".*Found duplicate type: (.*)")

    static void processSchemaFile(ProcessingState processingState, FileState fileState) {
        String path = fileState.getPath()
        logger.debug("Processing {}, excluding types {}", path, fileState.getDuplicateTypeNames())
        File sourceFile = fileState.getFile()
        Map<String, Schema> parserTypes = processingState.determineParserTypes(fileState)
        try {


            org.apache.avro.Schema$Parser parser = new org.apache.avro.Schema$Parser()
            parser.addTypes(parserTypes)
            parser.parse(sourceFile)
            Map<String, Schema> typesDefinedInFile = MapUtils.asymmetricDifference(parser.getTypes(), parserTypes)
            processingState.processTypeDefinitions(fileState, typesDefinedInFile)
            if (logger.isDebugEnabled()) {
                logger.debug("Processed {}; contained types {}", path, typesDefinedInFile.keySet())
            } else {
                logger.info("Processed {}", path)
            }
        } catch (SchemaParseException ex) {
            String errorMessage = ex.getMessage()
            Matcher unknownTypeMatcher = ERROR_UNKNOWN_TYPE.matcher(errorMessage)
            Matcher duplicateTypeMatcher = ERROR_DUPLICATE_TYPE.matcher(errorMessage)
            if (unknownTypeMatcher.matches()) {
                fileState.setError(ex)
                processingState.queueForDelayedProcessing(fileState)
                logger.debug("Found undefined name in {} ({}); will try again", path, errorMessage)
            } else if (duplicateTypeMatcher.matches()) {
                String typeName = duplicateTypeMatcher.group(1)
                if (fileState.containsDuplicateTypeName(typeName)) {
                    throw new GradleException(
                            String.format("Failed to resolve schema definition file %s; contains duplicate type definition %s", path, typeName),
                            ex)
                } else {
                    fileState.setError(ex)
                    fileState.addDuplicateTypeName(typeName)
                    processingState.queueForProcessing(fileState)
                    logger.debug("Identified duplicate type {} in {}; will re-process excluding it", typeName, path)
                }
            } else {
                throw new GradleException(String.format("Failed to resolve schema definition file %s", path), ex)
            }
        } catch (IOException ex) {
            throw new GradleException(String.format("Failed to resolve schema definition file %s", path), ex)
        }
    }
}
//////////////////////////////////




import net.bytebuddy.ByteBuddy
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.asm.Advice
import net.bytebuddy.dynamic.loading.ClassReloadingStrategy
import net.bytebuddy.matcher.ElementMatchers
import net.bytebuddy.implementation.MethodDelegation

import com.github.davidmc24.gradle.plugin.avro.SchemaResolver

import com.example.ProcessSchemaInterceptor

 task replaceProcessSchemaFile {
    doFirst {
        println "Installing ByteBuddy agent and redefining processSchemaFile..."

        ByteBuddyAgent.install()

        new ByteBuddy()
                .redefine(SchemaResolver)
                .method(ElementMatchers.named("processSchemaFile"))
                .intercept(MethodDelegation.to(ProcessSchemaInterceptor.class))
                .make()
                .load(com.github.davidmc24.gradle.plugin.avro.SchemaResolver.getClassLoader(), ClassReloadingStrategy.fromInstalledAgent())

        println "Method processSchemaFile replaced successfully."
    }
}



 tasks.named('generateAvroSchemas') {
    dependsOn replaceProcessSchemaFile
}



*/







/*

import com.github.davidmc24.gradle.plugin.avro.GenerateAvroJavaTask


task generateAvroSchemas(type: GenerateAvroJavaTask) {
    source("src/main/avro")
    outputDir = file("$buildDir/generated-avro-schemas")
}


import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.ByteBuddy
import net.bytebuddy.matcher.ElementMatchers
import net.bytebuddy.dynamic.loading.ClassReloadingStrategy
import net.bytebuddy.implementation.MethodDelegation

task redefineSchemaResolver {

    doLast {
        def gradleClassLoader = this.class.classLoader
        def interceptorClass = gradleClassLoader.loadClass('com.example.bytebuddy.SchemaResolverInterceptor')

        ByteBuddyAgent.install()
        def load = new ByteBuddy()
                .redefine(Class.forName('com.github.davidmc24.gradle.plugin.avro.SchemaResolver'))
                .method(ElementMatchers.named('processSchemaFile').and(ElementMatchers.takesArguments(2)))
                .intercept(MethodDelegation.to(interceptorClass))
                .make()
                .load(gradleClassLoader, ClassReloadingStrategy.fromInstalledAgent())
        println "SchemaResolver.processSchemaFile method redefined with custom implementation"
    }
}

tasks.named('generateAvroSchemas') {
    dependsOn redefineSchemaResolver
}






plugins {
    id 'java'
    id 'groovy'
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'org.codehaus.groovy:groovy-all:3.0.22'
    implementation 'com.github.davidmc24.gradle.plugin:gradle-avro-plugin:1.9.1'
    implementation 'org.apache.avro:avro:1.12.0'
    implementation 'org.apache.commons:commons-collections4:4.4'
    implementation 'org.slf4j:slf4j-api:1.7.30'
    implementation 'org.slf4j:slf4j-simple:1.7.30'
}

*/

