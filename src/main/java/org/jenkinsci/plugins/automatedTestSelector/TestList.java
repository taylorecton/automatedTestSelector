package org.jenkinsci.plugins.automatedTestSelector;

import java.io.*;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Taylor Ecton
 */
public class TestList {
    private ArrayList<String> testList;

    public TestList(String filePath) {
        this.testList = getTestListFromFile(filePath);
    }

    public ArrayList<String> getTestList() {
        return testList;
    }

    private ArrayList<String> getTestListFromFile(String filePath) {
        ArrayList<String> testList = new ArrayList<>();
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
