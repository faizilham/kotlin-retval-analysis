package com.faizilham.kotlin.retval

// import org.demiurg906.kotlin.plugin.runners.AbstractBoxTest
import com.faizilham.kotlin.retval.runners.AbstractDiagnosticTest
import org.jetbrains.kotlin.generators.generateTestGroupSuiteWithJUnit5

fun main() {
    generateTestGroupSuiteWithJUnit5 {
        testGroup(testDataRoot = "testData", testsRoot = "test-gen") {
            testClass<AbstractDiagnosticTest> {
                model("diagnostics")
            }
        }
    }
}
