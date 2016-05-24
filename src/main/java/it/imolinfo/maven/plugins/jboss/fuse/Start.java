package it.imolinfo.maven.plugins.jboss.fuse;

import it.imolinfo.maven.plugins.jboss.fuse.options.Cfg;
import it.imolinfo.maven.plugins.jboss.fuse.utils.ExceptionManager;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.FileUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.util.IOUtil;

@Mojo(name = "start", requiresProject = false, defaultPhase = LifecyclePhase.PRE_INTEGRATION_TEST)
public class Start extends AbstractGoal {

    private static final String USER_PROPERTIES_FILE_NAME = "users.properties";
    private static final String DEFAULT_ADMIN_CONFIG = "#admin=admin,admin,manager,viewer,Monitor, Operator, Maintainer, Deployer, Auditor, Administrator, SuperUser";
    private static final String ADMIN_CONFIG = "admin=admin,admin,manager,viewer,Monitor, Operator, Maintainer, Deployer, Auditor, Administrator, SuperUser";

    @Parameter
    private Long timeout;

    @Parameter
    private List<Cfg> cfg;

    public Start() {
        super();
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        LOG.info("Start jboss-fuse");
        timeout = timeout == null ? DEFAULT_START_TIMEOUT : timeout;
        download();
        initBinDirectory();
        disableAdminPassword();
        configure();
        startJbosFuse();
    }

    private void startJbosFuse() throws MojoExecutionException, MojoFailureException {
        Runtime runtime = Runtime.getRuntime();
        try {
            runtime.exec(START_CMD);
            String statusStr;
            Process status;
            Long startTime = System.currentTimeMillis();
            do {
                status = runtime.exec(STATUS_CMD);
                status.waitFor(DEFAULT_STATUS_TIMEOUT, TimeUnit.MILLISECONDS);
                statusStr = IOUtil.toString(status.getInputStream());
                LOG.info(String.format("Wait for fuse: Status: %s", statusStr));
                Thread.sleep(SLEEP);
                if (System.currentTimeMillis() - startTime > timeout) {
                    new Shutdown().execute();
                    throw new MojoExecutionException("Timeout...");
                }
            } while (!statusStr.contains(RUNNING));
            LOG.info(String.format("JBoss Fuse started...Check ssh port %d", SSH_PORT));
            startTime = System.currentTimeMillis();
            Boolean sshAvailable;
            do {
                if (System.currentTimeMillis() - startTime > SSH_TIMEOUT) {
                    new Shutdown().execute();
                    throw new MojoExecutionException("SSH timeout...");
                }
                Thread.sleep(SLEEP);
            } while (!(sshAvailable = checkSSHPort()));
            if (!sshAvailable) {
                throw new MojoExecutionException(String.format("SSH port %d unavailable", SSH_PORT));
            }
            LOG.info(String.format("SSH port %d available", SSH_PORT));
        } catch (Exception ex) {
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

    private static void configure(Cfg configuration) throws IOException, MojoExecutionException {
        ExceptionManager.throwMojoExecutionExceptionIfNull(configuration.getOption(), "Null option");
        ExceptionManager.throwMojoExecutionExceptionIfNull(configuration.getDestination(), "Null destination");
        File destination = new File(String.format("%s/%s", JBOSS_FUSE_DIRECTORY, configuration.getDestination()));
        ExceptionManager.throwMojoExecutionException(!destination.exists(), String.format("%s not exists", destination.getAbsolutePath()));
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
        ExceptionManager.throwMojoExecutionException(!configuration.getSource().exists(), "Source file not exists");
        ExceptionManager.throwMojoExecutionException(!destination.isDirectory(), String.format("%s is file", destination.getAbsolutePath()));
        LOG.info(String.format("Add %s in %s", configuration.getSource().getAbsolutePath(), configuration.getDestination()));
        try {
            FileUtils.copyFileToDirectory(configuration.getSource(), destination);
        } catch (IOException ex) {
            throw new MojoExecutionException(ex.getMessage(), ex);
        }
    }

    private static void append(Cfg configuration, File destination) throws MojoExecutionException {
        ExceptionManager.throwMojoExecutionExceptionIfNull(configuration.getProperties(), "Null properties");
        ExceptionManager.throwMojoExecutionException(destination.isDirectory(), String.format("%s is directory", destination.getAbsolutePath()));
        LOG.info(String.format("Append properties in %s", destination.getAbsolutePath()));
        try {
            StringBuilder sb = new StringBuilder(FileUtils.readFileToString(destination));
            configuration.getProperties().keySet().stream().forEach((key) -> {
                String propertyName = String.valueOf(key);
                sb.append(String.format("%s = %s\n", key, configuration.getProperties().getProperty(propertyName)));
            });
            FileUtils.write(destination, sb.toString());
        } catch (IOException ex) {
            throw new MojoExecutionException(ex.getMessage(), ex);
        }
    }

    private static void replace(Cfg configuration, File destination) throws MojoExecutionException {
        ExceptionManager.throwMojoExecutionExceptionIfNull(configuration.getTarget(), "Null target");
        ExceptionManager.throwMojoExecutionExceptionIfNull(configuration.getReplacement(), "Null replacement");
        ExceptionManager.throwMojoExecutionException(destination.isDirectory(), String.format("%s is directory", destination.getAbsolutePath()));
        LOG.info(String.format("Replace %s with %s in %s", configuration.getTarget(), configuration.getReplacement(), destination.getAbsolutePath()));
        replace(destination, configuration.getTarget(), configuration.getReplacement());
    }

    private static void replace(File destination, String target, String replacement) throws MojoExecutionException {
        try {
            String text = FileUtils.readFileToString(destination).replace(target, replacement);
            FileUtils.write(destination, text);
        } catch (IOException ex) {
            throw new MojoExecutionException(ex.getMessage(), ex);
        }
    }

    private static Boolean checkSSHPort() throws MojoExecutionException {
        try {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(LOCALHOST, SSH_PORT), SSH_TIMEOUT);
                return Boolean.TRUE;
            }
        } catch (Exception ex) {
            return Boolean.FALSE;
        }
    }
}
