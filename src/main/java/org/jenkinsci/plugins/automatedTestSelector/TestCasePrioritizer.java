package org.jenkinsci.plugins.automatedTestSelector;

import com.google.common.collect.ImmutableSet;

import hudson.AbortException;
import hudson.Launcher;
import hudson.Extension;
import hudson.FilePath;

import hudson.model.*;

import hudson.scm.ChangeLogSet.Entry;

import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.junit.ClassResult;
import hudson.tasks.test.AbstractTestResultAction;
import hudson.tasks.test.TabulatedResult;
import hudson.tasks.test.TestResult;
import hudson.util.FormValidation;

import org.apache.commons.io.Charsets;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.*;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.TreeMap;

/**
 * @author Taylor Ecton
 */

public class TestCasePrioritizer extends Builder {

    // set of build results to consider; currently set to only consider successful and unstable builds
    private static final ImmutableSet<Result> RESULTS_TO_CONSIDER = ImmutableSet.of(Result.SUCCESS, Result.UNSTABLE, Result.FAILURE);

    // possible starts of @SuiteClasses annotation in test suite file, and the end of the annotation
    private static final String ANNOTATION_START_1 = "@SuiteClasses({";
    private static final String ANNOTATION_START_2 = "@Suite.SuiteClasses({";
    private static final String ANNOTATION_END = "})";

    // file to contain information about when a test was last prioritized
    private static final String LAST_PRIORITIZED_FILE = "build_when_previously_prioritized.txt";

    // file for passing information between plugin and stand-alone dependency analysis program
    private static final String HANDOFF_FILE = "handoff.txt";

    // parameters used for prioritizing tests
    private final int failureWindow;
    private final int executionWindow;
    private final int priorityWindow;

    // name of the test suite file containing all tests and the directory storing test results
    private final String testSuiteFile;
    private final String testReportDir;

    // boolean indicating if the user wants to use dependency analysis or not
    private final boolean useDepAnalysis;
    // path of Understand Database if dependency analysis is used
    private final String udbPath;

    @DataBoundConstructor
    public TestCasePrioritizer(int failureWindow,
                               int executionWindow,
                               int priorityWindow,
                               String testSuiteFile,
                               String testReportDir,
                               boolean useDepAnalysis,
                               String udbPath) {
        this.executionWindow = executionWindow;
        this.failureWindow = failureWindow;
        this.priorityWindow = priorityWindow;

        this.testSuiteFile = testSuiteFile;
        this.testReportDir = testReportDir;

        this.useDepAnalysis = useDepAnalysis;
        this.udbPath = udbPath;
    }

    /**
     * Getter functions
     */
    public int getExecutionWindow() {
        return executionWindow;
    }

    public int getFailureWindow() {
        return failureWindow;
    }

    public int getPriorityWindow() {
        return priorityWindow;
    }

    public String getTestSuiteFile() {
        return testSuiteFile;
    }

    public String getTestReportDir() {
        return testReportDir;
    }

    /* public boolean getUseDepAnalysis() {
        return useDepAnalysis;
    } */

    public String getUdbPath() {
        return udbPath;
    }

