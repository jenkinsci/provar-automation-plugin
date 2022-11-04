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

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.*;
import hudson.model.*;
import hudson.remoting.VirtualChannel;
import hudson.slaves.NodeSpecific;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.tasks._ant.AntConsoleAnnotator;
import hudson.tools.*;
import hudson.util.*;
import jenkins.model.Jenkins;
import jenkins.security.MasterToSlaveCallable;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import javax.servlet.ServletException;
import java.io.File;
import java.io.IOException;
import java.util.*;

public class ProvarAutomation extends Builder {

    /**
     * Identifies {@link ProvarAutomationInstallation} to be used.
     */
    @NonNull
    private final String provarAutomationName;

    @NonNull
    private final String buildFile;
    @NonNull
    private final String testPlan;
    @NonNull
    private final String testFolder;
    public enum Browser {
        // no one likes IE anyway
//        Chrome_Headless, Chrome, Edge, Edge_Legacy, Firefox, IE, Safari
        Chrome_Headless, Chrome, Edge, Edge_Legacy, Firefox, Safari
    }
    public enum SalesforceMetadataCacheSettings {
        Reuse, Refresh, Reload
    }
    public enum ResultsPathSettings {
        Increment, Replace, Fail
    }
    @NonNull
    // default for running in a CI/CD environment is headless Chrome
    private final Browser browser;
    @NonNull
    // default test environment should be either empty or <default>
    private final String environment;
    @NonNull
    private final Secret secretsPassword;
    @NonNull
    private final SalesforceMetadataCacheSettings salesforceMetadataCacheSetting;
    @NonNull
    private final ResultsPathSettings resultsPathSetting;
    @NonNull
    private final String projectName;

    @DataBoundConstructor
    public ProvarAutomation(@NonNull String provarAutomationName,
                            @NonNull String buildFile,
                            @NonNull String testPlan,
                            @NonNull String testFolder,
                            @NonNull String environment,
                            @NonNull Browser browser,
                            @NonNull Secret secretsPassword,
                            @NonNull SalesforceMetadataCacheSettings salesforceMetadataCacheSetting,
                            @NonNull ResultsPathSettings resultsPathSetting,
                            @NonNull String projectName) {
        this.provarAutomationName = provarAutomationName;
        this.buildFile = Util.fixEmptyAndTrim(buildFile);
        this.testPlan = Util.fixEmptyAndTrim(testPlan);
        this.testFolder = Util.fixEmptyAndTrim(testFolder);
        this.browser = browser;
        this.environment = environment;
        this.secretsPassword = Objects.requireNonNull(secretsPassword);
        this.salesforceMetadataCacheSetting = salesforceMetadataCacheSetting;
        this.resultsPathSetting = resultsPathSetting;
        this.projectName = projectName;
    }

    /**
     * Gets the ProvarCLI to invoke,
     * or null to invoke the default one.
     */
    public ProvarAutomationInstallation getProvar() {
        for(ProvarAutomationInstallation i : getDescriptor().getInstallations()) {
            if(provarAutomationName != null && provarAutomationName.equals(i.getName()))
                return i;
        }
        return null;
    }

    @NonNull
    public String getProvarAutomationName() { return provarAutomationName; }
    @NonNull
    public String getBuildFile() { return buildFile; }
    @NonNull
    public String getTestPlan() {
        return testPlan;
    }
    @NonNull
    public String getTestFolder() {
        return testFolder;
    }
    @NonNull
    public String getEnvironment() { return environment; }
    @NonNull
    public Browser getBrowser() { return browser; }
    @NonNull
    public Secret getSecretsPassword() {
        return secretsPassword;
    }
    @NonNull
    public SalesforceMetadataCacheSettings getSalesforceMetadataCacheSetting() { return salesforceMetadataCacheSetting; }
    @NonNull
    public ResultsPathSettings getResultsPathSetting() { return resultsPathSetting; }
    @NonNull
    public String getProjectName() { return projectName; }

