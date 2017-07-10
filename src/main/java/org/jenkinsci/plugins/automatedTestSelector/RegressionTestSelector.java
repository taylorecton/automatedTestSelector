package org.jenkinsci.plugins.automatedTestSelector;

import com.google.common.collect.ImmutableSet;

import com.sun.org.apache.xpath.internal.operations.Bool;
import hudson.AbortException;
import hudson.Launcher;
import hudson.Extension;
import hudson.FilePath;
// import hudson.util.FormValidation;

import hudson.model.*;

import hudson.scm.ChangeLogSet.Entry;

import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.junit.ClassResult;
import hudson.tasks.test.AbstractTestResultAction;
import hudson.tasks.test.TabulatedResult;
import hudson.tasks.test.TestResult;

import hudson.util.FormValidation;
import jenkins.tasks.SimpleBuildStep;

// import net.sf.json.JSONObject;
import org.apache.commons.io.Charsets;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.*;
import java.nio.Buffer;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Collections;
// import javax.servlet.ServletException;
// import java.io.IOException;

/**
 * @author Taylor Ecton
 */

public class RegressionTestSelector extends Builder {

    public static final ImmutableSet<Result> RESULTS_OF_BUILDS_TO_CONSIDER = ImmutableSet.of(Result.SUCCESS, Result.UNSTABLE);

    private final int failureWindow;
    private final int executionWindow;

    private final String testListFile;
    private final String testReportDir;
    private final String includesFile;

    @DataBoundConstructor
    public RegressionTestSelector(int failureWindow, int executionWindow, String testListFile, String testReportDir, String includesFile) {
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
    public boolean perform(AbstractBuild<?,?> build, Launcher launcher, BuildListener listener) throws AbortException, InterruptedException, IOException {
        listener.getLogger().println("Running regression test selector with a failure window of " + failureWindow);
        listener.getLogger().println("and an execution window of " + executionWindow);

        FilePath workspace = build.getWorkspace();
        if (workspace == null)
            throw new AbortException("No workspace");

        FilePath reportDir = workspace.child(testReportDir);
        reportDir.deleteContents();

        ArrayList<String> allTests = getAllTests(workspace, testListFile);
        ArrayList<String> selectedTests = selectTests(build, listener, allTests);

        buildIncludesFile(workspace, includesFile, selectedTests);

/*  LEAVING THIS BLOCK OF CODE AS AN EXAMPLE OF GETTING CHANGED FILES

        for (Entry entry : build.getChangeSet()) {
            if (entry.getAffectedPaths() != null) {
                changedFiles = entry.getAffectedPaths();
            }
        }

        if (changedFiles != null) {
            for (String path : changedFiles) {
                listener.getLogger().println("path: " + path);
            }
        } else {
            listener.getLogger().println("No changed files in repository");
        }
*/
        return true;
    }

    /**
     * Creates a list of all tests from the testListFile provided by user
     */
    private ArrayList<String> getAllTests(FilePath workspace, String testListFile)
            throws IOException, InterruptedException {

        ArrayList<String> allTests = new ArrayList<>();

        // try to read the file, read in all of the test names and add them to the list
        try (InputStream inputStream = workspace.child(testListFile).read();
             InputStreamReader inputStreamReader = new InputStreamReader(inputStream, Charsets.UTF_8);
             BufferedReader bufferedReader = new BufferedReader(inputStreamReader)) {
            String testName;
            while ((testName = bufferedReader.readLine()) != null)
                allTests.add(testName);
        }

        return allTests;
    }

    /**
     * Returns a list of tests selected for execution
     */
    private ArrayList<String> selectTests(Run<?, ?> b, TaskListener listener, ArrayList<String> tests) {
        ArrayList<String> selectedTests = new ArrayList<>();
        ArrayList<String> foundTests = new ArrayList<>();

        // continue iterating until i reaches failureWindow or executionWindow, whichever is larger
        for (int i = 0; i < this.getFailureWindow() || i < this.getExecutionWindow(); i++) {
            b = b.getPreviousBuild();

            if (b == null) break;
            if (!RESULTS_OF_BUILDS_TO_CONSIDER.contains(b.getResult())) continue; // build failed = no test results

            AbstractTestResultAction testResultAction = b.getAction(AbstractTestResultAction.class);
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
                    if (!selectedTests.contains(testName))
                        selectedTests.add(testName);
                }
            }
        }

        // tests not found have not been executed within execution window and should be selected
        for (String test : tests) {
            if (!foundTests.contains(test))
                selectedTests.add(test);
        }

        return selectedTests;
    }

    /**
     * Generates includesFile to be used by build script
     */
    private void buildIncludesFile(FilePath workspace, String includesFile, ArrayList<String> selectedTests)
            throws IOException, InterruptedException {
        try (OutputStream os = workspace.child(includesFile).write();
             OutputStreamWriter osw = new OutputStreamWriter(os, Charsets.UTF_8);
             PrintWriter pw = new PrintWriter(osw)) {
            for (String fileName : selectedTests) {
                pw.println(fileName);
            }
        }
    }

    /**
     * Collect test names from a build into two lists: found and failed
     */
    static private void collect(TestResult r, ArrayList<String> failed, ArrayList<String> found,
                                Boolean withinExWindow, Boolean withinFailWindow) {
        if (r instanceof ClassResult) {
            ClassResult cr = (ClassResult) r;
            String className;
            String pkgName = cr.getParent().getName();

            if (pkgName.equals("(root)"))   // UGH
                pkgName = "";
            else
                pkgName += '.';
            className = pkgName + cr.getName() + ".class";

            if (withinExWindow && !found.contains(className)) // don't add if outside of execution window
                found.add(className);

            if (withinFailWindow && cr.getFailCount() > 0) // don't add if outside of failure window
                failed.add(className);

            return; // no need to go deeper
        }
        if (r instanceof TabulatedResult) {
            TabulatedResult tr = (TabulatedResult) r;
            for (TestResult child : tr.getChildren()) {
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
         * Validate numbers entered for failure and excution windows
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
                return FormValidation.error("Please include a Test List File.");

            return FormValidation.ok();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // this builder can be used with all project types
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Regression Test Selector";
        }
    }
}