    /**
     * main function of the regression test selector
     */
    @Override
    public boolean perform(AbstractBuild<?,?> build, Launcher launcher, BuildListener listener)
            throws IOException, InterruptedException {
        long startTime = System.currentTimeMillis();
        long stopTime;
        double elapsedTimeInSeconds;

        // Print out user input parameters to verify they are set correctly
        listener.getLogger().println("Running test case prioritizer...");
        listener.getLogger().println("Failure window is set to: " + failureWindow);
        listener.getLogger().println("Execution window is set to: " + executionWindow);
        listener.getLogger().println("Prioritization window is set to: " + priorityWindow);
        if (useDepAnalysis) listener.getLogger().println("UDB Path: " + udbPath);
        // listener.getLogger().println("Class path: " + System.getProperty("java.class.path")); // <-- for debugging

        // get current build number for setting last prioritized build number on tests
        int currentBuildNum = build.getNumber();
        // gets project workspace
        FilePath workspace = build.getWorkspace();
        if (workspace == null)
            throw new AbortException("No workspace");

        listener.getLogger().println("remote: " + workspace.getRemote()); // <-- for debugging

        // clears the test report directory before running; plugin encounters an error if it does not do this
        FilePath reportDir = workspace.child(testReportDir);
        reportDir.deleteContents();

        // linesForFile holds the lines to put in the test suite file, minus the tests
        ArrayList<String> linesForFile = new ArrayList<>();
        // allTests holds all of the test classes found in the test suite file
        TreeMap<String, TestPriority> allTests = getAllTests(workspace, linesForFile, listener);
        // relevantTests will hold the tests found to be relevant to current code changes
        TreeMap<String, TestPriority> relevantTests;

        // get relevant tests from dependency analysis if useDepAnalysis is true; use allTests otherwise
        if (useDepAnalysis) {
            relevantTests = doDependencyAnalysis(build, listener, allTests);
        } else {
            relevantTests = allTests;
        }

        // checks to make sure allTests contains tests
        if (!allTests.isEmpty()) {
            // read last prioritized build file and set prioritized build number for tests accordingly
            setPreviousPrioritizedBuildNums(workspace, listener, relevantTests, allTests);

            // returns tests sorted by priority
            ArrayList<TestPriority> sortedTests = prioritizeTests(build, currentBuildNum, listener, relevantTests);

            // get a list containing all tests with current previous prioritized build numbers
            // used for writing to the previous prioritized build file
            ArrayList<TestPriority> testList = updateAllLastPrioritizedNumbers(allTests, sortedTests, currentBuildNum);

            // write the test suite file with the sorted tests and write the previous prioritized build file
            // with the list of all tests
            buildFiles(workspace, sortedTests, testList, linesForFile);
        } else {
            // allTests does not contain any values
            listener.getLogger().println("Error: allTests is empty. Cannot prioritize tests.");
        }

        stopTime = System.currentTimeMillis();
        elapsedTimeInSeconds = (stopTime - startTime) / 1000.0;

        listener.getLogger().println("Test selection and prioritization took " + elapsedTimeInSeconds + " seconds.");

        return true;
    }

