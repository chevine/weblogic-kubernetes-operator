// Copyright (c) 2022, 2023, Oracle and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.spec.InvalidKeySpecException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;

import oracle.kubernetes.common.logging.MessageKeys;
import oracle.kubernetes.operator.calls.UnrecoverableCallException;
import oracle.kubernetes.operator.helpers.ClientPool;
import oracle.kubernetes.operator.helpers.HelmAccess;
import oracle.kubernetes.operator.http.BaseServer;
import oracle.kubernetes.operator.http.metrics.MetricsServer;
import oracle.kubernetes.operator.http.rest.BaseRestServer;
import oracle.kubernetes.operator.logging.LoggingContext;
import oracle.kubernetes.operator.logging.LoggingFacade;
import oracle.kubernetes.operator.logging.LoggingFactory;
import oracle.kubernetes.operator.tuning.TuningParameters;
import oracle.kubernetes.operator.utils.PathSupport;
import oracle.kubernetes.operator.work.Component;
import oracle.kubernetes.operator.work.Container;
import oracle.kubernetes.operator.work.ContainerResolver;
import oracle.kubernetes.operator.work.Engine;
import oracle.kubernetes.operator.work.Fiber.CompletionCallback;
import oracle.kubernetes.operator.work.Packet;
import oracle.kubernetes.operator.work.Step;
import oracle.kubernetes.utils.SystemClock;

/** An abstract base main class for the operator and the webhook. */
public abstract class BaseMain {
  static final LoggingFacade LOGGER = LoggingFactory.getLogger("Operator", "Operator");

  static final String GIT_BUILD_VERSION_KEY = "git.build.version";
  static final String GIT_BRANCH_KEY = "git.branch";
  static final String GIT_COMMIT_KEY = "git.commit.id.abbrev";
  static final String GIT_BUILD_TIME_KEY = "git.build.time";

  static final Container container = new Container();
  static final ThreadFactory threadFactory = new WrappedThreadFactory();
  static ScheduledExecutorService wrappedExecutorService =
      Engine.wrappedExecutorService(container);  // non-final to allow change in unit tests
  static final AtomicReference<OffsetDateTime> lastFullRecheck =
      new AtomicReference<>(SystemClock.now());
  static final Semaphore shutdownSignal = new Semaphore(0);

  static final File deploymentHome;
  static final File probesHome;
  final CoreDelegate delegate;

  private final AtomicReference<BaseServer> restServer = new AtomicReference<>();
  private final AtomicReference<BaseServer> metricsServer = new AtomicReference<>();

