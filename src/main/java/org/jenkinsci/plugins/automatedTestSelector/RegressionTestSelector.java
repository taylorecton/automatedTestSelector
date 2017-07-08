package org.jenkinsci.plugins.automatedTestSelector;

import com.google.common.collect.ImmutableSet;

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
    /**
     * MEMBER VARIABLES WILL GO HERE
     */
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
     * Getters and Setters
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
     * internal classes and member functions
     */
    @Override
    public boolean perform(AbstractBuild<?,?> build, Launcher launcher, BuildListener listener) throws AbortException, InterruptedException, IOException {
        listener.getLogger().println("You set the failure window to " + failureWindow);
        listener.getLogger().println("You set the execution window to " + executionWindow);


        FilePath workspace = build.getWorkspace();
        FilePath reportDir = workspace.child(testReportDir);
        reportDir.deleteContents();

        ArrayList<String> allTests = new ArrayList<>();
        try (InputStream inputStream = workspace.child(testListFile).read();
             InputStreamReader inputStreamReader = new InputStreamReader(inputStream, Charsets.UTF_8);
             BufferedReader bufferedReader = new BufferedReader(inputStreamReader)) {
            String testName;
            while ((testName = bufferedReader.readLine()) != null)
                allTests.add(testName);
        }

        ArrayList<String> selectedTests = selectTests(build, listener, allTests);

        try (OutputStream os = workspace.child(includesFile).write();
             OutputStreamWriter osw = new OutputStreamWriter(os, Charsets.UTF_8);
             PrintWriter pw = new PrintWriter(osw)) {
            for (String fileName : selectedTests) {
                pw.println(fileName);
            }
        }
/*
        Collection<String> changedFiles = null;
        TestList testList = new TestList(testListFile);

        for (String test : testList.getTestList()) {
            listener.getLogger().println("test: " + test);
        }

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

    private ArrayList<String> selectTests(Run<?, ?> b, TaskListener listener, ArrayList<String> tests) {
        ArrayList<String> selectedTests = new ArrayList<>();
        ArrayList<String> foundTests = new ArrayList<>();

        for (int i = 0; i < this.getFailureWindow() || i < this.getExecutionWindow(); i++) {
            b = b.getPreviousBuild();

            if (b == null) break;
            if (!RESULTS_OF_BUILDS_TO_CONSIDER.contains(b.getResult())) continue;

            AbstractTestResultAction tra = b.getAction(AbstractTestResultAction.class);
            if (tra == null) continue;

            Object o = tra.getResult();
            if (o instanceof TestResult) {
                TestResult result = (TestResult) o;
                ArrayList<String> testsFailedThisBuild = new ArrayList<>();
                collect(result, testsFailedThisBuild, foundTests);
                for (String testName : testsFailedThisBuild) {
                    if (!selectedTests.contains(testName))
                        selectedTests.add(testName);
                }
            }
        }

        for (String test : tests) {
            if (!foundTests.contains(test))
                selectedTests.add(test);
        }

        return selectedTests;
    }

    static private void collect(TestResult r, ArrayList<String> failed, ArrayList<String> found) {
        if (r instanceof ClassResult) {
            ClassResult cr = (ClassResult) r;
            String className;
            String pkgName = cr.getParent().getName();

            if (pkgName.equals("(root)"))   // UGH
                pkgName = "";
            else
                pkgName += '.';
            className = pkgName + cr.getName() + ".class";

            if (!found.contains(className))
                found.add(className);

            if (cr.getFailCount() > 0)
                failed.add(className);

            return; // no need to go deeper
        }
        if (r instanceof TabulatedResult) {
            TabulatedResult tr = (TabulatedResult) r;
            for (TestResult child : tr.getChildren()) {
                collect(child, failed, found);
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
