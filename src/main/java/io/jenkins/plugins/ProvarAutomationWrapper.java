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

import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.console.ConsoleLogFilter;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.EnvironmentSpecific;
import hudson.model.JDK;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.slaves.NodeSpecific;
import hudson.tasks.Ant;
import hudson.tasks._ant.AntConsoleAnnotator;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.tools.ToolInstallation;

import java.io.*;
import java.util.Map;
import edu.umd.cs.findbugs.annotations.NonNull;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildWrapper;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

public class ProvarAutomationWrapper extends SimpleBuildWrapper {

    private String installation;
    private String jdk;
    private String ant;

    @DataBoundConstructor
    public ProvarAutomationWrapper() {}

    public String getInstallation() {
        return installation;
    }

    @DataBoundSetter
    public void setInstallation(String installation) {
        this.installation = Util.fixEmpty(installation);
    }

    public String getJdk() {
        return jdk;
    }

    @DataBoundSetter
    public void setJdk(String jdk) {
        this.jdk = Util.fixEmpty(jdk);
    }

    public String getAnt() {
        return ant;
    }

    @DataBoundSetter
    public void setAnt(String ant) {
        this.ant = Util.fixEmpty(ant);
    }


    @Override
    public void setUp(Context context, Run<?, ?> build, FilePath workspace, Launcher launcher, TaskListener listener, EnvVars initialEnvironment) throws IOException, InterruptedException {
        if (installation != null) {
            // you get a 403 Forbidden for some reason without this property sent to the HTTP Client
            System.setProperty("http.agent", "Mozilla/5.0 (Windows; U; MSIE 9.0; Windows NT 9.0; en-US)");
            toolEnv(context, installation, Jenkins.get().getDescriptorByType(ProvarAutomation.DescriptorImpl.class).getInstallations(), workspace, listener, initialEnvironment);
        }
        if (jdk != null) {
            toolEnv(context, jdk, Jenkins.get().getDescriptorByType(JDK.DescriptorImpl.class).getInstallations(), workspace, listener, initialEnvironment);
        }
        if (ant != null) {
            toolEnv(context, ant, Jenkins.get().getDescriptorByType(Ant.DescriptorImpl.class).getInstallations(), workspace, listener, initialEnvironment);
        }
    }

    private static void toolEnv(Context context, @NonNull String tool, ToolInstallation[] tools, FilePath workspace, TaskListener listener, EnvVars initialEnvironment) throws IOException, InterruptedException {
        ToolInstallation inst = null;
        for (ToolInstallation _inst : tools) {
            if (_inst.getName().equals(tool)) {
                inst = _inst;
                break;
            }
        }
        if (inst == null) {
            throw new AbortException("no such tool ‘" + tool + "’");
        }
        if (inst instanceof NodeSpecific) {
            Computer computer = workspace.toComputer();
            if (computer != null) {
                Node node = computer.getNode();
                if (node != null) {
                    inst = (ToolInstallation) ((NodeSpecific) inst).forNode(node, listener);
                }
            }
        }
        if (inst instanceof EnvironmentSpecific) {
            inst = (ToolInstallation) ((EnvironmentSpecific) inst).forEnvironment(initialEnvironment);
        }
        EnvVars modified = new EnvVars();
        inst.buildEnvVars(modified);
        for (Map.Entry<String, String> entry : modified.entrySet()) {
            context.env(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public ConsoleLogFilter createLoggerDecorator(Run<?, ?> build) {
        return AntConsoleAnnotator.asConsoleLogFilter();
    }

    @Symbol("withProvarAutomation")
    @Extension
    public static class DescriptorImpl extends BuildWrapperDescriptor {

        @Override
        public String getDisplayName() {
            return "With Provar Automation";
        }

        @Override
        public boolean isApplicable(AbstractProject<?, ?> item) {
            return true;
        }
    }
}
