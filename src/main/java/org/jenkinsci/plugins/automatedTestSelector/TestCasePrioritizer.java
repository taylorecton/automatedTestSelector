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

    private final int failureWindow;
    private final int executionWindow;

    private final String testListFile;
    private final String testReportDir;
    private final String includesFile;

    @DataBoundConstructor
    public TestCasePrioritizer(int failureWindow, int executionWindow, String testListFile,
                                  String testReportDir, String includesFile) {
        this.executionWindow = executionWindow;
        this.failureWindow = failureWindow;

        this.testListFile = testListFile;
        this.testReportDir = testReportDir;
        this.includesFile = includesFile;
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

    public String getTestListFile() {
        return testListFile;
    }

    public String getTestReportDir() {
        return testReportDir;
    }

    public String getIncludesFile() {
        return includesFile;
    }


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

        TreeMap<String, TestPriority> allTests = getAllTests(workspace, testListFile);
        ArrayList<TestPriority> sortedTests = prioritizeTests(build, listener, allTests);

        listener.getLogger().println(sortedTests.size() + " out of " + allTests.size() + " selected for execution");

        buildIncludesFile(workspace, includesFile, sortedTests);

        return true;
    }

    /**
     * Creates a list of all tests from the testListFile provided by user
     */
    private TreeMap<String, TestPriority> getAllTests(FilePath workspace, String testListFile)
            throws IOException, InterruptedException {

        TreeMap<String, TestPriority> allTests = new TreeMap<>();

        // try to read the file, read in all of the test names and add them to the list
        try (InputStream inputStream = workspace.child(testListFile).read();
             InputStreamReader inputStreamReader = new InputStreamReader(inputStream, Charsets.UTF_8);
             BufferedReader bufferedReader = new BufferedReader(inputStreamReader)) {
            String testName;
            while ((testName = bufferedReader.readLine()) != null) {
                TestPriority testPriority = new TestPriority(testName);
                allTests.put(testName, testPriority);
            }
        }

        return allTests;
    }

    /**
     * Returns a list of tests selected for execution
     */
    private ArrayList<TestPriority> prioritizeTests(Run<?, ?> build, TaskListener listener, TreeMap<String, TestPriority> tests) {
        ArrayList<String> foundTests = new ArrayList<>();
        ArrayList<String> testNames = new ArrayList<>(tests.keySet());

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
    private void buildIncludesFile(FilePath workspace, String includesFile, ArrayList<TestPriority> sortedTests)
            throws IOException, InterruptedException {
        try (OutputStream os = workspace.child(includesFile).write();
             OutputStreamWriter osw = new OutputStreamWriter(os, Charsets.UTF_8);
             PrintWriter pw = new PrintWriter(osw)) {
            for (TestPriority testPriority : sortedTests) {
                pw.println(testPriority.getClassName());
            }
        }
    }

    /**
     * Collect test names from a build into two lists: found and failed
     */
    static private void collect(TestResult testResult, ArrayList<String> failed, ArrayList<String> found,
                                Boolean withinExWindow, Boolean withinFailWindow) {
        if (testResult instanceof ClassResult) {
            ClassResult classResult = (ClassResult) testResult;
            String className;
            String pkgName = classResult.getParent().getName();

            if (pkgName.equals("(root)"))   // UGH
                pkgName = "";
            else
                pkgName += '.';
            className = pkgName + classResult.getName() + ".class";

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

        public FormValidation doCheckIncludesFile(@QueryParameter String value)
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