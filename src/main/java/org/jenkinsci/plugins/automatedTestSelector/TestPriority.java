package org.jenkinsci.plugins.automatedTestSelector;

/**
 * Created by taylorecton on 7/12/17.
 */
public class TestPriority implements Comparable<TestPriority> {
    private String className;
    private int priority;
    private int previousPrioritizedBuildNum;

    public TestPriority(String name) {
        className = name;
        priority = 1;
        previousPrioritizedBuildNum = 0;
    }

    public String getClassName() {
        return className;
    }

    public int getPriority() {
        return priority;
    }

    public int getPreviousPrioritizedBuildNum() {
        return previousPrioritizedBuildNum;
    }

    public void setHighPriority() {
        priority = 0;
    }

    public void setPreviousPrioritizedBuildNum(int buildNum) {
        previousPrioritizedBuildNum = buildNum;
    }

    public int compareTo(TestPriority that) {
        int p = this.priority - that.priority;
        if (p < 0) return -1;
        if (p > 0) return 1;
        return 0;
    }

    public boolean equals(Object that) {
        // NEVER USED
        // implemented to remove warning from findbugs
        if (that instanceof TestPriority) {
            TestPriority other = (TestPriority) that;
            if ((this.className.equals(other.className)) &&
                    (this.priority == other.priority) &&
                    (this.previousPrioritizedBuildNum == other.previousPrioritizedBuildNum))
                return true;
        }
        return false;
    }

    public int hashCode() {
        // NEVER USED
        // implemented to remove warning from findbugs
        assert false : "hashCode not designed";
        return 42; // any arbitrary constant will do
    }
}
