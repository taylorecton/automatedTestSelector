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
import org.jaxen.pantry.Test;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.TreeMap;

/**
 * @author Taylor Ecton
 */

public class TestCasePrioritizer extends Builder {

    public static final ImmutableSet<Result> RESULTS_TO_CONSIDER = ImmutableSet.of(Result.SUCCESS, Result.UNSTABLE);
    public static final String ANNOTATION_START_1 = "@SuiteClasses({";
    public static final String ANNOTATION_START_2 = "@Suite.SuiteClasses({";
    public static final String ANNOTATION_END = "})";
    public static final String LAST_PRIORITIZED_FILE = "build_when_previously_prioritized.txt";

    private final int failureWindow;
    private final int executionWindow;
    private final int prioritizationWindow;

    private final String testSuiteFile;
    private final String testReportDir;

    private final Boolean useDependencyAnalysis;
    private final String understandDatabasePath;

    //private final String includesFile;

    @DataBoundConstructor
    public TestCasePrioritizer(int failureWindow,
                               int executionWindow,
                               int prioritizationWindow,
                               String testSuiteFile,
                               String testReportDir,
                               Boolean useDependencyAnalysis,
                               String understandDatabasePath) {
        this.executionWindow = executionWindow;
        this.failureWindow = failureWindow;
        this.prioritizationWindow = prioritizationWindow;

        this.testSuiteFile = testSuiteFile;
        this.testReportDir = testReportDir;

        this.useDependencyAnalysis = useDependencyAnalysis;
        this. understandDatabasePath = understandDatabasePath;
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

    public String getTestSuiteFile() {
        return testSuiteFile;
    }

    public String getTestReportDir() {
        return testReportDir;
    }

    /*
    public String getIncludesFile() {
        return includesFile;
    }
    */

    /**
     * main function of the regression test selector
     */
    @Override
    public boolean perform(AbstractBuild<?,?> build, Launcher launcher, BuildListener listener)
            throws InterruptedException, IOException {
        listener.getLogger().println("Running regression test selector...");
        listener.getLogger().println("Failure window is set to: " + failureWindow);
        listener.getLogger().println("Execution window is set to: " + executionWindow);
        listener.getLogger().println("Prioritization window is set to: " + prioritizationWindow);
        listener.getLogger().println("Class path: " + System.getProperty("java.class.path"));

        int currentBuildNum = build.getNumber();
        FilePath workspace = build.getWorkspace();
        if (workspace == null)
            throw new AbortException("No workspace");

        FilePath reportDir = workspace.child(testReportDir);
        reportDir.deleteContents();

        ArrayList<String> linesForFile = new ArrayList<>();
        TreeMap<String, TestPriority> allTests = getAllTests(workspace, testSuiteFile, linesForFile);
        TreeMap<String, TestPriority> dependentTests;

        if (useDependencyAnalysis) {
            dependentTests = doDependencyAnalysis(build, listener, allTests);
        } else {
            dependentTests = allTests;
        }

        if (!allTests.isEmpty()) {
            setPreviousPrioritizedBuildNums(workspace, listener, dependentTests, allTests);
            ArrayList<TestPriority> sortedTests = prioritizeTests(build, currentBuildNum, listener, dependentTests);
            ArrayList<TestPriority> testList = updateAllTestPriorities(allTests, sortedTests, currentBuildNum);
            buildFiles(workspace, testSuiteFile, sortedTests, testList, linesForFile);
        } else {
            listener.getLogger().println("Error: allTests is empty. Cannot prioritize tests.");
        }

        return true;
    }

    private TreeMap<String, TestPriority> doDependencyAnalysis(AbstractBuild<?,?> build,
                                           BuildListener listener,
                                           TreeMap<String, TestPriority> allTests) {
        TreeMap<String, TestPriority> updatedAllTests = new TreeMap<>();

        listener.getLogger().println("**----------------------------------**"); // <-- for debugging
        listener.getLogger().println("Running dependency analysis code..."); // <-- for debugging
        DependencyAnalysis dependencyAnalysis = new DependencyAnalysis(understandDatabasePath);
        ArrayList<String> allChangedFiles = new ArrayList<>();
        ArrayList<String> changedSourceFiles = new ArrayList<>();
        ArrayList<String> dependentModules = new ArrayList<>();

        for (Entry entry : build.getChangeSet()) {
            if (entry.getAffectedPaths() != null)
                allChangedFiles.addAll(entry.getAffectedPaths());
        }

        if (!allChangedFiles.isEmpty()) {
            listener.getLogger().println("-------------------------------"); // <-- for debugging
            listener.getLogger().println("All changed files: "); // <-- for debugging
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

            dependentModules = dependencyAnalysis.getDependentModules(changedSourceFiles);

            listener.getLogger().println("All dependent files: "); // <-- for debugging
            for (String file : dependentModules) {
                listener.getLogger().println(file);
                file += ".class";
                if (allTests.containsKey(file)) {
                    updatedAllTests.put(file, allTests.get(file));
                }
            }
            listener.getLogger().println("**----------------------------------**"); // <-- for debugging
        } else {
            listener.getLogger().println("No changed files detected...");
            listener.getLogger().println("Using all tests in Test Suite File");
            updatedAllTests = allTests;
        }

        return updatedAllTests;
    }

    /**
     * Creates a list of all tests from the testListFile provided by user
     */
    private TreeMap<String, TestPriority> getAllTests(FilePath workspace,
                                                      String testSuiteFile,
                                                      ArrayList<String> linesForFile)
            throws IOException, InterruptedException {

        TreeMap<String, TestPriority> allTests = new TreeMap<>();

        // try to read the file, read in all of the test names and add them to the list
        try (InputStream inputStream = workspace.child(testSuiteFile).read();
             InputStreamReader inputStreamReader = new InputStreamReader(inputStream, Charsets.UTF_8);
             BufferedReader bufferedReader = new BufferedReader(inputStreamReader)) {
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                linesForFile.add(line);
                if (line.trim().equals(ANNOTATION_START_1) || line.trim().equals(ANNOTATION_START_2)) {
                    line = bufferedReader.readLine();
                    while (!line.trim().equals(ANNOTATION_END)) {
                        line = line.trim();
                        if (line.contains(","))
                            line = line.replace(",", "");
                        TestPriority testPriority = new TestPriority(line);
                        allTests.put(line, testPriority);
                        line = bufferedReader.readLine();
                    }
                }
            }
            bufferedReader.close();
        }

        /* FOR DEBUG
        for (String line : linesForFile)
            System.out.println(line);
        */

        return allTests;
    }

