# automatedTestSelector

I am currently working on a Jenkins plugin to study the effectiveness of regression test selection (RTS)
and test case prioritization (TCP) techniques in continuous integration (CI) environments.

The relevant files to my work:
  - pom.xml
  + src/main/
    + java/org/jenkinsci/plugins/automatedTestSelector/
      - DependencyAnalysis.java
      - RegressionTestSelector.java
      - TestList.java // <-- this file currently not being used; may be deleted
      - TestCasePrioritizer.java
      - TestPriority.java
    + resources/
      - index.jelly
      + org/jenkinsci/plugins/automatedTestSelector/
        + RegressionTestSelector/
          - config.jelly
          - help-executionWindow.html
          - help-failureWindow.html
          - help-testSuiteFile.html
          - help-testListFile.html
          - help-testReportDir.html
        + TestCasePrioritizer
          - config.jelly
          - help-executionWindow.html
          - help-failureWindow.html
          - help-prioritizationWindow.html
          - help-testReportDir.html
          - help-testSuiteFile.html