    /**
     * Uses SciTools Understand to determine which files are relevant to changes made in version control
     * @param build The current build
     * @param listener BuildListener used to write to Jenkins console output
     * @param allTests TreeMpa of all tests
     * @return TreeMap containing only the tests relevant to changes
     */
    private TreeMap<String, TestPriority> doDependencyAnalysis(AbstractBuild<?,?> build,
                                                               BuildListener listener,
                                                               TreeMap<String, TestPriority> allTests)
            throws IOException, InterruptedException {

        // ------------ DEPENDENCY ANALYSIS CLASS MOVED TO STAND-ALONE PROGRAM -----------------------------
        // ------------ due to bug that has not yet been resolved... ---------------------------------------
        // DependencyAnalysis dependencyAnalysis = new DependencyAnalysis(udbPath, workspacePath, listener);

        // TreeMap will hold tests relevant to files changed in version control
        TreeMap<String, TestPriority> relevantTests = new TreeMap<>();
        // allChangedFiles will hold EVERY file changed in version control since previous build
        ArrayList<String> allChangedFiles = new ArrayList<>();
        // changedSourceFiles will hold only changed .java files
        ArrayList<String> changedSourceFiles = new ArrayList<>();
        // dependentModules will hold all .java files related to changed files (including non-tests)
        ArrayList<String> dependentModules = new ArrayList<>();

        listener.getLogger().println("**----------------------------------**"); // <-- for debugging
        listener.getLogger().println("Running dependency analysis code..."); // <-- for debugging

        // get allChangedFiles from version control
        for (Entry entry : build.getChangeSet()) {
            if (entry.getAffectedPaths() != null)
                allChangedFiles.addAll(entry.getAffectedPaths());
        }

        // do not enter the following block if no files have changed from previous build
        if (!allChangedFiles.isEmpty()) {

            listener.getLogger().println("-------------------------------"); // <-- for debugging
            listener.getLogger().println("All changed files: "); // <-- for debugging

            // check each file name to see if it contains '.java'; add to changedSourceFiles if it does
            for (String file : allChangedFiles) {

                listener.getLogger().println(file); // <-- for debugging

                if (file.contains(".java")) {
                    String[] pathComponents = file.split("/");
                    file = pathComponents[pathComponents.length - 1];
                    file = file.replace(".java", "");
                    changedSourceFiles.add(file);
                }
            }

            listener.getLogger().println("-------------------------------"); // <-- for debugging

            // do not enter the following block if none of the changed files are .java files
            if (!changedSourceFiles.isEmpty()) {
                // --------------- DEPENDENCY ANALYSIS CLASS MOVED TO STAND-ALONE PROGRAM -------
                // --------------- due to issue opening Understand database more than once ------
                // dependentModules = dependencyAnalysis.getDependentModules(changedSourceFiles);

                runDependencyAnalysisJava(build, listener, changedSourceFiles, dependentModules);

                listener.getLogger().println("All dependent files: "); // <-- for debugging

                // iterate through dependentModules and add '.class' to the string for use in
                // the re-written testSuiteFile
                for (String file : dependentModules) {

                    listener.getLogger().println(file); // <-- for debugging

                    file += ".class";

                    // if the file is a test (determined by checking if it's contained in allTests),
                    // then add it to relevantTests
                    if (allTests.containsKey(file)) {
                        relevantTests.put(file, allTests.get(file));
                    }
                }
            } else {
                // No changes found in version control since the previous build
                listener.getLogger().println("No changed source code files. Utilizing all tests for prioritization.");
                // set relevant tests to allTests and consider all tests for execution
                relevantTests = allTests;
            }

            listener.getLogger().println("**----------------------------------**"); // <-- for debugging

        }

        // the following is executed if none of the changed files relate to the tests,
        // for example: if no changed files are source files
        if (relevantTests.isEmpty()) {
            listener.getLogger().println("List of relevant tests is empty. Tests may be unrelated to changes.");
            listener.getLogger().println("Using all tests in Test Suite File");
            // set relevant tests to allTests and consider all tests for execution
            relevantTests = allTests;
        }

        return relevantTests;
    }

    private void runDependencyAnalysisJava(AbstractBuild<?,?> build,
                                           BuildListener listener,
                                           ArrayList<String> changedSourceFiles,
                                           ArrayList<String> dependentModules)
            throws IOException, InterruptedException {

        // wokspacePath is the absolute path of the build workspace for this Jenkins job
        String workspacePath;
        try {
            workspacePath = build.getWorkspace().getRemote();
        } catch (NullPointerException e) {
            throw new AbortException("getRemote returned null");
        }
        // concatenate workspacePath and HANDOFF_FILE to get path of handoff file
        String handoffPath = workspacePath + "/" + HANDOFF_FILE;

        // listener.getLogger().println("handoffpath = " + handoffPath); // <-- for debugging

        // create the handoff file; if there is an old one, delete it first
        try {
            File file = new File(handoffPath);
            if (file.exists()) {

                listener.getLogger().println("Deleting old handoff file..."); // <-- for debugging

                if (file.delete()) listener.getLogger().println("File deleted.");
            }

            OutputStream outputStream = new FileOutputStream(handoffPath);
            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(outputStream, Charsets.UTF_8);
            PrintWriter printWriter = new PrintWriter(outputStreamWriter);

            listener.getLogger().println("Writing new handoff file..."); // <-- for debugging

            // write each changed source file to the handoff file
            for (String sourceFile : changedSourceFiles)
                printWriter.println(sourceFile);

            printWriter.close();
        } catch (IOException exception) {
            listener.getLogger().println(exception.getMessage());
        }

        // command is the shell command to run DependencyAnalysis program
        String command = "java DependencyAnalysis " + udbPath + " " + workspacePath + " " + handoffPath;

        // listener.getLogger().println("command = " + command); // <-- for debugging

        // run command using exec; output is redirected to listener.getLogger() for viewing in Jenkins
        Process dependencyAnalysis = Runtime.getRuntime().exec(command);
        String output;
        BufferedReader depAnalysisReader = new BufferedReader(
                new InputStreamReader(dependencyAnalysis.getInputStream(), Charsets.UTF_8) );

        while ((output = depAnalysisReader.readLine()) != null) {
            listener.getLogger().println(output);
        }

        // make sure dependencyAnalysis process terminates before proceeding
        dependencyAnalysis.waitFor();
        depAnalysisReader.close();

        listener.getLogger().println("Dependency analysis should have finished."); // <-- for debugging

        // try to read information from the handoff file; should now contain information from
        // dependency analysis program
        try {
            InputStream inputStream;
            try {
                inputStream = build.getWorkspace().child(HANDOFF_FILE).read();
            } catch (NullPointerException e) {
                throw new AbortException("inputStream is null");
            }
            InputStreamReader inputStreamReader = new InputStreamReader(inputStream, Charsets.UTF_8);
            BufferedReader bufferedReader = new BufferedReader(inputStreamReader);

            String line;
            while((line = bufferedReader.readLine()) != null)
                dependentModules.add(line);

            bufferedReader.close();
            inputStreamReader.close();
            inputStream.close();
        } catch (IOException exception) {
            listener.getLogger().println(exception.getMessage());
        }
    }

