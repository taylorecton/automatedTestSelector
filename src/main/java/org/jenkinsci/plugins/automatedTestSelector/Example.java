package org.jenkinsci.plugins.automatedTestSelector;

import hudson.Extension;
import hudson.model.RootAction;

@Extension
public class Example implements RootAction {

	@Override
	public String getIconFileName() {
		return "clipboard.png";
	}

	@Override
	public String getDisplayName() {
		return "This Is An Example!";
	}

	@Override
	public String getUrlName() {
		return "http://www.google.com/";
	}

}
