/*
 * The MIT License
 *
 * Copyright (c) 2004-2010, Sun Microsystems, Inc., Kohsuke Kawaguchi, Tom Huybrechts, Yahoo! Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.jenkins.plugins;

import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.Result;
import hudson.util.Secret;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.JenkinsRule.WebClient;

import static org.junit.Assert.*;

public class ProvarAutomationTest {

    @Rule
    public JenkinsRule jr = new JenkinsRule();

    final String buildFile = "build.xml";
    final String testPlan = "Regression";
    final String testFolder = "all";
    final String environment = "Dev";
    static String windowsLicensePath = "C:/Users/" + System.getProperty("user.name") + "/Provar/.licenses";
    static String unixLicensePath = System.getenv("HOME") + "/Provar/.licenses";
    static String osName = System.getProperty("os.name");
    final String licensePath = osName.contains("Windows") ? windowsLicensePath : unixLicensePath;
    final ProvarAutomation.Browser browser = ProvarAutomation.Browser.Safari;
    final Secret secretsPassword = Secret.fromString("ProvarSecretsPasssword");
    final ProvarAutomation.SalesforceMetadataCacheSettings salesforceMetadataCacheSetting = ProvarAutomation.SalesforceMetadataCacheSettings.Reload;
    final ProvarAutomation.ResultsPathSettings resultsPathSetting = ProvarAutomation.ResultsPathSettings.Replace;
    final String projectName = "Hackathon2022";
    final String provarAutomationName = "";
    final int quietPeriod = 5;

    @Test
    public void testConfigRoundtrip() throws Exception {
        FreeStyleProject p = jr.createFreeStyleProject();
        p.getBuildersList().add(new ProvarAutomation(provarAutomationName, buildFile, testPlan, testFolder, environment, browser, secretsPassword, salesforceMetadataCacheSetting, resultsPathSetting, projectName, licensePath));

        WebClient webClient = jr.createWebClient();
        HtmlPage page = webClient.getPage(p,"configure");

        HtmlForm form = page.getFormByName("config");
        jr.submit(form);

        ProvarAutomation pa = p.getBuildersList().get(ProvarAutomation.class);
        assertNotNull(pa);
        assertNull(pa.getProvar());
        assertEquals(buildFile,pa.getBuildFile());
        assertEquals(testPlan,pa.getTestPlan());
        assertEquals(testFolder,pa.getTestFolder());
        assertEquals(environment,pa.getEnvironment());
        assertEquals(browser,pa.getBrowser());
        assertEquals(secretsPassword,pa.getSecretsPassword());
        assertEquals(salesforceMetadataCacheSetting,pa.getSalesforceMetadataCacheSetting());
        assertEquals(resultsPathSetting,pa.getResultsPathSetting());
        assertEquals(projectName,pa.getProjectName());
        assertEquals(licensePath, pa.getLicensePath());
    }
    // TODO: Add tests for validations of file paths, specifically the build file.
    // TODO: Add testing for downloading of Provar CLI, extraction, and env var set.
    // TODO: Add testing for NOT downloading Provar CLI if tool is set or if we can locate a valid PROVAR_HOME
    // TODO: Add testing for setting all environment variables from build step parameters
    // TODO: Add testing for failing gracefully if the Provar Version does not exist

    @Test
    public void testBuild() throws Exception {
        FreeStyleProject project = jr.createFreeStyleProject();
        ProvarAutomation builder = new ProvarAutomation(provarAutomationName, buildFile, testPlan, testFolder, environment, browser, secretsPassword, salesforceMetadataCacheSetting, resultsPathSetting, projectName, licensePath);
        project.getBuildersList().add(builder);
        FreeStyleBuild build = jr.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(quietPeriod).get());
        jr.assertLogContains("Running the build file: " + buildFile, build);
        jr.assertLogContains("Executing test plan: " + testPlan, build);
        jr.assertLogContains("Executing test folder: " + testFolder, build);
        jr.assertLogContains("Target browser: " + browser, build);
        jr.assertLogContains("Target environment: " + environment, build);
        jr.assertLogContains("Salesforce Metadata Cache Setting: " + salesforceMetadataCacheSetting, build);
        jr.assertLogContains("Results Path Setting: " + resultsPathSetting, build);
        jr.assertLogContains("Project Folder: " + projectName, build);
        jr.assertLogContains("Project is encrypted! Thank you for being secure.", build);
        jr.assertLogContains("Execution license path being used: " + licensePath, build);
    }

    // TODO: Add test for license path and license file
//    @Test
//    public void testLicense() throws Exception {
///*        FreeStyleProject project = jr.createFreeStyleProject();
//        ProvarAutomation builder = new ProvarAutomation(provarAutomationName, buildFile, testPlan, testFolder, environment, browser, secretsPassword, salesforceMetadataCacheSetting, resultsPathSetting, projectName, licensePath);
//        project.getBuildersList().add(builder);
//        FreeStyleBuild build = jr.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(quietPeriod).get());
//        Path path = Paths.get("does-not-exist.txt");
//        assertFalse(Files.exists(path));*/
//    }

    @Test
    public void testScriptedPipeline() throws Exception {
        String agentLabel = "my-agent";
        jr.createOnlineSlave(Label.get(agentLabel));
        WorkflowJob job = jr.createProject(WorkflowJob.class, "test-scripted-pipeline");
        String pipelineScript
                = "node {\n"
                + "  provarAutomation  provarAutomationName: '" + provarAutomationName + "',\n"
                + "  buildFile: '" + buildFile + "',\n"
                + "  testPlan: '" + testPlan + "',\n"
                + "  testFolder: '" + testFolder + "',\n"
                + "  environment: '" + environment + "',\n"
                + "  browser: '" + browser + "',\n"
                + "  secretsPassword: '" + secretsPassword + "',\n"
                + "  salesforceMetadataCacheSetting: '" + salesforceMetadataCacheSetting + "',\n"
                + "  resultsPathSetting: '" + resultsPathSetting + "',\n"
                + "  projectName: '" + projectName + "',\n"
                + "  licensePath: '" + licensePath + "'\n"
                + "}";
        job.setDefinition(new CpsFlowDefinition(pipelineScript, true));

        WorkflowRun completedBuild = job.scheduleBuild2(quietPeriod).get();
        jr.assertBuildStatusSuccess(completedBuild);
        jr.assertLogContains("Start of Pipeline", completedBuild);
    }

}