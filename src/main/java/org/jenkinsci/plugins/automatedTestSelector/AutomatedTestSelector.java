package org.jenkinsci.plugins.automatedTestSelector;

import hudson.Launcher;
import hudson.Extension;
import hudson.FilePath;
import hudson.util.FormValidation;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.IOException;

/**
 * File: AutomatedTestSelector.java
 * Author: Taylor Ecton
 * Class purpose: Extend Jenkins to allow automation of test selection;
 *                This plugin is designed to determine which test cases
 *                need to be run in order to reduce the amount of resources
 *                used in performing testing of builds.
 * Last modified: 06-01-2017
 */
public class AutomatedTestSelector extends Builder {
    /**
     * MEMBER VARIABLES WILL GO HERE
     */

    @DataBoundConstructor
    public AutomatedTestSelector(/* parameter list */) {

    }

    /**
     * Getters and Setters
     */

    /**
     * internal classes and member functions
     * (probably going to need @Override public boolean perform)
     */

    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Builder> {
        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Automated test selection";
        }
    }
}
