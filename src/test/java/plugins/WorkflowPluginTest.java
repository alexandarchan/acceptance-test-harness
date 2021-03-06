/*
 * The MIT License
 *
 * Copyright 2014 Jesse Glick.
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

package plugins;

import java.io.File;
import java.util.concurrent.Callable;
import javax.inject.Inject;
import org.apache.commons.io.FileUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.jenkinsci.test.acceptance.Matchers.hasContent;
import org.jenkinsci.test.acceptance.controller.JenkinsController;
import org.jenkinsci.test.acceptance.controller.LocalController;
import org.jenkinsci.test.acceptance.docker.DockerContainerHolder;
import org.jenkinsci.test.acceptance.docker.fixtures.GitContainer;
import org.jenkinsci.test.acceptance.docker.fixtures.SvnContainer;
import org.jenkinsci.test.acceptance.junit.AbstractJUnitTest;
import org.jenkinsci.test.acceptance.junit.DockerTest;
import org.jenkinsci.test.acceptance.junit.Native;
import org.jenkinsci.test.acceptance.junit.Wait;
import org.jenkinsci.test.acceptance.junit.WithCredentials;
import org.jenkinsci.test.acceptance.junit.WithDocker;
import org.jenkinsci.test.acceptance.junit.WithPlugins;
import org.jenkinsci.test.acceptance.plugins.git.GitRepo;
import org.jenkinsci.test.acceptance.plugins.maven.MavenInstallation;
import org.jenkinsci.test.acceptance.po.Artifact;
import org.jenkinsci.test.acceptance.po.Build;
import org.jenkinsci.test.acceptance.po.DumbSlave;
import org.jenkinsci.test.acceptance.po.WorkflowJob;
import org.jenkinsci.test.acceptance.slave.SlaveController;
import static org.junit.Assert.*;
import static org.junit.Assume.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.Issue;

/**
 * Roughly follows <a href="https://github.com/jenkinsci/workflow-plugin/blob/master/TUTORIAL.md">the tutorial</a>.
 */
public class WorkflowPluginTest extends AbstractJUnitTest {

    @Inject private SlaveController slaveController;
    @Inject DockerContainerHolder<GitContainer> gitServer;
    @Inject DockerContainerHolder<SvnContainer> svn;
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    @Inject JenkinsController controller;

    @WithPlugins("workflow-aggregator@1.1")
    @Test public void helloWorld() throws Exception {
        WorkflowJob job = jenkins.jobs.create(WorkflowJob.class);
        job.script.set("echo 'hello from Workflow'");
        job.sandbox.check();
        job.save();
        job.startBuild().shouldSucceed().shouldContainsConsoleOutput("hello from Workflow");
    }

