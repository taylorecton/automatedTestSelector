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

    private final int failureWindow;
    private final int executionWindow;

    private final String testSuiteFile;
    private final String testReportDir;

    //private final String includesFile;

    @DataBoundConstructor
    public TestCasePrioritizer(int failureWindow,
                               int executionWindow,
                               String testSuiteFile,
                               String testReportDir /* ,
                               String includesFile */) {
        this.executionWindow = executionWindow;
        this.failureWindow = failureWindow;

        this.testSuiteFile = testSuiteFile;
        this.testReportDir = testReportDir;
        // this.includesFile = includesFile;
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

        FilePath workspace = build.getWorkspace();
        if (workspace == null)
            throw new AbortException("No workspace");

        FilePath reportDir = workspace.child(testReportDir);
        reportDir.deleteContents();

        ArrayList<String> linesForFile = new ArrayList<>();
        TreeMap<String, TestPriority> allTests = getAllTests(workspace, testSuiteFile, linesForFile);
        ArrayList<TestPriority> sortedTests = prioritizeTests(build, listener, allTests);

        buildTestSuiteFile(workspace, testSuiteFile, sortedTests, linesForFile);

        return true;
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
        }

        /* FOR DEBUG
        for (String line : linesForFile)
            System.out.println(line);
        */

        return allTests;
    }

    /**
     * Returns a list of tests selected for execution
     */
    private ArrayList<TestPriority> prioritizeTests(Run<?, ?> build,
                                                    TaskListener listener,
                                                    TreeMap<String, TestPriority> tests) {
        ArrayList<String> foundTests = new ArrayList<>();
        ArrayList<String> testNames = new ArrayList<>(tests.keySet());

        /* DEBUG CODE
        for (String t : testNames)
            System.out.println("keys: " + t);
        */

        // continue iterating until i reaches failureWindow or executionWindow, whichever is larger
        for (int i = 0; i < this.getFailureWindow() || i < this.getExecutionWindow(); i++) {
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
                    // System.out.println(testName); // <-- for debugging
                    tests.get(testName).setHighPriority();
                }
            }
        }

        // tests not found have not been executed within execution window and should be selected
        for (String test : testNames) {
            if (!foundTests.contains(test))
                tests.get(test).setHighPriority();
        }

        // sort the tests
        ArrayList<TestPriority> sortedTests = new ArrayList<>(tests.values());
        Collections.sort(sortedTests);

        return sortedTests;
    }

    /**
     * Generates includesFile to be used by build script
     */
    private void buildTestSuiteFile(FilePath workspace,
                                     String testSuiteFile,
                                     ArrayList<TestPriority> sortedTests,
                                     ArrayList<String> linesForFile)
            throws IOException, InterruptedException {

        try (OutputStream osSuiteFile = workspace.child(testSuiteFile).write();
             OutputStreamWriter oswSuiteFile = new OutputStreamWriter(osSuiteFile, Charsets.UTF_8);
             PrintWriter pwSuiteFile = new PrintWriter(oswSuiteFile)) {

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