package org.jenkinsci.plugins.automatedTestSelector;

import hudson.AbortException;
import hudson.Launcher;
import hudson.Extension;
import hudson.FilePath;
// import hudson.util.FormValidation;
import hudson.model.AbstractProject;
import hudson.model.AbstractBuild;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.scm.ChangeLogSet.Entry;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import jenkins.tasks.SimpleBuildStep;
// import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
// import org.kohsuke.stapler.StaplerRequest;
// import org.kohsuke.stapler.QueryParameter;

import java.util.Collection;
// import javax.servlet.ServletException;
// import java.io.IOException;

/**
 * File: AutomatedTestSelector.java
 * Author: Taylor Ecton
 * Class purpose: Extend Jenkins to allow automation of test selection;
 *                This plugin is designed to determine which test cases
 *                need to be run in order to reduce the amount of resources
 *                used in performing testing of builds.
 * Last modified: 06-01-2017
 */

public class AutomatedTestSelector extends Builder implements SimpleBuildStep {
    /**
     * MEMBER VARIABLES WILL GO HERE
     */
    private final int failureWindow;
    private final int executionWindow;

    @DataBoundConstructor
    public AutomatedTestSelector(int failureWindow, int executionWindow) {
        this.executionWindow = executionWindow;
        this.failureWindow = failureWindow;
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


    /**
     * internal classes and member functions
     * (probably going to need @Override public boolean perform)
     */
    @Override
    public void perform(Run<?,?> build, FilePath workspace, Launcher launcher, TaskListener listener) throws AbortException {
        listener.getLogger().println("You set the failure window to " + failureWindow);
        listener.getLogger().println("You set the execution window to " + executionWindow);

        Collection<String> changedFiles = null;
        try {
            changedFiles = getChangedFiles(build, listener);
        } catch (AbortException e) {
            e.printStackTrace();
        }

        if (changedFiles != null) {
            for (String path : changedFiles) {
                listener.getLogger().println("path: " + path);
            }
        }
    }

    private Collection<String> getChangedFiles(Run<?,?> build, TaskListener listener) throws AbortException {
        listener.getLogger().println("Started getChangedFiles()");
        Collection<String> changedFiles = null;
        ItemGroup<?> ig = build.getParent().getParent();
        nextItem: for(Item item : ig.getItems()) {
            for (Job<?, ?> job : item.getAllJobs()) {
                if (job instanceof AbstractProject<?,?>) {
                    AbstractProject<?,?> p = (AbstractProject<?,?>) job;
                    for (AbstractBuild<?,?> b : p.getBuilds()) {
                        for (Entry entry : b.getChangeSet()) {
                            if (entry.getAffectedPaths() != null) {
                                changedFiles = entry.getAffectedPaths();
                                break nextItem;
                            }
                        }
                    }
                }
            }
        }
        return changedFiles;
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
         */

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // this builder can be used with all project types
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Automated test selection";
        }
    }
}
