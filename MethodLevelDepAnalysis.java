package org.jenkinsci.plugins.automatedTestSelector;

import com.scitools.understand.*;

import java.io.*;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.TreeMap;

/**
 * Class for utilizing SciTools Understand database for static code analysis;
 * This class will allow the test selector to only choose tests relevant to changed files.
 */
public class MethodLevelDepAnalysis {
    private static String udbPath; // path of Understand database
    private static String workspacePath;
    private static String changedFile;
    private static String libPath;

    private static Database db;

    /**
     * Main function for class
     *
     * @return List of all files in project related to the changed source files
     */
    public static void main(String[] args)
            throws IOException, InterruptedException {
        if (args.length != 3) {
            System.out.println("Number of arguments is not correct.");
            System.out.println("Format: 'java DependencyAnalysis <udbPath> <workspacePath> <file>'");
            return;
        }

        udbPath = args[0];
        workspacePath = args[1];
        changedFile = args[2];

//        ArrayList<String> changedModules = getChangedModules();

        try {
            db = Understand.open(udbPath);

            Entity[] files = db.ents("file");
            ArrayList<String> entsWeCareAbout = getProjFileNamesWithoutExtension(files);

            Entity[] methods = db.ents("method");

            TreeMap<String, Entity> methodTree = getMethodTree(methods);

        } catch (UnderstandException e) {
            System.err.println(e.getMessage());
        }
    }

    private static ArrayList<String> getProjFileNamesWithoutExtension(Entity[] files) {
        // System.out.println("Inside getProjFileNamesWithoutExtension..."); // <-- for debugging
        ArrayList<String> returnThis = new ArrayList<>();
        for (Entity file : files) {
            // System.out.println("Checking " + file.name() + "...");
            if (file.name().contains(".java")) {
                // System.out.println(file.name() + " contains '.java'..."); // <-- for debugging
                String name = file.name().replace(".java", "");
                // System.out.println("Adding " + name + "...");
                returnThis.add(name);
            }
        }
        // System.out.println("Returning from getProjFileNamesWithoutExtension..."); // <-- for debugging
        return returnThis;
    }

    private static TreeMap<String, Entity> getMethodTree(Entity[] methods) {
        TreeMap<String, Entity> methodTree = new TreeMap<>();

        for (Entity m : methods) {
            methodTree.put(m.name(), m);
        }

        return methodTree;
    }

    private static ArrayList<String> getChangedModules() {
        ArrayList<String> changedModules = new ArrayList<>();

        try {
            FileReader fileReader = new FileReader(changedFile);
            BufferedReader bufferedReader = new BufferedReader(fileReader);

            String line;
            while ((line = bufferedReader.readLine()) != null)
                changedModules.add(line);

            bufferedReader.close();
            fileReader.close();
        } catch (IOException exception) {
            System.out.println(exception.getMessage());
        }

        return changedModules;
    }
}