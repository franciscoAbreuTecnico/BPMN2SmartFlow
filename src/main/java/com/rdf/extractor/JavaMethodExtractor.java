package com.rdf.extractor;

import com.rdf.extractor.enums.PatternType;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashMap;

public class JavaMethodExtractor {
    
    private String[] targetDirectories;

    private ArrayList<String> targetPatterns = new ArrayList<String>() {{
        add("Flow.FLOW_PROCESSORS.put");
        add("Request.REQUEST_PROCESSORS.put");
        add("DocumentService.PAYLOAD_PROVIDERS.put");
    }};

    public HashMap<PatternType, HashMap<String, Location>> getResultsMap() {
        HashMap<PatternType, HashMap<String, Location>> results = new HashMap<>();

        for (String dir : targetDirectories) {
            Path rootPath = Paths.get(dir);
            for (String pattern : targetPatterns) {
                PatternType patternType = PatternType.fromString(pattern);
                if (patternType != null) {
                    processPattern(rootPath, pattern, patternType, results);
                }
            }
        }

        return results;
    }

    public void processPattern(Path rootPath, String searchPattern, PatternType patternType,
                           HashMap<PatternType, HashMap<String, Location>> resultMap) {
        try {
        Files.walk(rootPath)
             .filter(Files::isRegularFile)
             .filter(path -> {
                 String name = path.getFileName().toString();
                 return "RequestProcessors.java".equals(name)
                     || "PaperPusherISTServletContextListener.java".equals(name);
             })
             .forEach(path -> {
                 processFile(path, searchPattern, patternType, resultMap);
             });
        } catch (IOException e) {
            System.err.println("Error walking " + rootPath + ": " + e.getMessage());
        }
    }

    public void processFile(Path filePath, String searchPattern, PatternType patternType,
                            HashMap<PatternType, HashMap<String, Location>> resultMap) {
        try {
            ArrayList<String> lines = new ArrayList<>(Files.readAllLines(filePath, StandardCharsets.UTF_8));
            if (lines.stream().noneMatch(line -> line.contains(searchPattern))) return;

            String fileContent = Files.readString(filePath);
            JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
            CompilationUnit cu = parser.parse(fileContent).getResult().orElseThrow();

            cu.findAll(MethodCallExpr.class).forEach(call -> {
                if (call.getNameAsString().equals("put") &&
                        call.toString().contains(searchPattern) &&
                        !call.getArguments().isEmpty()) {

                    String key = call.getArgument(0).toString().replace("\"", "");
                    int line = call.getRange().map(r -> r.begin.line).orElse(-1);

                    HashMap<String, Location> keyMap = resultMap.computeIfAbsent(patternType, pt -> new HashMap<>());
                    if (!keyMap.containsKey(key)) {
                        keyMap.put(key, new Location(filePath, line));
                    }
                }
            });

        } catch (IOException e) {
            System.err.println("Error reading/parsing file: " + filePath);
        }
    }

    public JavaMethodExtractor(String[] targetDirectories) {
        this.targetDirectories = targetDirectories;
    }

    public class Location {
        public Path file;
        public int lineNumber;

        public Location(Path file, int lineNumber) {
            this.file = file;
            this.lineNumber = lineNumber;
        }

        @Override
        public String toString() {
            return "Location{" +
                    "file=" + file +
                    ", lineNumber=" + lineNumber +
                    '}';
        }

        public Path getFile() {
            return file;
        }
    }
}