    @WithPlugins({"workflow-aggregator@2.0", "workflow-cps@2.10", "workflow-basic-steps@2.1", "junit@1.18", "git@2.3"})
    @Test public void linearFlow() throws Exception {
        assumeTrue("This test requires a restartable Jenkins", jenkins.canRestart());
        MavenInstallation.installMaven(jenkins, "M3", "3.1.0");
        final DumbSlave slave = (DumbSlave) slaveController.install(jenkins).get();
        slave.configure(new Callable<Void>() {
            @Override public Void call() throws Exception {
                slave.labels.set("remote");
                return null;
            }
        });
        WorkflowJob job = jenkins.jobs.create(WorkflowJob.class);
        job.script.set(
            "node('remote') {\n" +
            "  git 'https://github.com/jglick/simple-maven-project-with-tests.git'\n" +
            "  def v = version()\n" +
            "  if (v) {\n" +
            "    echo \"Building version ${v}\"\n" +
            "  }\n" +
            "  def mvnHome = tool 'M3'\n" +
            "  withEnv([\"PATH+MAVEN=${mvnHome}/bin\", \"M2_HOME=${mvnHome}\"]) {\n" +
            "    sh 'mvn -B -Dmaven.test.failure.ignore verify'\n" +
            "  }\n" +
            "  input 'Ready to go?'\n" +
            "  step([$class: 'ArtifactArchiver', artifacts: '**/target/*.jar', fingerprint: true])\n" + // TODO Jenkins 2.2+: archiveArtifacts artifacts: '**/target/*.jar', fingerprint: true
            "  junit '**/target/surefire-reports/TEST-*.xml'\n" +
            "}\n" +
            "def version() {\n" +
            "  def matcher = readFile('pom.xml') =~ '<version>(.+)</version>'\n" +
            "  matcher ? matcher[0][1] : null\n" +
            "}");
        job.sandbox.check();
        job.save();
        final Build build = job.startBuild();
        waitFor().until(new Wait.Predicate<Boolean>() {
            @Override public Boolean apply() throws Exception {
                return build.getConsole().contains("Ready to go?");
            }
            @Override public String diagnose(Throwable lastException, String message) {
                return "Console output:\n" + build.getConsole() + "\n";
            }
        });
        build.shouldContainsConsoleOutput("Building version 1.0-SNAPSHOT");
        jenkins.restart();
        // Default 120s timeout of Build.waitUntilFinished sometimes expires waiting for RetentionStrategy.Always to tick (after initial failure of CommandLauncher.launch: EOFException: unexpected stream termination):
        slave.waitUntilOnline(); // TODO rather wait for build output: "Ready to run"
        visit(build.getConsoleUrl());
        clickLink("Proceed");
        try {
            build.shouldSucceed();
        } catch (AssertionError x) {
            // Tests in this project are currently designed to fail at random, so either status is OK.
            // TODO if resultIs were public and there were a disjunction combinator for Matcher we could use it here.
            build.shouldBeUnstable();
        }
        new Artifact(build, "target/simple-maven-project-with-tests-1.0-SNAPSHOT.jar").assertThatExists(true);
        build.open();
        clickLink("Test Result");
        assertThat(driver, hasContent("All Tests"));
    }

    @WithPlugins({"workflow-aggregator@2.0", "workflow-cps@2.10", "workflow-basic-steps@2.1", "parallel-test-executor@1.9", "junit@1.18", "git@2.3"})
    @Native("mvn")
    @Test public void parallelTests() throws Exception {
        for (int i = 0; i < 3; i++) {
            slaveController.install(jenkins);
        }
        WorkflowJob job = jenkins.jobs.create(WorkflowJob.class);
        job.script.set(
            "node('master') {\n" +
            // TODO could be switched to multibranch, in which case this initial `node` is unnecessary, and each branch can just `checkout scm`
            "  git 'https://github.com/jenkinsci/parallel-test-executor-plugin-sample.git'\n" +
            "  stash 'sources'\n" +
            "}\n" +
            "def splits = splitTests count(3)\n" +
            "def branches = [:]\n" +
            "for (int i = 0; i < splits.size(); i++) {\n" +
            "  def exclusions = splits.get(i);\n" +
            "  branches[\"split${i}\"] = {\n" +
            "    node('!master') {\n" +
            "      sh 'rm -rf *'\n" +
            "      unstash 'sources'\n" +
            "      writeFile file: 'exclusions.txt', text: exclusions.join(\"\\n\")\n" +
            // Do not bother with ${tool 'M3'}; would take too long to unpack Maven on all slaves.
            // TODO would be useful for ToolInstallation to support the URL installer, hosting the tool ZIP ourselves somewhere cached.
            "      sh 'mvn -B -Dmaven.test.failure.ignore test'\n" +
            "      junit 'target/surefire-reports/*.xml'\n" +
            "    }\n" +
            "  }\n" +
            "}\n" +
            "parallel branches");
        job.sandbox.check();
        job.save();
        Build build = job.startBuild();
        try {
            build.shouldSucceed();
        } catch (AssertionError x) { // cf. linearFlow
            build.shouldBeUnstable();
        }
        build.shouldContainsConsoleOutput("No record available"); // first run
        build = job.startBuild();
        try {
            build.shouldSucceed();
        } catch (AssertionError x) {
            build.shouldBeUnstable();
        }
        build.shouldContainsConsoleOutput("divided into 3 sets");
    }

