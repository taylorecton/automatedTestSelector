# automatedTestSelector

I am currently working on a Jenkins plugin to study the effectiveness of regression test selection (RTS)
and test case prioritization (TCP) techniques in continuous integration (CI) environments.

The project structure:
  - pom.xml
  + src/main/
    + java/org/jenkinsci/plugins/automatedTestSelector/
      - DependencyAnalysis.java
      - RegressionTestSelector.java
      - TestCasePrioritizer.java
      - TestPriority.java
    + resources/
      - index.jelly
      + org/jenkinsci/plugins/automatedTestSelector/
        + RegressionTestSelector/
          - config.jelly
          - help-executionWindow.html
          - help-failureWindow.html
          - help-testReportDir.html
          - help-testSuiteFile.html
          - help-understandDatabasePath.html
          - help-useDependencyAnalysis.html
        + TestCasePrioritizer
          - config.jelly
          - help-executionWindow.html
          - help-failureWindow.html
          - help-prioritizationWindow.html
          - help-testReportDir.html
          - help-testSuiteFile.html
          - help-understandDatabasePath.html
          - help-useDependencyAnalysis.html
