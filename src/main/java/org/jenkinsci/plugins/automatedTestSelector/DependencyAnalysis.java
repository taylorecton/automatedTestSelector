package org.jenkinsci.plugins.automatedTestSelector;

import com.scitools.understand.*;
import java.util.ArrayList;
import java.lang.reflect.Field;
import java.util.TreeMap;

/**
 * Class for utilizing SciTools Understand database for static code analysis;
 * This class will allow the test selector to only choose tests relevant to changed files.
 */
public class DependencyAnalysis {
    // Location of .jnlib file on my machine
    private static final String LIB_PATH = "/Applications/Understand.app/Contents/MacOS/Java";

    private String projectPath; // path of Understand database

    public DependencyAnalysis(String projectPath) {
        this.projectPath = projectPath;
    }

    /**
     * Main function for class
     * @param changedModules = a list of files that have been changed in version control
     * @return list of all files in project related to the changed source files
     */
    public ArrayList<String> getDependentModules (ArrayList<String> changedModules) {
        setLibPath();

        ArrayList<String> dependentModules = new ArrayList<>(); // "visited" in DFS

        try {
            Database db = Understand.open(projectPath);

            // entsWeCareAbout are files that are in the project; this prevents the program from looking at
            // basic java classes (i.e. java.lang.*, etc)
            Entity[] files = db.ents("files");
            ArrayList<String> entsWeCareAbout = getProjFileNamesWithoutExtension(files);

            Entity[] classes = db.ents("class");
            TreeMap<String, Entity> classTree = getClassTree(classes);

            // essentially treat the class dependencies as a graph and use DFS
            for (String module : changedModules) {
                if (!dependentModules.contains(module)) {
                    dependentModules.add(module);
                    getReferences(module, classTree, entsWeCareAbout, dependentModules);
                }
            }
        } catch (UnderstandException exception) {
            System.out.println("Failed opening Database:"
                    + exception.getMessage());
        }

        return dependentModules;
    }

    /**
     * Basically DFS through class dependencies
     * @param targetClass = class you want dependencies for
     * @param classTree = TreeMap containing database Entity objects for quick access
     * @param entsWeCareAbout = entities we want to consider
     * @param visited = all of the dependent modules visited so far
     */
    private void getReferences(String targetClass,
                               TreeMap<String, Entity> classTree,
                               ArrayList<String> entsWeCareAbout,
                               ArrayList<String> visited) {
        Entity c = classTree.get(targetClass);
        Reference[] refs = c.refs(null, "class", true);
        for (Reference ref : refs) {
            String entityName = ref.ent().name();
            if (entsWeCareAbout.contains(entityName) && !visited.contains(entityName)) {
                visited.add(entityName);
                getReferences(entityName, classTree, entsWeCareAbout, visited);
            }
        }

    }

    /**
     * Gets a TreeMap of class Entity objects
     * Keys: Class names
     * values: Entity objects from database
     */
    private TreeMap<String, Entity> getClassTree(Entity[] classes) {
        TreeMap<String, Entity> classTree = new TreeMap<>();

        for (Entity c : classes) classTree.put(c.name(), c);

        return classTree;
    }

    /**
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