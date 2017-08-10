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

    private String projectPath; // path of Understand database
    private String workspacePath;
    private BuildListener listener;

    public DependencyAnalysis(String projectPath, String workspacePath, BuildListener listener) {
        this.projectPath = projectPath;
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
        setLibPath();

        ArrayList<String> dependentModules = new ArrayList<>();

        // if the Understand Database does not already exist, create a new Understand Database
        File file = new File(projectPath);
        if (!file.exists()) {
            String command = "und create -db " + projectPath + " -languages java add "
                             + workspacePath + " analyze -all";

            listener.getLogger().println("Understand Database does not exist...");
            listener.getLogger().println("Attempting to create and analyze new database...");

            Process createDatabase = Runtime.getRuntime().exec(command);
            createDatabase.waitFor();
        }

        try {
            Database db = Understand.open(projectPath);

            // entsWeCareAbout are files that are in the project; this prevents the program from looking at
            // references to basic java classes (i.e. java.lang.*, etc)
            Entity[] files = db.ents("file");
            ArrayList<String> entsWeCareAbout = getProjFileNamesWithoutExtension(files);

            Entity[] classes = db.ents("class");
            TreeMap<String, Entity> classTree = getClassTree(classes);

            for (String module : changedModules) {
                if (!dependentModules.contains(module)) {
                    dependentModules.add(module);
                    getReferences(module, classTree, entsWeCareAbout, dependentModules);
                }
            }

            db.close();
        } catch (UnderstandException exception) {
            System.out.println("Failed opening Database:" + exception.getMessage());
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
        Entity c = classTree.get(targetClass);
        Reference[] refs = c.refs(null, "class", true);
        for (Reference ref : refs) {
            String entityName = ref.ent().name();
            if (entsWeCareAbout.contains(entityName) && !dependencies.contains(entityName))
                dependencies.add(entityName);
        }

    }

    /**
     * Gets a TreeMap of class Entity objects
     * @param classes An array of Entity objects containing all classes in project
     * @return TreeMap with: Keys = Class name; values = Entity objects from database
     */
    private TreeMap<String, Entity> getClassTree(Entity[] classes) {
        TreeMap<String, Entity> classTree = new TreeMap<>();

        for (Entity c : classes) classTree.put(c.name(), c);

        return classTree;
    }

    /**
     * @param files Array of Entity objects containing all files in project
     * @return a list of the java source files from the project without the .java extension
     */
    private ArrayList<String> getProjFileNamesWithoutExtension(Entity[] files) {
        ArrayList<String> returnThis = new ArrayList<>();
        for (Entity file : files) {
            if (file.name().contains(".java")) {
                String name = file.name().replace(".java", "");
                returnThis.add(name);
            }
        }
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