    /**
     * Creates a list of all tests from the testSuiteFile provided by user
     *
     * @param workspace FilePath for current build workspace
     * @param linesForFile list containing lines from the test suite file; used to rewrite the file later
     * @return A TreeMap of all tests found in the test suite file
     */
    private TreeMap<String, TestPriority> getAllTests(FilePath workspace, ArrayList<String> linesForFile, BuildListener listener)
            throws IOException, InterruptedException {

        TreeMap<String, TestPriority> allTests = new TreeMap<>();

        listener.getLogger().println("All tests: "); // <-- for debugging

        // try to read the file, read in all of the test names and add them to the list
        try (InputStream inputStream = workspace.child(testSuiteFile).read();
             InputStreamReader inputStreamReader = new InputStreamReader(inputStream, Charsets.UTF_8);
             BufferedReader bufferedReader = new BufferedReader(inputStreamReader)) {
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                if (line.startsWith("//")) // ignore comment lines
                    continue;

                linesForFile.add(line);
                if (line.trim().equals(ANNOTATION_START_1) || line.trim().equals(ANNOTATION_START_2)) {
                    line = bufferedReader.readLine();
                    while (line != null && !line.trim().equals(ANNOTATION_END)) {
                        if (!line.contains(".class")) // ignore potential blank lines
                            continue;

                        line = line.trim();
                        if (line.contains(","))
                            line = line.replace(",", "");
                        TestPriority testPriority = new TestPriority(line);
                        allTests.put(line, testPriority);

                        listener.getLogger().println(line); // <-- for debugging

                        line = bufferedReader.readLine();
                    }
                }
            }
            bufferedReader.close();
            inputStreamReader.close();
            inputStream.close();
        }

        /* FOR DEBUG
        for (String line : linesForFile)
            System.out.println(line);
        */

