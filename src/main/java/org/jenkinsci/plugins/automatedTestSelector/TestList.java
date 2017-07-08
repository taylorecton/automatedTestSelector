package org.jenkinsci.plugins.automatedTestSelector;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Taylor Ecton
 */
public class TestList {
    private List<String> testList;

    public TestList(String filePath) {
        this.testList = getTestListFromFile(filePath);
    }

    public List<String> getTestList() {
        return testList;
    }

    private List<String> getTestListFromFile(String filePath) {
        List<String> testList = new ArrayList<>();
        String test;

        if (filePath == null || filePath.isEmpty()) {
            testList.add("Invalid file name");
            return testList;
        }

        File testFile = new File(filePath);

        if (!testFile.exists()) {
            testList.add(filePath + ": File does not exist or Jenkins does not have permission");
            return testList;
        }

        if (!testFile.isFile()) {
            testList.add(filePath + ": Not a file");
            return testList;
        }

        try {
            FileReader fileReader = new FileReader(testFile.getPath());
            BufferedReader bufferedReader = new BufferedReader(fileReader);

            while ((test = bufferedReader.readLine()) != null) {
                testList.add(test);
            }

            bufferedReader.close();

            return testList;
        } catch (IOException e) {
            return null;
        }

    }

}