    @Override
    public boolean perform(AbstractBuild<?,?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        FilePath workspaceFilePath = build.getWorkspace();
        EnvVars env = build.getEnvironment(listener);
        String buildFile = env.expand(this.buildFile);

        // If (for some reason) they get rid of the build file parameter, let's give em the default
        if (buildFile == null) {
            buildFile = DescriptorImpl.defaultBuildFile;
        } else if (!buildFile.endsWith(".xml")) {
            // append .xml if the buildFile is missing the extension
            buildFile += ".xml";
        }

        listener.getLogger().println("Provar Automation CLI Version: " + provarAutomationName);
        listener.getLogger().println("Project Folder: " + projectName);
        listener.getLogger().println("Running the build file: " + buildFile);
        listener.getLogger().println("Executing test plan: " + testPlan);
        listener.getLogger().println("Executing test folder: " + testFolder);

        listener.getLogger().println("Target environment: " + environment);
        listener.getLogger().println("Target browser: " + browser);
        if (secretsPassword.getPlainText() != null) {
            listener.getLogger().println("Project is encrypted! Thank you for being secure.");
        }
        listener.getLogger().println("Salesforce Metadata Cache Setting: " + salesforceMetadataCacheSetting);
        listener.getLogger().println("Results Path Setting: " + resultsPathSetting);

        listener.getLogger().println("Workspace: " + workspaceFilePath);
        ArgumentListBuilder args = new ArgumentListBuilder();


        // Allow empty build parameters to be used in property replacements.
        // The env.override/overrideAll methods remove the property if it's an empty string.
        for (Map.Entry<String, String> e : build.getBuildVariables().entrySet()) {
            if (e.getValue() != null && e.getValue().length() == 0) {
                env.put(e.getKey(), e.getValue());
            } else {
                env.override(e.getKey(), e.getValue());
            }
        }

        ProvarAutomationInstallation pi = getProvar();
        if (pi == null) {
            args.add(launcher.isUnix() ? "ant" : "ant.bat");
        } else {
            Node node = Computer.currentComputer().getNode();
            if (node == null) {
                throw new AbortException(Messages.ProvarAutomation_NodeOffline());
            }
            pi = pi.forNode(node, listener);
            pi = pi.forEnvironment(env);
            String exe = pi.getExecutable(launcher);
            if (exe == null) {
                throw new AbortException(Messages.ProvarAutomation_NotAProvarDirectory(pi.getName()));
            }
            args.add(launcher.isUnix() ? "ant" : "ant.bat");
        }

        // Some default/empty value handling for test plans/folders
        // ProvarProject/tests/ will run all tests
        if (testPlan != null) {
            env.put("TEST_PLAN", testPlan);
        } else {
            env.put("TEST_PLAN", " ");
        }
        if (testFolder.equalsIgnoreCase("All")) {
            env.put("TEST_FOLDER", "/");
        } else if (testFolder != null) {
            env.put("TEST_FOLDER", testFolder);
        } else {
            env.put("TEST_FOLDER", " ");
        }

        // set up env vars for every parameter
        env.put("PROJECT_WORKSPACE", workspaceFilePath + File.separator + projectName);
        env.put("ENVIRONMENT", environment);
        env.put("BROWSER", browser.name());
        env.put("CACHE_SETTING", salesforceMetadataCacheSetting.name());
        env.put("RESULTS_PATH_SETTING", resultsPathSetting.name());
        env.put("PROJECT_NAME", projectName);

        VariableResolver<String> vr = new VariableResolver.ByMap<>(env);
        FilePath buildFilePath = buildFilePath(build.getModuleRoot(), buildFile, env.expand(projectName));

        if(!buildFilePath.exists()) {
            listener.getLogger().println(Jenkins.getInstance().getRootDir());

            // first check if this appears to be a valid relative path from workspace root
            if (workspaceFilePath != null) {
                FilePath buildFilePath2 = buildFilePath(workspaceFilePath, buildFile, env.expand(projectName));
                if(buildFilePath2.exists()) {
                    // This must be what the user meant. Let it continue.
                    buildFilePath = buildFilePath2;
                } else {
                    // neither file exists. build the build file
                    buildFilePath = launcher.isUnix() ? new FilePath(workspaceFilePath, "/" + buildFile) : new FilePath(workspaceFilePath, "\\" + buildFile);
                    //throw new AbortException("Unable to find build script at "+ buildFilePath);
                }
            } else {
                throw new AbortException("Workspace is not available. Agent may be disconnected.");
            }
        }
        listener.getLogger().println("BUILD FILE PATH:" + buildFilePath);

        if(buildFile != null) {
            args.add("-file", buildFilePath.getName());
        }
        Set<String> sensitiveVars = build.getSensitiveBuildVariables();
        sensitiveVars.add("ProvarSecretsPassword");
        args.addKeyValuePairs("-D", build.getBuildVariables(), sensitiveVars);
        String properties = "ProvarSecretsPassword=" + secretsPassword.getPlainText();
        args.addKeyValuePairsFromPropertyString("-D", properties, vr, sensitiveVars);

        if(pi != null) {
            pi.buildEnvVars(env);
        }
        if(!launcher.isUnix()) {
            args = toWindowsCommand(args.toWindowsCommand());
        }

        long startTime = System.currentTimeMillis();
        try {
            AntConsoleAnnotator aca = new AntConsoleAnnotator(listener.getLogger(), build.getCharset());
            int r;
            try {
                r = launcher.launch().cmds(args).envs(env).stdout(aca).pwd(buildFilePath.getParent()).join();
            } finally {
                aca.forceEol();
            }
            return r==0;
        } catch (IOException e) {
            Util.displayIOException(e,listener);

            String errorMessage = Messages.ProvarAutomation_AntExecutionFailed();
            if(pi == null && (System.currentTimeMillis()-startTime)<1000) {
                if(getDescriptor().getInstallations() == null)
                    // looks like the user didn't configure any Ant installation
                    errorMessage += Messages.ProvarAutomation_GlobalConfigNeeded();
                else
                    // There are Ant installations configured but the project didn't pick it
                    errorMessage += Messages.ProvarAutomation_ProjectConfigNeeded();
            }
            throw new AbortException(errorMessage);
        }
    }

