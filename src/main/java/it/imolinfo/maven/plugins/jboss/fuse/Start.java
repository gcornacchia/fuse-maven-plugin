/*
 * Copyright 2016 Imola Informatica S.P.A..
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package it.imolinfo.maven.plugins.jboss.fuse;

import it.imolinfo.maven.plugins.jboss.fuse.options.Cfg;
import it.imolinfo.maven.plugins.jboss.fuse.options.Feature;
import it.imolinfo.maven.plugins.jboss.fuse.utils.ExceptionManager;
import it.imolinfo.maven.plugins.jboss.fuse.utils.KarafJMXConnector;
import java.io.File;
import java.io.IOException;
import java.util.List;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MalformedObjectNameException;
import javax.management.ReflectionException;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.TabularDataSupport;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Mojo;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Parameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mojo(name = "start", requiresProject = false, defaultPhase = LifecyclePhase.PRE_INTEGRATION_TEST)
public class Start extends AbstractGoal {

    private static final Logger LOG = LoggerFactory.getLogger(Start.class);

    private static final String USER_PROPERTIES_FILE_NAME = "users.properties";
    private static final String DEFAULT_ADMIN_CONFIG = "#admin=admin,admin,manager,viewer,Monitor, Operator, Maintainer, Deployer, Auditor, Administrator, SuperUser";
    private static final String ADMIN_CONFIG = "admin=admin,admin,manager,viewer,Monitor, Operator, Maintainer, Deployer, Auditor, Administrator, SuperUser";

    @Parameter
    private Long timeout;

    @Parameter
    private List<Cfg> cfg;

    @Parameter
    private List<Feature> features;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        LOG.info("Start jboss-fuse");
        timeout = timeout == null ? TIMEOUT : timeout;
        download();
        initBinDirectory();
        disableAdminPassword();
        configure();
        startJbosFuse();
        features();
        deployDependencies();
        deploy(project.getArtifact().getFile(), timeout);
    }

    private void startJbosFuse() throws MojoExecutionException, MojoFailureException {
        Runtime runtime = Runtime.getRuntime();
        try {
            runtime.exec(START_CMD).waitFor();
        } catch (IOException | InterruptedException ex) {
            new Shutdown().execute();
            throw new MojoExecutionException(ex.getMessage(), ex);
        }
    }

    private void configure() throws MojoExecutionException, MojoFailureException {
        if (cfg != null) {
            for (Cfg configuration : cfg) {
                try {
                    configure(configuration);
                } catch (IOException ex) {
                    throw new MojoExecutionException(ex.getMessage(), ex);
                }
            }
        }
    }

    private void features() throws MojoExecutionException, MojoFailureException {
        if (features != null) {
            for (Feature feature : features) {
                LOG.info("Deploy feature {}", feature.getFeature());
                try {
                    KarafJMXConnector jMXConnector = KarafJMXConnector.getInstance(timeout);
                    jMXConnector.featureInstall(feature.getFeature());
                } catch (ReflectionException | MBeanException | InstanceNotFoundException | IOException | MalformedObjectNameException ex) {
                    LOG.error(ex.getMessage(), ex);
                    new Shutdown().execute();
                    throw new MojoExecutionException(ex.getMessage(), ex);
                }
            }
        }
    }

    private void deployDependencies() throws MojoExecutionException, MojoFailureException {
        LOG.info("Deploy plugin dependencies");
        PluginDescriptor pluginDescriptor = (PluginDescriptor) super.getPluginContext().get("pluginDescriptor");
        String pluginGroupId = pluginDescriptor.getGroupId();
        String pluginArtifactiId = pluginDescriptor.getArtifactId();
        for (Plugin plugin : project.getBuild().getPlugins()) {
            if (plugin.getGroupId().equals(pluginGroupId) && plugin.getArtifactId().equals(pluginArtifactiId)) {
                for (Dependency dependency : plugin.getDependencies()) {
                    LOG.info("Deploy {}:{}:{}", dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
                    String path = String.format("%s/%s/%s/%s", super.settings.getLocalRepository(), dependency.getGroupId().replaceAll("\\.", "/"),
                            dependency.getArtifactId(), dependency.getVersion());
                    File dependencyDirectory = new File(path);
                    for (File dependencyFile : dependencyDirectory.listFiles()) {
                        if (FilenameUtils.getExtension(dependencyFile.getAbsolutePath()).toLowerCase().equals(JAR)) {
                            deploy(dependencyFile, timeout);
                        }
                    }
                }
            }
        }
    }

    private static void configure(Cfg configuration) throws IOException, MojoExecutionException {
        ExceptionManager.throwMojoExecutionExceptionIfNull(configuration.getOption(), "Null option");
        File destination = new File(String.format("%s/%s", JBOSS_FUSE_DIRECTORY, configuration.getDestination()));
        switch (configuration.getOption()) {
            case COPY:
                copy(configuration, destination);
                break;
            case APPEND:
                append(configuration, destination);
                break;
            case REPLACE:
                replace(configuration, destination);
                break;
            default:
                throw new MojoExecutionException("Invalid option");
        }
    }

    private static void disableAdminPassword() throws MojoExecutionException {
        LOG.info("Disable admin password");
        File usersFile = new File(String.format("%s/%s", JBOSS_FUSE_ETC_DIRECTORY.getAbsolutePath(), USER_PROPERTIES_FILE_NAME));
        replace(usersFile, DEFAULT_ADMIN_CONFIG, ADMIN_CONFIG);
    }

    private static void copy(Cfg configuration, File destination) throws MojoExecutionException {
        ExceptionManager.throwMojoExecutionExceptionIfNull(configuration.getSource(), "Null source File");
        ExceptionManager.throwMojoExecutionExceptionIfNull(configuration.getDestination(), "Null destination");
        ExceptionManager.throwMojoExecutionException(!destination.exists(), String.format("%s not exists", destination.getAbsolutePath()));
        ExceptionManager.throwMojoExecutionException(!configuration.getSource().exists(), "Source file not exists");
        ExceptionManager.throwMojoExecutionException(!destination.isDirectory(), String.format("%s is file", destination.getAbsolutePath()));
        LOG.info("Add {} in {}", configuration.getSource().getAbsolutePath(), configuration.getDestination());
        try {
            FileUtils.copyFileToDirectory(configuration.getSource(), destination);
        } catch (IOException ex) {
            throw new MojoExecutionException(ex.getMessage(), ex);
        }
    }

    private static void append(Cfg configuration, File destination) throws MojoExecutionException {
        ExceptionManager.throwMojoExecutionExceptionIfNull(configuration.getProperties(), "Null properties");
        ExceptionManager.throwMojoExecutionExceptionIfNull(configuration.getDestination(), "Null destination");
        ExceptionManager.throwMojoExecutionException(!destination.exists(), String.format("%s not exists", destination.getAbsolutePath()));
        ExceptionManager.throwMojoExecutionException(destination.isDirectory(), String.format("%s is directory", destination.getAbsolutePath()));
        LOG.info("Append properties in {}", destination.getAbsolutePath());
        try {
            StringBuilder sb = new StringBuilder(FileUtils.readFileToString(destination, "UTF-8"));
            configuration.getProperties().keySet().stream().forEach((key) -> {
                String propertyName = String.valueOf(key);
                sb.append(String.format("%s = %s\n", key, configuration.getProperties().getProperty(propertyName)));
            });
            FileUtils.write(destination, sb.toString(), "UTF-8");
        } catch (IOException ex) {
            throw new MojoExecutionException(ex.getMessage(), ex);
        }
    }

    private static void replace(Cfg configuration, File destination) throws MojoExecutionException {
        ExceptionManager.throwMojoExecutionExceptionIfNull(configuration.getTarget(), "Null target");
        ExceptionManager.throwMojoExecutionExceptionIfNull(configuration.getDestination(), "Null destination");
        ExceptionManager.throwMojoExecutionException(!destination.exists(), String.format("%s not exists", destination.getAbsolutePath()));
        ExceptionManager.throwMojoExecutionExceptionIfNull(configuration.getReplacement(), "Null replacement");
        ExceptionManager.throwMojoExecutionException(destination.isDirectory(), String.format("%s is directory", destination.getAbsolutePath()));
        LOG.info("Replace {} with {} in {}", configuration.getTarget(), configuration.getReplacement(), destination.getAbsolutePath());
        replace(destination, configuration.getTarget(), configuration.getReplacement());
    }

    private static void replace(File destination, String target, String replacement) throws MojoExecutionException {
        try {
            String text = FileUtils.readFileToString(destination, "UTF-8").replace(target, replacement);
            FileUtils.write(destination, text, "UTF-8");
        } catch (IOException ex) {
            throw new MojoExecutionException(ex.getMessage(), ex);
        }
    }

    private static void deploy(File deployment, Long timeout) throws MojoExecutionException, MojoFailureException {
        try {
            KarafJMXConnector fuseJMXConnector = KarafJMXConnector.getInstance(timeout);
            Long bundleId = fuseJMXConnector.install(deployment, Boolean.TRUE);
            TabularDataSupport tabularDataSupport = fuseJMXConnector.list();
            CompositeDataSupport compositeDataSupport = (CompositeDataSupport) tabularDataSupport.get(new Object[]{bundleId});
            String id = String.valueOf(compositeDataSupport.get("ID"));
            String name = String.valueOf(compositeDataSupport.get("Name"));
            String state = String.valueOf(compositeDataSupport.get("State"));
            String version = String.valueOf(compositeDataSupport.get("Version"));
            LOG.info("{} {} {} {}", id, name, version, state);
            if (!state.toUpperCase().equals("ACTIVE")) {
                new Shutdown().execute();
                throw new MojoExecutionException(String.format("Invalid bundle state %s [%s %s %s]", state, id, name, version));
            }
            if (compositeDataSupport.containsKey("Blueprint")) {
                String blueprintState = String.valueOf(compositeDataSupport.get("Blueprint"));
                LOG.info("{} {} {} {} {}", id, name, version, state, blueprintState);
                if (!blueprintState.trim().isEmpty() && !blueprintState.toUpperCase().equals("CREATED")) {
                    new Shutdown().execute();
                    throw new MojoExecutionException(String.format("Invalid blueprint state %s [%s %s %s]", blueprintState, id, name, version));
                }
            }
            if (compositeDataSupport.containsKey("Spring")) {
                String springState = String.valueOf(compositeDataSupport.get("Spring"));
                LOG.info("{} {} {} {} {}", id, name, version, state, springState);
                if (!springState.trim().isEmpty() && !springState.toUpperCase().equals("CREATED")) {
                    new Shutdown().execute();
                    throw new MojoExecutionException(String.format("Invalid spring state %s [%s %s %s]", springState, id, name, version));
                }
            }
        } catch (IOException | MalformedObjectNameException | InstanceNotFoundException | MBeanException | ReflectionException ex) {
            new Shutdown().execute();
            throw new MojoExecutionException(ex.getMessage(), ex);
        }
    }
}
