import java.io.*;
import java.util.ArrayList;
import java.util.LinkedList;

/**
 * Command line tool for generating an AllTests.java test suite for JUnit using @RunWith and @SuiteClasses
 * annotations. May extend the tool to update the found test files to JUnit 4 as well, if necessary.
 */
public class AllTestsCreator {
    // contains all test files found
    private ArrayList<File> testFiles = new ArrayList<>();

    // search queue of directories
    private LinkedList<File> directories = new LinkedList<>();

    // import statements for output file
    private ArrayList<String> importStatements = new ArrayList<>();

    // names of classes for output file (excludes test suites)
    private ArrayList<String> testClasses = new ArrayList<>();

    /**
     * Constructor
     * @param startingDirectoryPath The path of  the directory that will serve as the root of the search.
     * @param testPackage The name of the package for the output file.
     */
    private AllTestsCreator(String startingDirectoryPath, String testPackage) {
        // get the starting directory and add it as the first element of the search queue
        File startingDirectory = new File(startingDirectoryPath);
        this.directories.add(startingDirectory);

        // sets up import statements common to all output files
        initializeImports(testPackage);

        // finds all test files in the project
        findFiles();

        // reads the files for package names for import statements and discards test suite files
        processFiles();
    }

    /**
     * Sets up import statements common to all output files.
     * @param testPackage The name of the package for the output file.
     */
    private void initializeImports(String testPackage) {
        // only add the package statement if one is needed
        if (!testPackage.equals(""))
            importStatements.add("package " + testPackage + ";");

        // used for running the tests
        importStatements.add("import org.junit.Runners.RunWith;");
        importStatements.add("import org.junit.Runners.Suite;");
        importStatements.add("import org.junit.Runners.Suite.SuiteClasses;");
    }

    /**
     * Find all test files in starting directory and its subdirectories.
     */
    private void findFiles() {
        System.out.println("Finding files...");

        // while the search queue is not empty
        while (!(directories.size() == 0)) {
            // get the next directory in the queue and a list of all contained files
            File dir = directories.poll();
            String[] fileNamesInDir = dir.list();

            // checking for null to prevent possible NullPointerException
            if (fileNamesInDir != null) {
                // for all of the file names in the directory
                for (String fileName : fileNamesInDir) {
                    // get the file
                    File f = new File(dir.getAbsolutePath() + '/' + fileName);

                    // add it to the list of test files if its name matches the regex
                    if (fileName.matches("[a-zA-Z0-9|/]*Test.java$|[a-zA-Z0-9|/]*Tests.java$"))
                        testFiles.add(f);

                    // make sure current directory and parent directory are not re-added to queue
                    if (!fileName.equals(".") && !fileName.equals("..")) {
                        // if the file is a directory, add it to the queue
                        if (f.isDirectory())
                            directories.add(f);
                    }
                }
            }
        }
        System.out.println(testFiles.size() + " test files found.");
    }

    /**
     * Reads the files for package names to construct imports and discards test suite files.
     * IF TEST FILES NEED TO BE UPDATED FROM JUNIT 3 TO JUNIT 4, COULD EXTEND THIS FUNCTION
     */
    private void processFiles() {
        // for reading the file
        FileReader fReader;
        BufferedReader bReader;

        // information for output file
        String packageName;
        String className;

        // the line being read in
        String line;
        // switch to exclude test suites
        boolean addClass;

        System.out.println("Processing files...");

        // for all the test files
        for (File f : testFiles) {
            // initialize variables for loop
            packageName = "";
            addClass = true;

            try {
                fReader = new FileReader(f);
                bReader = new BufferedReader(fReader);

                // read all the lines in the file
                while ((line = bReader.readLine()) != null) {
                    // get the package name
                    if (line.contains("package ")) {
                        line = line.replace("package ", "");
                        packageName = line.replace(";", "");
                    }
                    // break out of the loop and turn switch off if the file is a test suite
                    if (line.contains("suite.addTest(")) {
                        addClass = false;
                        break;
                    }
                }

                bReader.close();
                fReader.close();

                if (addClass) {
                    className = f.getName().replace(".java", "");

                    // add import statement if package name was set
                    if (!packageName.equals(""))
                        importStatements.add("import " + packageName + "." + className + ";");
                    // add the class name to the list of classes
                    testClasses.add(className + ".class");
                }

            } catch (Exception e) {
                System.err.println(e.getMessage());
                System.exit(1);
            }
        }

        System.out.println(testClasses.size() + " classes ready to write to all tests file.");
    }

    /**
     * Writes the output file.
     * @param name The name of the output file.
     */
    private void writeAllTestsFile(String name) {
        System.out.println("Writing " + name + "...");

        try {
            PrintWriter printWriter = new PrintWriter(name);
            BufferedWriter bufferedWriter = new BufferedWriter(printWriter);

            // remove the .java from file name to write corresponding class name
            String className = name.replace(".java", "");

            // write the import statements
            for (String importStatement : importStatements)
                bufferedWriter.write(importStatement + "\n");
            bufferedWriter.newLine();

            // write annotations
            bufferedWriter.write("@SuppressWarnings(\"deprecation\")\n");
            bufferedWriter.write("@RunWith(Suite.class)\n");
            bufferedWriter.write("@SuiteClasses({\n");

            // write classes inside the @SuiteClasses annotation
            for (int i = 0; i < testClasses.size() - 1; i++)
                bufferedWriter.write("\t" + testClasses.get(i) + ",\n");
            bufferedWriter.write("\t" + testClasses.get(testClasses.size()-1) + "\n");
            bufferedWriter.write("})\n\n");

            // write the class
            bufferedWriter.write("public class " + className + " {\n");
            bufferedWriter.write("}");

            bufferedWriter.close();
            printWriter.close();

            System.out.println("Done.");

        } catch (Exception e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Main method.
     * @param args Command line arguments: 1. Starting Directory, 2. Package Name for AllTests, 3. Name for Output File
     */
    public static void main(String[] args) {
        // checks for correct number of command line arguments
        if (args.length != 3) {
            System.err.println("Usage: AllTestsCreator <startingDirectory> <testPackageName> <outputFileName>");
            System.exit(1);
        }

        // command line arguments in variables for readability
        String startingDir = args[0];
        String testPackageName = args[1];
        String outputFileName = args[2];

        // instantiate AllTestsCreator
        AllTestsCreator allTestsCreator = new AllTestsCreator(startingDir, testPackageName);

        // write the output file
        allTestsCreator.writeAllTestsFile(outputFileName);
    }
}