    private static FilePath buildFilePath(FilePath base, String buildFile, String projectName) {
        return base.child(projectName + File.separator + "ANT" + File.separator + buildFile);
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    /**
     * Backward compatibility by checking the number of parameters
     *
     */
    protected static ArgumentListBuilder toWindowsCommand(ArgumentListBuilder args) {
        List<String> arguments = args.toList();

        if (arguments.size() > 3) { // "cmd.exe", "/C", "ant.bat", ...
            // branch for core equals or greater than 1.654
            boolean[] masks = args.toMaskArray();
            // don't know why are missing single quotes.

            args = new ArgumentListBuilder();
            args.add(arguments.get(0), arguments.get(1)); // "cmd.exe", "/C", ...

            int size = arguments.size();
            for (int i = 2; i < size; i++) {
                String arg = arguments.get(i).replaceAll("^(-D[^\" ]+)=$", "$0\"\"");

                if (masks[i]) {
                    args.addMasked(arg);
                } else {
                    args.add(arg);
                }
            }
        } else {
            // branch for core under 1.653 (backward compatibility)
            // For some reason, ant on Windows rejects empty parameters but unix does not.
            // Add quotes for any empty parameter values:
            List<String> newArgs = new ArrayList<String>(args.toList());
            newArgs.set(newArgs.size() - 1, newArgs.get(newArgs.size() - 1).replaceAll(
                    "(?<= )(-D[^\" ]+)= ", "$1=\"\" "));
            args = new ArgumentListBuilder(newArgs.toArray(new String[newArgs.size()]));
        }

        return args;
    }

    @Symbol("provarAutomation")
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        @CopyOnWrite
        private volatile ProvarAutomationInstallation[] installations = new ProvarAutomationInstallation[0];

        public DescriptorImpl() {
            load();
        }

        private DescriptorImpl(Class<? extends ProvarAutomation> clazz) {
            super(clazz);
        }

        /**
         * Obtains the {@link ProvarAutomationInstallation.DescriptorImpl} instance.
         */
        public ProvarAutomationInstallation.DescriptorImpl getToolDescriptor() {
            return ToolInstallation.all().get(ProvarAutomationInstallation.DescriptorImpl.class);
        }

        public ProvarAutomationInstallation[] getInstallations() {
            return Arrays.copyOf(installations, installations.length);
        }

        public void setInstallations(ProvarAutomationInstallation... provarAutomationInstallations) {
            this.installations = provarAutomationInstallations;
            save();
        }

        public FormValidation doCheckBuildFile(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.warning(Messages.ProvarAutomation_DescriptorImpl_warnings_missingBuildFile());
            return FormValidation.validateRequired(value);
        }

        public FormValidation doCheckTestPlan(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.warning(Messages.ProvarAutomation_DescriptorImpl_warnings_missingTestPlan());

            return FormValidation.ok();
        }

        public FormValidation doCheckTestFolder(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.warning(Messages.ProvarAutomation_DescriptorImpl_warnings_missingTestFolder());

            return FormValidation.ok();
        }

        public FormValidation doCheckSecretsPassword(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.warning(Messages.ProvarAutomation_DescriptorImpl_warnings_noSecretsPassword());

            return FormValidation.ok();
        }

        public FormValidation doCheckProjectName(@QueryParameter String value)
                throws IOException, ServletException {

            if (value.length() == 0)
                return FormValidation.ok(Messages.ProvarAutomation_DescriptorImpl_warnings_projectFolderMissing());

            return FormValidation.ok();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return Messages.ProvarAutomation_DescriptorImpl_DisplayName();
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            req.bindJSON(this, json);
            return true;
        }


        public static final String defaultProvarAutomationName = "";
        public static final String defaultProjectName = "ProvarProject";
        public static final String defaultBuildFile = "build.xml";
        public static final Browser defaultBrowser = Browser.Chrome_Headless;
        public static final String defaultEnvironment = "";
        public static final String defaultTestPlan = "Regression";
        public static final String defaultTestFolder = "All";
        public static final SalesforceMetadataCacheSettings defaultSalesforceMetadataCacheSetting = SalesforceMetadataCacheSettings.Reuse;
        public static final ResultsPathSettings defaultResultsPathSetting = ResultsPathSettings.Increment;

        public ListBoxModel doFillBrowserItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("Chrome (Headless)", Browser.Chrome_Headless.name());
            items.add("Chrome", Browser.Chrome.name());
            items.add("Edge", Browser.Edge.name());
            items.add("Edge (Legacy)", Browser.Edge_Legacy.name());
            items.add("Firefox", Browser.Firefox.name());
            // no one likes IE anyway
            //items.add("Internet Explorer", Browser.IE.name());
            items.add("Safari", Browser.Safari.name());
            return items;
        }

