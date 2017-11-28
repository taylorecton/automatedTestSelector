import com.scitools.understand.*;

import java.io.*;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.TreeMap;


/**
 * Class for utilizing SciTools Understand database for static code analysis;
 * This class will allow the test selector to only choose tests relevant to changed files.
 */
public class DependencyAnalysis {
    private static String udbPath; // path of Understand database
    private static String workspacePath;
    private static String changedFile;
    private static String libPath;

    private static Database db;

    private static final String LAST_ANALYSIS = "lastAnalysis.txt";

    /**
     * Main function for class
     * @param changedModules List of files that have been changed in version control
     * @return List of all files in project related to the changed source files
     */
    public static void main (String[] args)
            throws IOException, InterruptedException {
        if (args.length != 3) {
            System.out.println("Number of arguments is not correct.");
            System.out.println("Format: 'java DependencyAnalysis <udbPath> <workspacePath> <file>'");
            return;
        }

        udbPath = args[0];
        workspacePath = args[1];
        changedFile = args[2];

        // The line below is used to make analysis only run after a certain number of builds
        // int buildNum = checkLastAnalysis();
        // int numberOfBuildsToWait = 5;

        ArrayList<String> changedModules = getChangedModules();

        ArrayList<String> dependentModules = new ArrayList<>();

        File file = new File(udbPath);

        if (!file.exists()) {
            // if the Understand Database does not already exist, create a new Understand Database
            String command = "und create -db " + udbPath + " -languages java add "
                             + workspacePath + " analyze -all";

            System.out.println("Understand Database does not exist...");
            System.out.println("Attempting to create and analyze new database...");

            Process createDatabase = Runtime.getRuntime().exec(command);

            String output;
            BufferedReader bufferedReader = new BufferedReader(
                    new InputStreamReader(createDatabase.getInputStream()));
            while ((output = bufferedReader.readLine()) != null) {
                System.out.println(output);
            }

            createDatabase.waitFor();
            System.out.println(udbPath + " created.");
        } else /* if (buildNum % numberOfBuildsToWait == 0) */ { /* uncommenting the condition and uncommenting the
                                                                    assignment of buildNum above allows to only scan
                                                                    database after a given number of builds */

            // scan for changed/added files and analyze files that have changed
            String command = "und -db " + udbPath + " analyze -rescan -changed";

            System.out.println("Re-scanning Understand Database...");

            Process analyzeDatabase = Runtime.getRuntime().exec(command);

            String output;
            BufferedReader bufferedReader = new BufferedReader(
                    new InputStreamReader(analyzeDatabase.getInputStream())
            );
            while ((output = bufferedReader.readLine()) != null) {
                System.out.println(output);
            }

            analyzeDatabase.waitFor();
            System.out.println(udbPath + " successfully re-scanned for changes.");
        }

        try {
            System.out.println("Opening database: " + udbPath + " ..."); // <-- for debugging

            db = Understand.open(udbPath);

            System.out.println("Database opened..."); // <-- for debugging

            // entsWeCareAbout are files that are in the project; this prevents the program from looking at
            // references to basic java classes (i.e. java.lang.*, etc)
            Entity[] files = db.ents("file");
            ArrayList<String> entsWeCareAbout = getProjFileNamesWithoutExtension(files);

            /*
            for (String fileName : entsWeCareAbout) {    //
                System.out.println("File: " + fileName); // <-- for debugging
            }                                            //
            */

            Entity[] classes = db.ents("class");
            Entity[] interfaces = db.ents("interface");

            /*
            for (Entity c : classes) {                    //
                System.out.println("Class: " + c.name()); // <-- for debugging
            }                                             //
            */

            TreeMap<String, Entity> classTree = getClassInterfaceTree(classes, interfaces);

            for (String module : changedModules) {
                if (!dependentModules.contains(module)) {
                    System.out.println("Adding " + module + " to dependentModules..."); // <-- for debugging
                    dependentModules.add(module);
                    getReferences(module, classTree, entsWeCareAbout, dependentModules);
                }
            }

            db.close();
            System.out.println("Database closed.");
        } catch (UnderstandException exception) {
            System.out.println("Failed opening Database:" + exception.getMessage());
        }

        writeDependentModules(dependentModules);

        System.out.println("Inside DependencyAnalysis: DependencyAnalysis process successfully completed.");
    }