    private ArrayList<TestPriority> updateAllTestPriorities(TreeMap<String, TestPriority> allTests,
                                         ArrayList<TestPriority> sortedTests,
                                         int currentBuildNum) {
        for (TestPriority test : sortedTests) {
            allTests.get(test.getClassName()).setPreviousPrioritizedBuildNum(currentBuildNum);
        }

        ArrayList<TestPriority> testList = new ArrayList<>(allTests.values());
        return testList;
    }

    /**
     * Returns a list of tests selected for execution
     */
    private ArrayList<TestPriority> prioritizeTests(Run<?, ?> build,
                                                    int currentBuildNumber,
                                                    TaskListener listener,
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
            if ((currentBuildNumber - testPriority.getPreviousPrioritizedBuildNum()) > prioritizationWindow) {

                listener.getLogger().println(testPriority.getClassName() + " not prioritized w/in window"); // <-- for debugging
                listener.getLogger().println("Prioritizing " + testPriority.getClassName());                // <-- for debugging
                listener.getLogger().println();                                                             // <-- for debugging

                testPriority.setHighPriority();
                testPriority.setPreviousPrioritizedBuildNum(currentBuildNumber);
            }
        }

        Collections.sort(sortedTests);

        return sortedTests;
    }

    /**
     * Generates includesFile to be used by build script
     */
    private void buildFiles(FilePath workspace,
                                     String testSuiteFile,
                                     ArrayList<TestPriority> sortedTests,
                                     ArrayList<TestPriority> testList,
                                     ArrayList<String> linesForFile)
            throws IOException, InterruptedException {

        try (OutputStream osSuiteFile = workspace.child(testSuiteFile).write();
             OutputStreamWriter oswSuiteFile = new OutputStreamWriter(osSuiteFile, Charsets.UTF_8);
             PrintWriter pwSuiteFile = new PrintWriter(oswSuiteFile);

             OutputStream osPriorityWindowFile = workspace.child(LAST_PRIORITIZED_FILE).write();
             OutputStreamWriter oswPriorityWindowFile = new OutputStreamWriter(osPriorityWindowFile, Charsets.UTF_8);
             PrintWriter pwPriorityWindowFile = new PrintWriter(oswPriorityWindowFile)) {

            for (String line : linesForFile) {
                pwSuiteFile.println(line);

                if (line.equals(ANNOTATION_START_1) || line.equals(ANNOTATION_START_2)) {
                    TestPriority testPriority;
                    for (int i = 0; i < sortedTests.size() - 1; i++) {
                        testPriority = sortedTests.get(i);
                        pwSuiteFile.println(testPriority.getClassName() + ",");
                    }
                    testPriority = sortedTests.get(sortedTests.size()-1);
                    pwSuiteFile.println(testPriority.getClassName());
                    pwSuiteFile.println(ANNOTATION_END);
                }
            }

            for (TestPriority test : testList) {
                pwPriorityWindowFile.println(test.getClassName()
                        + ":" + test.getPreviousPrioritizedBuildNum());
            }

            pwSuiteFile.close();
            pwPriorityWindowFile.close();
        }
    }

    private void setPreviousPrioritizedBuildNums(FilePath workspace,
                                                 BuildListener listener,
                                                 TreeMap<String, TestPriority> dependentTests,
                                                 TreeMap<String, TestPriority> allTests)
            throws IOException, InterruptedException {
        try (InputStream inputStream = workspace.child(LAST_PRIORITIZED_FILE).read();
             InputStreamReader inputStreamReader = new InputStreamReader(inputStream, Charsets.UTF_8);
             BufferedReader bufferedReader = new BufferedReader(inputStreamReader)) {
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                String[] splitLine = line.split(":");
                if (dependentTests.containsKey(splitLine[0]))
                    dependentTests.get(splitLine[0]).setPreviousPrioritizedBuildNum(Integer.parseInt(splitLine[1]));
                if (allTests.containsKey(splitLine[0]))
                    allTests.get(splitLine[0]).setPreviousPrioritizedBuildNum(Integer.parseInt(splitLine[1]));
            }
            bufferedReader.close();
        } catch (FileNotFoundException e) {
            listener.getLogger().println(LAST_PRIORITIZED_FILE + " not found.");
            listener.getLogger().println("Using 0 as previous prioritized build number.");
        }
    }

    /**
     * Collect test names from a build into two lists: found and failed
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

            if (pkgName.equals("(root)"))   // UGH
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

        /*
         * Validate numbers entered for failure and execution windows
         *
        public FormValidation doCheckWindow(@QueryParameter int value)
                throws IOException, ServletException {

        }

        public FormValidation doCheckFailureWindow(@QueryParameter int value)
                throws IOException, ServletException {

         }
         */
        public FormValidation doCheckTestListFile(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("You must set a test list file for execution window to work.");

            return FormValidation.ok();
        }

        public FormValidation doCheckTestReportDir(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("You must set the directory containing test results.");

            return FormValidation.ok();
        }

        public FormValidation doCheckIncludesFileHigh(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("You must set the name of includes file referred to by the build script.");

            return FormValidation.ok();
        }

        public FormValidation doCheckIncludesFileLow(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("You must set the name of includes file referred to by the build script.");

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