  static {
    try {
      // suppress System.err since we catch all necessary output with Logger
      OutputStream output = new FileOutputStream("/dev/null");
      PrintStream nullOut = new PrintStream(output);
      System.setErr(nullOut);

      ClientPool.initialize(threadFactory);

      // Simplify debugging the operator by allowing the setting of the operator
      // top-level directory using either an env variable or a property. In the normal,
      // container-based use case these values won't be set and the operator will with the
      // /operator directory.
      String deploymentHomeLoc = HelmAccess.getHelmVariable("DEPLOYMENT_HOME");
      if (deploymentHomeLoc == null) {
        deploymentHomeLoc = System.getProperty("deploymentHome", "/deployment");
      }
      deploymentHome = new File(deploymentHomeLoc);

      String probesHomeLoc = HelmAccess.getHelmVariable("PROBES_HOME");
      if (probesHomeLoc == null) {
        probesHomeLoc = System.getProperty("probesHome", "/probes");
      }
      probesHome = new File(probesHomeLoc);

      TuningParameters.initializeInstance(wrappedExecutorService, new File(deploymentHome, "config"));
    } catch (IOException e) {
      LOGGER.warning(MessageKeys.EXCEPTION, e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Return the build properties generated by the git-commit-id-plugin.
   */
  static Properties getBuildProperties() {
    try (final InputStream stream = BaseMain.class.getResourceAsStream("/version.properties")) {
      Properties buildProps = new Properties();
      buildProps.load(stream);
      return buildProps;
    } catch (IOException e) {
      LOGGER.warning(MessageKeys.EXCEPTION, e);
      return null;
    }
  }

  BaseMain(CoreDelegate delegate) {
    this.delegate = delegate;
  }

  void startDeployment(Runnable completionAction) {
    try {
      delegate.runSteps(new Packet(), createStartupSteps(), completionAction);
    } catch (Throwable e) {
      LOGGER.warning(MessageKeys.EXCEPTION, e);
    }
  }

  void stopDeployment(Runnable completionAction) {
    Step shutdownSteps = createShutdownSteps();
    if (shutdownSteps != null) {
      try {
        delegate.runSteps(new Packet(), shutdownSteps, new ReleaseShutdownSignalRunnable(completionAction));
        acquireShutdownSignal();
      } catch (Throwable e) {
        LOGGER.warning(MessageKeys.EXCEPTION, e);
      }
    } else if (completionAction != null) {
      completionAction.run();
    }
  }

  private class ReleaseShutdownSignalRunnable implements Runnable {
    final Runnable inner;

    ReleaseShutdownSignalRunnable(Runnable inner) {
      this.inner = inner;
    }

    @Override
    public void run() {
      if (inner != null) {
        inner.run();
      }
      releaseShutdownSignal();
    }
  }

  void markReadyAndStartLivenessThread() {
    try {
      new DeploymentReady(delegate).create();

      logStartingLivenessMessage();
      // every five seconds we need to update the last modified time on the liveness file
      wrappedExecutorService.scheduleWithFixedDelay(
              new DeploymentLiveness(delegate), 5, 5, TimeUnit.SECONDS);
    } catch (IOException io) {
      LOGGER.severe(MessageKeys.EXCEPTION, io);
    }
  }

  void startRestServer(Container container)
      throws UnrecoverableKeyException, CertificateException, IOException, NoSuchAlgorithmException,
      KeyStoreException, InvalidKeySpecException, KeyManagementException {
    BaseRestServer value = createRestServer();
    restServer.set(value);
    value.start(container);
  }

  abstract BaseRestServer createRestServer();

  // For test
  AtomicReference<BaseServer> getRestServer() {
    return restServer;
  }

  void stopRestServer() {
    Optional.ofNullable(restServer.getAndSet(null)).ifPresent(BaseServer::stop);
  }

  @SuppressWarnings("SameParameterValue")
  void startMetricsServer(Container container) throws UnrecoverableKeyException, CertificateException, IOException,
      NoSuchAlgorithmException, KeyStoreException, InvalidKeySpecException, KeyManagementException {
    startMetricsServer(container, delegate.getMetricsPort());
  }

  // for test
  void startMetricsServer(Container container, int port) throws UnrecoverableKeyException, CertificateException,
      IOException, NoSuchAlgorithmException, KeyStoreException, InvalidKeySpecException, KeyManagementException {
    BaseServer value = new MetricsServer(port);
    metricsServer.set(value);
    value.start(container);
  }

  // for test
  BaseServer getMetricsServer() {
    return metricsServer.get();
  }

  void stopMetricsServer() {
    Optional.ofNullable(metricsServer.getAndSet(null)).ifPresent(BaseServer::stop);
  }

  abstract Step createStartupSteps();

  Step createShutdownSteps() {
    return null;
  }

  abstract void logStartingLivenessMessage();

  void stopAllWatchers() {
    // no-op
  }

  private void acquireShutdownSignal() {
    try {
      shutdownSignal.acquire();
    } catch (InterruptedException ignore) {
      Thread.currentThread().interrupt();
    }
  }

  private void releaseShutdownSignal() {
    shutdownSignal.release();
  }

  // For test
  int getShutdownSignalAvailablePermits() {
    return shutdownSignal.availablePermits();
  }

  void waitForDeath() {
    Runtime.getRuntime().addShutdownHook(new Thread(this::releaseShutdownSignal));
    scheduleCheckForShutdownMarker();

    acquireShutdownSignal();

    stopAllWatchers();
  }

  void scheduleCheckForShutdownMarker() {
    wrappedExecutorService.scheduleWithFixedDelay(
        () -> {
          File marker = new File(delegate.getDeploymentHome(), CoreDelegate.SHUTDOWN_MARKER_NAME);
          if (isFileExists(marker)) {
            releaseShutdownSignal();
          }
        }, 5, 2, TimeUnit.SECONDS);
  }

  private static boolean isFileExists(File file) {
    return Files.isRegularFile(PathSupport.getPath(file));
  }

  static Packet createPacketWithLoggingContext(String ns) {
    Packet packet = new Packet();
    packet.getComponents().put(
        LoggingContext.LOGGING_CONTEXT_KEY,
        Component.createFor(new LoggingContext().namespace(ns)));
    return packet;
  }

  private static class WrappedThreadFactory implements ThreadFactory {
    private final ThreadFactory delegate = Thread.ofVirtual().factory();

    @Override
    public Thread newThread(@Nonnull Runnable r) {
      return delegate.newThread(
          () -> {
            ContainerResolver.getDefault().enterContainer(container);
            r.run();
          });
    }
  }

  static class NullCompletionCallback implements CompletionCallback {
    private final Runnable completionAction;

    NullCompletionCallback(Runnable completionAction) {
      this.completionAction = completionAction;
    }

    @Override
    public void onCompletion(Packet packet) {
      if (completionAction != null) {
        completionAction.run();
      }
    }

    @Override
    public void onThrowable(Packet packet, Throwable throwable) {
      if (throwable instanceof UnrecoverableCallException uce) {
        uce.log();
      } else {
        LOGGER.severe(MessageKeys.EXCEPTION, throwable);
      }
    }
  }
}
