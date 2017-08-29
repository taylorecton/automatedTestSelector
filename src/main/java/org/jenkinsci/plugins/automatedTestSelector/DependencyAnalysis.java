package org.jenkinsci.plugins.automatedTestSelector;

import com.scitools.understand.*;
import hudson.model.BuildListener;

import java.io.*;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.TreeMap;

/**
 * Class for utilizing SciTools Understand database for static code analysis;
 * This class will allow the test selector to only choose tests relevant to changed files.
 */
public class DependencyAnalysis {
    // Location of .jnlib file on my machine
    private static final String LIB_PATH = "/Applications/Understand.app/Contents/MacOS/Java";

    private String udbPath; // path of Understand database
    private String workspacePath;
    private BuildListener listener;

    private static Database db = null;

    public DependencyAnalysis(String udbPath, String workspacePath, BuildListener listener) {
        setLibPath();

        this.udbPath = udbPath;
        this.workspacePath = workspacePath;
        this.listener = listener;
    }

    /**
     * Main function for class
     * @param changedModules List of files that have been changed in version control
     * @return List of all files in project related to the changed source files
     */
    public ArrayList<String> getDependentModules (ArrayList<String> changedModules)
            throws IOException, InterruptedException {
        ArrayList<String> dependentModules = new ArrayList<>();

        File file = new File(udbPath);

        if (!file.exists()) {
            // if the Understand Database does not already exist, create a new Understand Database
            String command = "und create -db " + udbPath + " -languages java add "
                             + workspacePath + " analyze -all";

            listener.getLogger().println("Understand Database does not exist...");
            listener.getLogger().println("Attempting to create and analyze new database...");

            Process createDatabase = Runtime.getRuntime().exec(command);
            createDatabase.waitFor();
            listener.getLogger().println(udbPath + " created.");
        } else {
            // scan for changed/added files and analyze files that have changed
            String command = "und -db " + udbPath + " analyze -rescan -changed";

            listener.getLogger().println("Re-scanning Understand Database...");

            Process analyzeDatabase = Runtime.getRuntime().exec(command);
            analyzeDatabase.waitFor();
            listener.getLogger().println(udbPath + " successfully re-scanned for changes.");
        }

        listener.getLogger().println(udbPath + " exists? = " + file.exists());
        listener.getLogger().println(udbPath + " readable? = " + file.canRead());
        listener.getLogger().println(udbPath + " writable? = " + file.canWrite());

        try {
            listener.getLogger().println("Opening database: " + udbPath + " ..."); // <-- for debugging

            db = Understand.open(udbPath);

            listener.getLogger().println("Database opened..."); // <-- for debugging

            // entsWeCareAbout are files that are in the project; this prevents the program from looking at
            // references to basic java classes (i.e. java.lang.*, etc)
            Entity[] files = db.ents("file");
            ArrayList<String> entsWeCareAbout = getProjFileNamesWithoutExtension(files);

            for (String fileName : entsWeCareAbout) {              //
                listener.getLogger().println("File: " + fileName); // <-- for debugging
            }                                                      //

            Entity[] classes = db.ents("class");

            for (Entity c : classes) {                              //
                listener.getLogger().println("Class: " + c.name()); // <-- for debugging
            }                                                       //

            TreeMap<String, Entity> classTree = getClassTree(classes);

            for (String module : changedModules) {
                if (!dependentModules.contains(module)) {
                    listener.getLogger().println("Adding " + module + " to dependentModules..."); // <-- for debugging
                    dependentModules.add(module);
                    getReferences(module, classTree, entsWeCareAbout, dependentModules);
                }
            }
        } catch (UnderstandException exception) {
            listener.getLogger().println("Failed opening Database:" + exception.getMessage());
        } finally {
            try {
                if (db != null) {
                    db.close();
                    listener.getLogger().println("Database closed.");
                }
            } catch (Exception e) {
                listener.getLogger().println(e.getMessage());
            }
        }

        return dependentModules;
    }

    /**
     * Get all dependencies for a class
     * @param targetClass Class you want dependencies for
     * @param classTree TreeMap containing database Entity objects for quick access
     * @param entsWeCareAbout Entities we want to consider
     * @param dependencies All of the dependent modules visited so far
     */
    private void getReferences(String targetClass,
                               TreeMap<String, Entity> classTree,
                               ArrayList<String> entsWeCareAbout,
                               ArrayList<String> dependencies) {
        listener.getLogger().println("Inside getReferences..."); // <-- for debugging
        Entity c = classTree.get(targetClass);
        Reference[] refs = c.refs(null, "class", true);
        listener.getLogger().println("Iterating through references..."); // <-- for debugging
        for (Reference ref : refs) {
            String entityName = ref.ent().name();
            listener.getLogger().println("Checking reference: " + entityName + "..."); // <-- for debugging
            if (entsWeCareAbout.contains(entityName) && !dependencies.contains(entityName)) {
                listener.getLogger().println("Adding " + entityName + "..."); // <-- for debugging
                dependencies.add(entityName);
            }
        }
        listener.getLogger().println("getReferences() finishing...");
    }

    /**
     * Gets a TreeMap of class Entity objects
     * @param classes An array of Entity objects containing all classes in project
     * @return TreeMap with: Keys = Class name; values = Entity objects from database
     */
    private TreeMap<String, Entity> getClassTree(Entity[] classes) {
        listener.getLogger().println("Inside getClassTree()..."); // <-- for debugging

        TreeMap<String, Entity> classTree = new TreeMap<>();

        for (Entity c : classes) classTree.put(c.name(), c);

        listener.getLogger().println("Returning from getClassTree()..."); // <-- for debugging
        return classTree;
    }

    /**
     * @param files Array of Entity objects containing all files in project
     * @return a list of the java source files from the project without the .java extension
     */
    private ArrayList<String> getProjFileNamesWithoutExtension(Entity[] files) {
        listener.getLogger().println("Inside getProjFileNamesWithoutExtension..."); // <-- for debugging
        ArrayList<String> returnThis = new ArrayList<>();
        for (Entity file : files) {
            listener.getLogger().println("Checking " + file.name() + "...");
            if (file.name().contains(".java")) {
                listener.getLogger().println(file.name() + " contains '.java'..."); // <-- for debugging
                String name = file.name().replace(".java", "");
                listener.getLogger().println("Adding " + name + "...");
                returnThis.add(name);
            }
        }
        listener.getLogger().println("Returning from getProjFileNamesWithoutExtension..."); // <-- for debugging
        return returnThis;
    }

    // necessary for making Understand API work
    private void setLibPath() {
        System.setProperty("java.library.path", LIB_PATH);
        try {
            Field fieldSysPath = ClassLoader.class.getDeclaredField("sys_paths");
            fieldSysPath.setAccessible(true);
            fieldSysPath.set(null, null);
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
    }
}