        public ListBoxModel doFillSalesforceMetadataCacheSettingItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("Reuse", SalesforceMetadataCacheSettings.Reuse.name());
            items.add("Refresh", SalesforceMetadataCacheSettings.Refresh.name());
            items.add("Reload", SalesforceMetadataCacheSettings.Reload.name());
            return items;
        }

        public ListBoxModel doFillResultsPathSettingItems(){
            ListBoxModel items = new ListBoxModel();
            items.add("Increment", ResultsPathSettings.Increment.name());
            items.add("Replace", ResultsPathSettings.Replace.name());
            items.add("Fail", ResultsPathSettings.Fail.name());
            return items;
        }
    }

    /**
     * Represents the Provar installation on the system.
     */
    public static final class ProvarAutomationInstallation extends ToolInstallation implements
            EnvironmentSpecific<ProvarAutomationInstallation>, NodeSpecific<ProvarAutomationInstallation> {

        @Deprecated
        private transient String provarHome;
        @DataBoundConstructor
        public ProvarAutomationInstallation(String name, String home, List<? extends ToolProperty<?>> properties) {
            super(name, launderHome(StringUtils.defaultString(home)), properties);
        }

        private static String launderHome(String home) {
            if(home.endsWith("/") || home.endsWith("\\")) {
                return home.substring(0,home.length()-1);
            } else {
                return home;
            }
        }

        @Override
        public void buildEnvVars(EnvVars env) {
            env.put("PROVAR_HOME", getHome());
        }

        /**
         * Gets the executable path of Provar on the given target system.
         */
        public String getExecutable(Launcher launcher) throws IOException, InterruptedException {
            VirtualChannel channel = launcher.getChannel();
            if (channel == null) {
                throw new IOException("offline?");
            }
            return channel.call(new GetExecutable(getHome()));
        }
        private static class GetExecutable extends MasterToSlaveCallable<String, IOException> {
            private static final long serialVersionUID = 1234567L;
            private final String rawHome;
            GetExecutable(String rawHome) {
                this.rawHome = rawHome;
            }
            @Override public String call() throws IOException {
                String home = Util.replaceMacro(rawHome, EnvVars.masterEnvVars);
                File exe = new File(home, "ant" + File.separator + "ant-provar.jar");
                if (exe.exists()) {
                    File f = new File(exe.getParent());
                    return f.getParent();
                }

                return null;
            }
        }

        /**
         * Returns true if the executable exists.
         */
        public boolean getExists() throws IOException, InterruptedException {
            return getExecutable(new Launcher.LocalLauncher(TaskListener.NULL))!=null;
        }

        private static final long serialVersionUID = 1L;

        public ProvarAutomationInstallation forEnvironment(EnvVars environment) {
            return new ProvarAutomationInstallation(getName(), environment.expand(getHome()), getProperties().toList());
        }

        public ProvarAutomationInstallation forNode(Node node, TaskListener log) throws IOException, InterruptedException {
            return new ProvarAutomationInstallation(getName(), translateFor(node, log), getProperties().toList());
        }

        @Symbol("provarAutomation")
        @Extension
        public static class DescriptorImpl extends ToolDescriptor<ProvarAutomationInstallation> {

            @Override
            public String getDisplayName() {
                return Messages.ProvarAutomation_ToolInstallation_DescriptorImpl_DisplayName();
            }

            @Override
            public ProvarAutomationInstallation[] getInstallations() {
                return Jenkins.get().getDescriptorByType(ProvarAutomation.DescriptorImpl.class).getInstallations();
            }

            @Override
            public void setInstallations(ProvarAutomationInstallation... installations) {
                Jenkins.get().getDescriptorByType(ProvarAutomation.DescriptorImpl.class).setInstallations(installations);
            }

            @Override
            public List<? extends ToolInstaller> getDefaultInstallers() {
                return Collections.singletonList(new ProvarAutomationInstaller(null));
            }

            /**
             * Checks if the PROVAR_HOME is valid.
             */
            public FormValidation doCheckHome(@QueryParameter File value) {
                // this can be used to check the existence of a file on the server, so needs to be protected
                if(!Jenkins.get().hasPermission(Jenkins.ADMINISTER))
                    return FormValidation.ok();

                if(value.getPath().equals(""))
                    return FormValidation.ok();

                if(!value.isDirectory())
                    return FormValidation.error(Messages.ProvarAutomation_NotADirectory(value));

                File antJar = new File(value,"ant/ant-provar.jar");
                if(!antJar.exists())
                    return FormValidation.error(Messages.ProvarAutomation_NotAProvarDirectory(value));

                return FormValidation.ok();
            }

            public FormValidation doCheckName(@QueryParameter String value) {
                return FormValidation.validateRequired(value);
            }
        }

        public static class ConverterImpl extends ToolConverter {
            public ConverterImpl(XStream2 xstream) { super(xstream); }
            @Override protected String oldHomeField(ToolInstallation obj) {
                return ((ProvarAutomationInstallation)obj).provarHome;
            }
        }
    }


    /**
     * Automatic Provar installer.
     */
    public static class ProvarAutomationInstaller extends DownloadFromUrlInstaller {
        @DataBoundConstructor
        public ProvarAutomationInstaller(String id) {
            super(id);
        }

        @Symbol("provarOnline")
        @Extension
        public static final class DescriptorImpl extends DownloadFromUrlInstaller.DescriptorImpl<ProvarAutomationInstaller> {

            public String getDisplayName() {
                return Messages.ProvarAutomation_InstallProvarOnline();
            }

            @Override
            public boolean isApplicable(Class<? extends ToolInstallation> toolType) {
                return toolType== ProvarAutomationInstallation.class;
            }
        }
    }
}