    @Category(DockerTest.class)
    @WithDocker
    @WithPlugins({"workflow-aggregator@1.14", "docker-workflow@1.4", "git", "ssh-agent@1.10"})
    @WithCredentials(credentialType=WithCredentials.SSH_USERNAME_PRIVATE_KEY, values={"git", "/org/jenkinsci/test/acceptance/docker/fixtures/GitContainer/unsafe"}, id="gitcreds")
    @Issue("JENKINS-27152")
    @Test public void sshGitInsideDocker() throws Exception {
        // Pending https://github.com/jenkinsci/docker-workflow-plugin/pull/31 (and additional APIs in DockerFixture etc.)
        // it is not possible to run this on a bind-mounted Docker slave, so we cannot verify that paths are local to the slave.
        GitContainer container = gitServer.get();
        String host = container.host();
        int port = container.port();
        GitRepo repo = new GitRepo();
        repo.commit("Initial commit");
        repo.transferToDockerContainer(host, port);
        WorkflowJob job = jenkins.jobs.create(WorkflowJob.class);
        job.script.set(
            "node {ws('" + tmp.getRoot() + "') {\n" + // TODO UNIX_PATH_MAX workaround
            "  docker.image('cloudbees/java-build-tools').inside {\n" +
            "    git url: '" + container.getRepoUrlInsideDocker() + "', credentialsId: 'gitcreds'\n" +
            "    sh 'mkdir ~/.ssh && echo StrictHostKeyChecking no > ~/.ssh/config'\n" +
            "    sshagent(['gitcreds']) {sh 'ls -l $SSH_AUTH_SOCK && git pull origin master'}\n" +
            "  }\n" +
            "}}");
        job.sandbox.check();
        job.save();
        job.startBuild().shouldSucceed();
    }

    @WithPlugins({"workflow-cps-global-lib@2.3", "workflow-basic-steps@2.1", "workflow-job@2.5"})
    @Issue("JENKINS-26192")
    @Test public void grapeLibrary() throws Exception {
        assumeThat("TODO otherwise we would need to set up SSH access to push via Git, which seems an awful hassle", controller, instanceOf(LocalController.class));
        File workflowLibs = /* WorkflowLibRepository.workspace() */ new File(((LocalController) controller).getJenkinsHome(), "workflow-libs");
        // Cf. GrapeTest.useBinary using JenkinsRule:
        FileUtils.write(new File(workflowLibs, "src/pkg/Lists.groovy"),
            "package pkg\n" +
            "@Grab('commons-primitives:commons-primitives:1.0')\n" +
            "import org.apache.commons.collections.primitives.ArrayIntList\n" +
            "static def arrayInt() {new ArrayIntList()}");
        WorkflowJob job = jenkins.jobs.create(WorkflowJob.class);
        job.script.set("echo(/got ${pkg.Lists.arrayInt()}/)");
        job.sandbox.check();
        job.save();
        assertThat(job.startBuild().shouldSucceed().getConsole(), containsString("got []"));
    }

    /** Pipeline analogue of {@link SubversionPluginTest#build_has_changes}. */
    @Category(DockerTest.class)
    @WithDocker
    @WithPlugins({"workflow-cps@2.12", "workflow-job@2.5", "workflow-durable-task-step@2.4", "subversion@2.6"})
    @Test public void subversion() throws Exception {
        final SvnContainer svnContainer = svn.get();
        WorkflowJob job = jenkins.jobs.create(WorkflowJob.class);
        job.script.set("node {svn '" + svnContainer.getUrlUnsaveRepoAtRevision(1) + "'}");
        job.save();
        job.startBuild().shouldSucceed();
        job.configure();
        job.script.set("node {svn '" + svnContainer.getUrlUnsaveRepoAtRevision(2) + "'}");
        job.save();
        Build b2 = job.startBuild().shouldSucceed();
        assertTrue(b2.getChanges().hasChanges());
    }

}
