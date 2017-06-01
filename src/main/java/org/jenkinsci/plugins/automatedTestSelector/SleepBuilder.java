package org.jenkinsci.plugins.automatedTestSelector;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.*;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.io.IOException;

/**
 * Created by taylorecton on 5/25/17.
 * and stuff
 */
public class SleepBuilder extends Builder {
    private long time;

    @DataBoundConstructor
    public SleepBuilder(long time) {
        this.time = time;
    }

    public long getTime() {
        return time;
    }

    public void setTime(long time) {
        this.time = time;
    }

    @Override
    public boolean perform(Build<?,?> build, Launcher launcher, BuildListener listener)
            throws InterruptedException, IOException {
        listener.getLogger().println("Sleeping for: " + time + "ms");
        Thread.sleep(time);
        return true;
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return aClass == FreeStyleProject.class;
        }

        @Override
        public String getDisplayName() {
            return "Sleep Builder";
        }
    }

    public FormValidation doCheckTime(@QueryParameter String time) {
        try {
            if (Long.valueOf(time) < 0) {
                return FormValidation.error("Please enter a positive number.");
            } else {
                return FormValidation.ok();
            }
        } catch (NumberFormatException) {
            return FormValidation.error("Please enter a number.");
        }
    }
}