        return allTests;
    }

    /**
     * Updates the last prioritized build number for tests prioritized this build
     *
     * @param allTests TreeMap of all tests from test suite file
     * @param sortedTests list of TestPriority objects sorted by priority
     * @param currentBuildNum the current build number
     *
     * @return A list of all tests with their last prioritized build number up to date
     */
    private ArrayList<TestPriority> updateAllLastPrioritizedNumbers(TreeMap<String,
                                                                   TestPriority> allTests,
                                                                   ArrayList<TestPriority> sortedTests,
                                                                   int currentBuildNum) {
        for (TestPriority test : sortedTests) {
            // for each relevant test, update the previousPrioritizedBuildNumber for that test in the list of
            // allTests
            allTests.get(test.getClassName()).setPreviousPrioritizedBuildNum(test.getPreviousPrioritizedBuildNum());
        }

        // get an ArrayList with the values in the allTests TreeMap
        ArrayList<TestPriority> testList = new ArrayList<>(allTests.values());
        return testList;
    }

    /**
     * Returns a list of tests sorted by priority
     *
     * @param build The current build
     * @param currentBuildNumber The build number of the current build
     * @param listener BuildListener used for writing to logger
     * @param tests TreeMap of all the tests being considered for prioritization
     *
     * @return ArrayList of TestPriority objects sorted with high priority tests at the beginning of the list
     */
    private ArrayList<TestPriority> prioritizeTests(Run<?, ?> build,
                                                    int currentBuildNumber,
                                                    BuildListener listener,
                                                    TreeMap<String, TestPriority> tests) {
        ArrayList<String> foundTests = new ArrayList<>();
        ArrayList<String> testNames = new ArrayList<>(tests.keySet());

        /* DEBUG CODE
        for (String t : testNames)
            listener.getLogger().println("keys: " + t);
        */

        // continue iterating until i reaches failureWindow or executionWindow, whichever is larger
        for (int i = 0; i < failureWindow || i < executionWindow; i++) {
            build = build.getPreviousBuild();

            if (build == null) break;
            if (!RESULTS_TO_CONSIDER.contains(build.getResult())) continue; // build failed = no test results

            AbstractTestResultAction testResultAction = build.getAction(AbstractTestResultAction.class);
            if (testResultAction == null) continue;

            Object object = testResultAction.getResult();
            if (object instanceof TestResult) {
                TestResult result = (TestResult) object;
                ArrayList<String> testsFailedThisBuild = new ArrayList<>();

                Boolean withinExecutionWindow = i < this.getExecutionWindow();
                Boolean withinFailureWindow = i < this.getFailureWindow();
                collect(result, testsFailedThisBuild, foundTests, withinExecutionWindow, withinFailureWindow);

                // failing tests within failure window should be selected; don't add duplicates
                for (String testName : testsFailedThisBuild) {
                    if (tests.containsKey(testName)) {

                        listener.getLogger().println(testName + " failed a build"); // <-- for debugging
                        listener.getLogger().println("Prioritizing " + testName);   // <-- for debugging
                        listener.getLogger().println();                             // <-- for debugging

                        TestPriority testPriority = tests.get(testName);
                        testPriority.setHighPriority();
                        testPriority.setPreviousPrioritizedBuildNum(currentBuildNumber);
                    }
                }
            }
        }

        // tests not found have not been executed within execution window and should be selected
        for (String test : testNames) {
            if (!foundTests.contains(test) && tests.containsKey(test)) {

                listener.getLogger().println(test + " not found within execution window"); // <-- for debugging
                listener.getLogger().println("Prioritizing " + test);                      // <-- for debugging
                listener.getLogger().println();                                            // <-- for debugging

                tests.get(test).setHighPriority();
            }
        }

        ArrayList<TestPriority> sortedTests = new ArrayList<>(tests.values());

        for (TestPriority testPriority : sortedTests) {
            if ((currentBuildNumber - testPriority.getPreviousPrioritizedBuildNum()) > priorityWindow) {
                // test has not been prioritized within priorityWindow

                listener.getLogger().println(testPriority.getClassName() + " not prioritized w/in window"); // <-- for debugging
                listener.getLogger().println("Prioritizing " + testPriority.getClassName());                // <-- for debugging
                listener.getLogger().println();                                                             // <-- for debugging

                // prioritize test and set previous prioritized build number to currentBuildNum
                testPriority.setHighPriority();
                testPriority.setPreviousPrioritizedBuildNum(currentBuildNumber);
            }
        }

        // sort the tests according to priority value
        Collections.sort(sortedTests);

        return sortedTests;
    }

    /**
     * Generates includesFile to be used by build script
     *
     * @param workspace FilePath of build workspace
     * @param sortedTests ArrayList of TestPriority objects sorted by priority
     * @param testList ArrayList of TestPriority objects containing all tests from test suite file
     * @param linesForFile ArrayList of lines for test suite file to rewrite the file
     */
    private void buildFiles(FilePath workspace,
                            ArrayList<TestPriority> sortedTests,
                            ArrayList<TestPriority> testList,
                            ArrayList<String> linesForFile)
            throws IOException, InterruptedException {
        // try to re-write the testSuiteFile to use for this build
        // and update the LAST_PRIORITIZED_FILE
        try (OutputStream osSuiteFile = workspace.child(testSuiteFile).write();
             OutputStreamWriter oswSuiteFile = new OutputStreamWriter(osSuiteFile, Charsets.UTF_8);
             PrintWriter pwSuiteFile = new PrintWriter(oswSuiteFile);

             OutputStream osPriorityWindowFile = workspace.child(LAST_PRIORITIZED_FILE).write();
             OutputStreamWriter oswPriorityWindowFile = new OutputStreamWriter(osPriorityWindowFile, Charsets.UTF_8);
             PrintWriter pwPriorityWindowFile = new PrintWriter(oswPriorityWindowFile)) {

            // for each line that was originally in the test suite file, excluding the original ordering
            // of tests, write the line into the new file
            for (String line : linesForFile) {
                pwSuiteFile.println(line);

                // after the start of the SuiteClasses annotation, write the tests in prioritized order
                if (line.equals(ANNOTATION_START_1) || line.equals(ANNOTATION_START_2)) {
                    TestPriority testPriority;
                    for (int i = 0; i < sortedTests.size() - 1; i++) {
                        testPriority = sortedTests.get(i);
                        pwSuiteFile.println(testPriority.getClassName() + ",");
                    }
                    if (!sortedTests.isEmpty()) {
                        testPriority = sortedTests.get(sortedTests.size() - 1);
                        pwSuiteFile.println(testPriority.getClassName());
                    }
                    pwSuiteFile.println(ANNOTATION_END);
                }
            }

            // for every test in the project, write the last build it was prioritized to a file
            for (TestPriority test : testList) {
                pwPriorityWindowFile.println(test.getClassName()
                        + ":" + test.getPreviousPrioritizedBuildNum());
            }

            // make sure to close all the things
            pwSuiteFile.close();
            oswSuiteFile.close();
            osSuiteFile.close();

            pwPriorityWindowFile.close();
            oswPriorityWindowFile.close();
            osPriorityWindowFile.close();
        }
    }

    /**
     *
     * @param workspace FilePath for current build workspace
     * @param listener BuildListener object; used to write to build's logger
     * @param relevantTests TreeMap containing tests relevant to changes to version control
     * @param allTests TreeMap containing all tests found in Test Suite File
     */
    private void setPreviousPrioritizedBuildNums(FilePath workspace,
                                                 BuildListener listener,
                                                 TreeMap<String, TestPriority> relevantTests,
                                                 TreeMap<String, TestPriority> allTests)
            throws IOException, InterruptedException {
        // read LAST_PRIORITIZED_FILE to get the last build number where each test was prioritized
        // used for the priority window check
        try {
            InputStream inputStream = workspace.child(LAST_PRIORITIZED_FILE).read();
            InputStreamReader inputStreamReader = new InputStreamReader(inputStream, Charsets.UTF_8);
            BufferedReader bufferedReader = new BufferedReader(inputStreamReader);

            // read and parse each line of the file
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                String[] splitLine = line.split(":");
                // set the build number in each of the TreeMaps holding TestPriority objects
                if (relevantTests.containsKey(splitLine[0]))
                    relevantTests.get(splitLine[0]).setPreviousPrioritizedBuildNum(Integer.parseInt(splitLine[1]));
                if (allTests.containsKey(splitLine[0]))
                    allTests.get(splitLine[0]).setPreviousPrioritizedBuildNum(Integer.parseInt(splitLine[1]));
            }
            // close all the things
            bufferedReader.close();
            inputStreamReader.close();
            inputStream.close();
        } catch (FileNotFoundException e) {
            listener.getLogger().println(LAST_PRIORITIZED_FILE + " not found.");
            listener.getLogger().println("Using 0 as previous prioritized build number.");
        } catch (NoSuchFileException e) {
            listener.getLogger().println("no such file : " + LAST_PRIORITIZED_FILE);
        }
    }

    /**
     * Collect test names from a build into two lists: found and failed
     *
     * @param testResult TestResult object from the current build
     * @param failed ArrayList that will hold names of tests found to be failing
     * @param found ArrayList holding all tests discovered in the collection process
     * @param withinExWindow Boolean value specifying if current iteration is within the execution window
     * @param withinFailWindow Boolean value specifying if iteration is within failure window
     */
    static private void collect(TestResult testResult,
                                ArrayList<String> failed,
                                ArrayList<String> found,
                                Boolean withinExWindow,
                                Boolean withinFailWindow) {
        if (testResult instanceof ClassResult) {
            ClassResult classResult = (ClassResult) testResult;
            String className;
            String pkgName = classResult.getParent().getName();

            if (pkgName.equals("(root)"))
                pkgName = "";
            else
                pkgName += '.';
            className = pkgName + classResult.getName() + ".class";
            // System.out.println("Collect: " + className); // <-- for debugging

            if (withinExWindow && !found.contains(className)) // don't add if outside of execution window
                found.add(className);

            if (withinFailWindow && classResult.getFailCount() > 0) // don't add if outside of failure window
                failed.add(className);

            return; // no need to go deeper
        }
        if (testResult instanceof TabulatedResult) {
            TabulatedResult tabulatedResult = (TabulatedResult) testResult;
            for (TestResult child : tabulatedResult.getChildren()) {
                collect(child, failed, found, withinExWindow, withinFailWindow);
            }
        }
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        public DescriptorImpl() {
            load();
        }

        public FormValidation doCheckExecutionWindow(@QueryParameter String value)
                throws IOException, ServletException {
            try {
                int input = Integer.parseInt(value);
                if (input >= 0)
                    return FormValidation.ok();
                else
                    return FormValidation.error("Execution window must be a positive number.");
            } catch (NumberFormatException e) {
                return FormValidation.error("Execution window must be a number.");
            }
        }

        public FormValidation doCheckFailureWindow(@QueryParameter String value)
                throws IOException, ServletException {
            try {
                int input = Integer.parseInt(value);
                if (input >= 0)
                    return FormValidation.ok();
                else
                    return FormValidation.error("Failure window must be a positive number.");
            } catch (NumberFormatException e) {
                return FormValidation.error("Failure window must be a number.");
            }
         }

        public FormValidation doCheckPrioritizationWindow(@QueryParameter String value)
                throws IOException, ServletException {
            try {
                int input = Integer.parseInt(value);
                if (input >= 0)
                    return FormValidation.ok();
                else
                    return FormValidation.error("Prioritization window must be a positive number.");
            } catch (NumberFormatException e) {
                return FormValidation.error("Prioritization window must be a number.");
            }
        }

        public FormValidation doCheckTestSuiteFile(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("You must set the path of the test suite file for the project.");

            return FormValidation.ok();
        }

        public FormValidation doCheckTestReportDir(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("You must set the directory containing test results.");

            return FormValidation.ok();
        }

        public FormValidation doCheckUnderstandDatabasePath(@QueryParameter String understandDatabasePath,
                                                            @QueryParameter Boolean useDependencyAnalysis)
                throws IOException, ServletException {

            if (useDependencyAnalysis) {
                if (understandDatabasePath.length() == 0)
                    return FormValidation.error("To use dependency analysis, you must set this value.");
            }

            return FormValidation.ok();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // this builder can be used with all project types
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Test Case Prioritizer";
        }

    }
}