    private static ArrayList<String> getChangedModules() {
        ArrayList<String> changedModules = new ArrayList<>();

        try {
            FileReader fileReader = new FileReader(changedFile);
            BufferedReader bufferedReader = new BufferedReader(fileReader);

            String line;
            System.out.println("Changed modules list received by dependency analysis program:"); // <-- for debugging
            while ((line = bufferedReader.readLine()) != null) {
                changedModules.add(line);
                System.out.println(line);   // <-- for debugging
            }
            System.out.println();   // <-- for debugging

            bufferedReader.close();
            fileReader.close();
        } catch (IOException exception) {
            System.out.println(exception.getMessage());
        }

        return changedModules;
    }

    private static void writeDependentModules(ArrayList<String> dependentModules) {
        try {
            File file = new File(changedFile);
            file.delete();

            PrintWriter printWriter = new PrintWriter(changedFile);

            for (String module : dependentModules)
                printWriter.println(module);

            printWriter.close();
        } catch (IOException exception) {
            System.out.println(exception.getMessage());
        }
    }

    /**
     * Get all dependencies for a class
     * @param targetClass Class you want dependencies for
     * @param classTree TreeMap containing database Entity objects for quick access
     * @param entsWeCareAbout Entities we want to consider
     * @param dependencies All of the dependent modules visited so far
     */
    private static void getReferences(String targetClass,
                               TreeMap<String, Entity> classTree,
                               ArrayList<String> entsWeCareAbout,
                               ArrayList<String> dependencies) {
        // System.out.println("Inside getReferences..."); // <-- for debugging
        System.out.println(targetClass);
        Entity c = classTree.get(targetClass);
        System.out.println(c.name());
        Reference[] refs = c.refs(null, "class", true);
        // System.out.println("Iterating through references..."); // <-- for debugging
        System.out.println("All references for " + targetClass + ":"); // <-- for debugging
        for (Reference ref : refs) {
            String entityName = ref.ent().simplename();
            System.out.println(entityName);  // <-- for debugging
            // System.out.println("Checking reference: " + entityName + "..."); // <-- for debugging
            if (entsWeCareAbout.contains(entityName) && !dependencies.contains(entityName)) {
                // System.out.println("Adding " + entityName + "..."); // <-- for debugging
                dependencies.add(entityName);
            }
        }
        // System.out.println("getReferences() finishing...");
        System.out.println();
    }

    /**
     * Gets a TreeMap of class Entity objects
     * @param classes An array of Entity objects containing all classes in project
     * @return TreeMap with: Keys = Class name; values = Entity objects from database
     */
    private static TreeMap<String, Entity> getClassInterfaceTree(Entity[] classes, Entity[] interfaces) {
        // System.out.println("Inside getClassTree()..."); // <-- for debugging

        TreeMap<String, Entity> classInterfaceTree = new TreeMap<>();

        // System.out.println("Building class tree"); // <-- for debugging

        for (Entity c : classes) {
//            System.out.println(c.simplename());   // <-- for debugging
            classInterfaceTree.put(c.simplename(), c);
        }

        for (Entity i : interfaces) {
//            System.out.println(i.simplename());
            classInterfaceTree.put(i.simplename(), i);
        }

        // System.out.println("Returning from getClassTree()..."); // <-- for debugging
        return classInterfaceTree;
    }

    /**
     * @param files Array of Entity objects containing all files in project
     * @return a list of the java source files from the project without the .java extension
     */
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

    private static int checkLastAnalysis() {
        int buildNum = 0;
        String fname = workspacePath + "/" + LAST_ANALYSIS;
        File lastAnalysis = new File(fname);

        if (lastAnalysis.exists()) {
            System.out.println("Last analysis file exists");

            try {
                FileReader fileReader = new FileReader(fname);
                BufferedReader bufferedReader = new BufferedReader(fileReader);

                String number;

                number = bufferedReader.readLine();

                try {
                    buildNum = Integer.parseInt(number) + 1;
                } catch (NumberFormatException e) {
                    System.err.println(e.getMessage());
                }

                bufferedReader.close();
                fileReader.close();
                lastAnalysis.delete();
            } catch (IOException exception) {
                System.out.println(exception.getMessage());
            }
        }

        try {
            OutputStream outputStream = new FileOutputStream(fname);
            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(outputStream);
            PrintWriter printWriter = new PrintWriter(outputStreamWriter);

            printWriter.println(buildNum);

            printWriter.close();
            outputStreamWriter.close();
            outputStream.close();
        } catch (IOException e) {
            System.out.println("Error creating file");
            System.err.println(e.getMessage());
        }

        return buildNum;
    }

    /*
    private static void setLibPath() {
        System.setProperty("java.library.path", LIB_PATH);
        try {
            Field fieldSysPath = ClassLoader.class.getDeclaredField("sys_paths");
            fieldSysPath.setAccessible(true);
            fieldSysPath.set(null, null);
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
    }
    */
}
